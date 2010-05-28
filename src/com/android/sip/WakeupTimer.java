/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.sip;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.util.Log;

import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;

/**
 * Timer that can schedule events to occur even when the device is in sleep.
 * Only used internally in this package.
 */
class WakeupTimer extends BroadcastReceiver {
    private static final String TAG = "__SIP.WakeupTimer__";
    private static final String EVENT_ID = "EventID";

    private Context mContext;
    private AlarmManager mAlarmManager;

    // runnable --> time to execute in SystemClock
    private PriorityQueue<MyEvent> mEventQueue =
            new PriorityQueue<MyEvent>(1, new MyEventComparator());

    public WakeupTimer(Context context) {
        mContext = context;
        mAlarmManager = (AlarmManager)
                context.getSystemService(Context.ALARM_SERVICE);

        IntentFilter filter = new IntentFilter(getAction());
        context.registerReceiver(this, filter);
    }

    /**
     * Stops the timer. No event can be scheduled after this method is called.
     */
    public synchronized void stop() {
        mContext.unregisterReceiver(this);
        for (MyEvent event : mEventQueue) {
            mAlarmManager.cancel(event.mPendingIntent);
        }
        mEventQueue.clear();
        mEventQueue = null;
    }

    private boolean stopped() {
        if (mEventQueue == null) {
            Log.w(TAG, "Timer stopped");
            return true;
        } else {
            return false;
        }
    }

    private MyEvent createMyEvent(long triggerTime, Runnable callback) {
        MyEvent event = new MyEvent();
        event.mTriggerTime = triggerTime;
        event.mCallback = callback;

        Intent intent = new Intent(getAction());
        intent.putExtra(EVENT_ID, event.toString());
        PendingIntent pendingIntent = event.mPendingIntent =
                PendingIntent.getBroadcast(mContext, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
        return event;
    }

    /**
     * Sets a timer event.
     *
     * @param delay the delay from now when the timer goes off; in milli-second
     * @param callback is called back when the timer goes off; the same
     *      callback can be specified in multiple timer events
     */
    public synchronized void set(long delay, Runnable callback) {
        if (stopped()) return;

        long t = SystemClock.elapsedRealtime();
        long triggerTime = t + delay;
        MyEvent event = createMyEvent(triggerTime, callback);

        MyEvent firstEvent = mEventQueue.peek();
        if (!mEventQueue.offer(event)) {
            throw new RuntimeException("failed to add event: " + callback);
        }
        if (mEventQueue.peek() == event) {
            if (firstEvent != null) {
                mAlarmManager.cancel(firstEvent.mPendingIntent);
            }
            scheduleNext();
        }

        Log.v(TAG, " add event " + event + " scheduled at "
                + showTime(triggerTime) + " at " + showTime(t)
                + ", #events=" + mEventQueue.size());
        printQueue();
    }

    /**
     * Cancels all the timer events with the specified callback.
     *
     * @param callback the callback
     */
    public synchronized void cancel(Runnable callback) {
        if (stopped()) return;

        MyEvent firstEvent = mEventQueue.peek();
        for (Iterator<MyEvent> iter = mEventQueue.iterator();
                iter.hasNext();) {
            MyEvent event = iter.next();
            if (event.mCallback == callback) iter.remove();
        }
        if ((firstEvent != null) && (firstEvent.mCallback == callback)) {
            mAlarmManager.cancel(firstEvent.mPendingIntent);
            scheduleNext();
        }
    }

    private void scheduleNext() {
        if (stopped() || mEventQueue.isEmpty()) return;

        MyEvent event = mEventQueue.peek();
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                event.mTriggerTime, event.mPendingIntent);
    }

    @Override
    public synchronized void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (getAction().equals(action)) {
            MyEvent firstEvent = mEventQueue.peek();
            String eventId = intent.getStringExtra(EVENT_ID);
            if (firstEvent.toString().equals(eventId)) {
                execute();
            } else {
                // log the unexpected event schedule
                Log.d(TAG, "time's up for " + eventId
                        + " but event q is:");
                printQueue();
            }
        }
    }

    private void printQueue() {
        int count = 0;
        for (MyEvent event : mEventQueue) {
            Log.d(TAG, "     " + event + ": scheduled at "
                    + showTime(event));
            if (++count >= 5) break;
        }
        if (mEventQueue.size() > count) {
            Log.d(TAG, "     .....");
        }
    }

    private synchronized void execute() {
        if (stopped()) return;

        MyEvent firstEvent = mEventQueue.poll();
        if (firstEvent != null) {
            Log.d(TAG, "execute " + firstEvent + " at "
                    + showTime(firstEvent));
            // run the callback in a new thread to prevent deadlock
            new Thread(firstEvent.mCallback).start();

            // TODO: fire all the events that are already late
            scheduleNext();
        }
        printQueue();
    }

    private String getAction() {
        return toString();
    }

    private static class MyEvent {
        PendingIntent mPendingIntent;
        long mTriggerTime;
        Runnable mCallback;
    }

    private static class MyEventComparator implements Comparator<MyEvent> {
        public int compare(MyEvent e1, MyEvent e2) {
            long diff = e1.mTriggerTime - e2.mTriggerTime;
            return (diff > 0) ? 1 : ((diff == 0) ? 0 : -1);
        }

        public boolean equals(Object that) {
            return (this == that);
        }
    }

    private static long showTime() {
        return showTime(SystemClock.elapsedRealtime());
    }

    private static long showTime(MyEvent event) {
        return showTime(event.mTriggerTime);
    }

    private static long showTime(long time) {
        return (time / 1000 % 10000);
    }
}
