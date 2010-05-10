/*
 * Copyright (C) 2009, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.sip.ISipService;
import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSessionAdapter;
import android.net.sip.SipSessionState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Timer;
import java.util.TimerTask;
import javax.sip.SipException;

/**
 * The service class for managing a VPN connection. It implements the
 * {@link IVpnService} binder interface.
 */
public class SipServiceBinder extends Service {
    private static final String TAG = SipServiceBinder.class.getSimpleName();
    private static final String TIMER_TAG = "_SIP.WakeupTimer_";

    private SipSessionLayer mSipSessionLayer;
    private String mNetworkType;
    private boolean mConnected;
    private AlarmManager mAlarmManager;

    // SipProfile URI --> receiver
    private Map<String, SipCallReceiver> mSipReceivers =
            new HashMap<String, SipCallReceiver>();

    // session ID --> session
    private Map<String, ISipSession> mPendingSessions =
            new HashMap<String, ISipSession>();

    private ConnectivityReceiver mConnectivityReceiver;

    private final IBinder mBinder = new ISipService.Stub() {
        public void openToReceiveCalls(SipProfile localProfile,
                String incomingCallBroadcastAction,
                ISipSessionListener listener) {
            try {
                Log.d(TAG, "openToReceiveCalls: " + localProfile + ": "
                        + incomingCallBroadcastAction);
                createCallReceiver(localProfile, incomingCallBroadcastAction,
                        listener);
            } catch (SipException e) {
                Log.e(TAG, "openToReceiveCalls()", e);
                // TODO: how to send the exception back
            }
        }

        public void close(SipProfile localProfile) {
            closeReceiver(localProfile);
        }

        public boolean isOpened(String localProfileUri) {
            return SipServiceBinder.this.isOpened(localProfileUri);
        }

        public boolean isRegistered(String localProfileUri) {
            return SipServiceBinder.this.isRegistered(localProfileUri);
        }

        public ISipSession createSession(SipProfile localProfile,
                ISipSessionListener listener) {
            if (!mConnected) return null;
            try {
                return mSipSessionLayer.createSession(localProfile, listener);
            } catch (SipException e) {
                Log.e(TAG, "createSession()", e);
                // TODO: how to send the exception back
                return null;
            }
        }

        public ISipSession getPendingSession(String callId) {
            if (callId == null) return null;
            return mPendingSessions.get(callId);
        }
    };

    private ISipSessionListener mCallReceiver = new SipSessionAdapter() {
        @Override
        public void onRinging(ISipSession session, SipProfile caller,
                byte[] sessionDescription) {
            try {
                SipProfile localProfile = session.getLocalProfile();
                SipCallReceiver receiver = getReceiver(localProfile);
                Log.d(TAG, " ringing~~ " + localProfile.getUri() + ": "
                        + caller.getUri() + ": receiver=" + receiver);
                if (receiver != null) {
                    receiver.processCall(session, caller, sessionDescription);
                } else {
                    // TODO: reject the call
                }
            } catch (RemoteException e) {
                // should not happen with a local call
                Log.e(TAG, "onRinging()", e);
            }
        }

        @Override
        public void onError(ISipSession session, String errorClass,
                String message) {
            Log.d(TAG, "sip session error: " + errorClass + ": " + message);
        }

        @Override
        public void onRegistering(ISipSession session) {
            try {
                SipProfile localProfile = session.getLocalProfile();
                SipCallReceiver receiver = getReceiver(localProfile);
                Log.d(TAG, "registering: " + localProfile.getUri());
                if (receiver != null) receiver.onRegistering(session);
            } catch (RemoteException e) {
                // should not happen with a local call
                Log.e(TAG, "onRegistering()", e);
            }
        }

        @Override
        public void onRegistrationDone(ISipSession session, int duration) {
            try {
                SipProfile localProfile = session.getLocalProfile();
                SipCallReceiver receiver = getReceiver(localProfile);
                Log.d(TAG, "registration done: " + localProfile.getUri());
                if (receiver != null) {
                    receiver.onRegistrationDone(session, duration);
                }
            } catch (RemoteException e) {
                // should not happen with a local call
                Log.e(TAG, "onRegistrationDone()", e);
            }
        }

        @Override
        public void onRegistrationFailed(ISipSession session, String className,
                String message) {
            try {
                SipProfile localProfile = session.getLocalProfile();
                SipCallReceiver receiver = getReceiver(localProfile);
                Log.d(TAG, "registration failed: " + localProfile.getUri());
                if (receiver != null) {
                    receiver.onRegistrationFailed(session, className, message);
                }
            } catch (RemoteException e) {
                // should not happen with a local call
                Log.e(TAG, "onRegistrationFailed()", e);
            }
        }

        @Override
        public void onRegistrationTimeout(ISipSession session) {
            try {
                SipProfile localProfile = session.getLocalProfile();
                SipCallReceiver receiver = getReceiver(localProfile);
                Log.d(TAG, "registration timed out: " + localProfile.getUri());
                if (receiver != null) {
                    receiver.onRegistrationTimeout(session);
                }
            } catch (RemoteException e) {
                // should not happen with a local call
                Log.e(TAG, "onRegistrationTimeout()", e);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mConnectivityReceiver = new ConnectivityReceiver();
        registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        WakeupTimer.Factory.setInstance(new WakeupTimer.Factory() {
            public WakeupTimer createTimer() {
                return new MyWakeupTimer();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private synchronized SipCallReceiver getReceiver(SipProfile localProfile) {
        String key = localProfile.getUriString();
        return mSipReceivers.get(key);
    }

    private synchronized void createCallReceiver(SipProfile localProfile,
            String incomingCallBroadcastAction,
            ISipSessionListener listener) throws SipException {
        String key = localProfile.getUriString();
        SipCallReceiver receiver = mSipReceivers.get(key);
        if (receiver != null) {
            receiver.setIncomingCallBroadcastAction(
                    incomingCallBroadcastAction);
            receiver.setListener(listener);
        } else {
            receiver = new SipCallReceiver(localProfile,
                    incomingCallBroadcastAction, listener);
            mSipReceivers.put(key, receiver);
        }

        if (mConnected) receiver.openToReceiveCalls();
    }

    private synchronized void closeReceiver(SipProfile localProfile) {
        String key = localProfile.getUriString();
        SipCallReceiver receiver = mSipReceivers.remove(key);
        if (receiver != null) receiver.close();
    }

    private synchronized boolean isOpened(String localProfileUri) {
        return mSipReceivers.containsKey(localProfileUri);
    }

    private synchronized boolean isRegistered(String localProfileUri) {
        SipCallReceiver receiver = mSipReceivers.get(localProfileUri);
        return ((receiver != null) ? receiver.isRegistered() : false);
    }

    private synchronized void onConnectivityChanged(
            String type, boolean connected) {
        if (type.equals(mNetworkType)) {
            if (mConnected == connected) return;
        } else {
            if (!connected) return;
        }

        Log.d(TAG, "onConnectivityChanged(): "
                + mNetworkType + (mConnected? " CONNECTED" : " DISCONNECTED")
                + " --> " + type + (connected? " CONNECTED" : " DISCONNECTED"));

        try {
            if (mConnected) {
                mSipSessionLayer.onNetworkDisconnected();
                mSipSessionLayer = null;
            }
            mNetworkType = type;
            mConnected = connected;
            if (connected) {
                mSipSessionLayer = new SipSessionLayer();
                openAllReceivers();
            }
        } catch (SipException e) {
            Log.e(TAG, "onConnectivityChanged()", e);
        }
    }

    private void openAllReceivers() {
        for (SipCallReceiver receiver : mSipReceivers.values()) {
            try {
                receiver.openToReceiveCalls();
            } catch (SipException e) {
                Log.e(TAG, "openAllReceivers()", e);
            }
        }
    }

    private synchronized void addPendingSession(ISipSession session) {
        try {
            mPendingSessions.put(session.getCallId(), session);
        } catch (RemoteException e) {
            // should not happen with a local call
            Log.e(TAG, "addPendingSession()", e);
        }
    }

    private class SipCallReceiver {
        private SipProfile mLocalProfile;
        private String mIncomingCallBroadcastAction;
        private ISipSessionListener mListener;
        private boolean mRegistered;
        private ISipSession mSipSession;
        private long mExpiryTime;

        public SipCallReceiver(SipProfile localProfile,
                String incomingCallBroadcastAction,
                ISipSessionListener listener) {
            mLocalProfile = localProfile;
            mIncomingCallBroadcastAction = incomingCallBroadcastAction;
            mListener = listener;
        }

        public void setListener(ISipSessionListener listener) {
            mListener = listener;
            if ((listener == null) || (mSipSession == null)) return;

            // TODO: separate thread for callback
            try {
                if (SipSessionState.REGISTERING.equals(
                        mSipSession.getState())) {
                    mListener.onRegistering(mSipSession);
                } else if (mRegistered) {
                    int duration = (int)
                            (mExpiryTime - System.currentTimeMillis());
                    mListener.onRegistrationDone(mSipSession, duration);
                }
            } catch (Throwable t) {
                Log.w(TAG, "setListener(): " + t);
            }
        }

        public void setIncomingCallBroadcastAction(String action) {
            mIncomingCallBroadcastAction = action;
        }

        public void openToReceiveCalls() throws SipException {
            mSipSessionLayer.openToReceiveCalls(mLocalProfile, mCallReceiver);
            Log.v(TAG, "   openToReceiveCalls: " + mIncomingCallBroadcastAction);
        }

        public void close() {
            mSipSessionLayer.close(mLocalProfile);
            Log.v(TAG, "   close: " + mIncomingCallBroadcastAction);
        }

        public void processCall(ISipSession session, SipProfile caller,
                byte[] sessionDescription) {
            try {
                addPendingSession(session);
                Intent intent = SipManager.createIncomingCallBroadcast(
                        mIncomingCallBroadcastAction, session.getCallId(),
                        sessionDescription);
                Log.d(TAG, "   send out intent: " + intent);
                sendBroadcast(intent);
            } catch (RemoteException e) {
                // should never happen with a local call
                Log.e(TAG, "processCall()", e);
            }
        }

        public boolean isRegistered() {
            return mRegistered;
        }

        public void onRegistering(ISipSession session) {
            mSipSession = session;
            // TODO: separate thread for callback
            if (mListener != null) {
                try {
                    mListener.onRegistering(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistering(): " + t);
                }
            }
        }

        public void onRegistrationDone(ISipSession session, int duration) {
            mSipSession = session;
            if (duration < 0) {
                mRegistered = false;
                mExpiryTime = -1L;
            } else {
                mRegistered = true;
                mExpiryTime = System.currentTimeMillis() + duration;
            }
            // TODO: separate thread for callback
            if (mListener != null) {
                try {
                    mListener.onRegistrationDone(session, duration);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationDone(): " + t);
                }
            }
        }

        public void onRegistrationFailed(ISipSession session, String className,
                String message) {
            mSipSession = session;
            mRegistered = false;
            // TODO: separate thread for callback
            if (mListener != null) {
                try {
                    mListener.onRegistrationFailed(session, className, message);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationFailed(): " + t);
                }
            }
        }

        public void onRegistrationTimeout(ISipSession session) {
            mSipSession = session;
            mRegistered = false;
            // TODO: separate thread for callback
            if (mListener != null) {
                try {
                    mListener.onRegistrationTimeout(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationTimeout(): " + t);
                }
            }
        }

        private String getUri() {
            return mLocalProfile.getUriString();
        }
    }

    private class ConnectivityReceiver extends BroadcastReceiver {
        private Timer mTimer = new Timer();
        private MyTimerTask mTask;

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                Bundle b = intent.getExtras();
                if (b != null) {
                    NetworkInfo netInfo = (NetworkInfo)
                            b.get(ConnectivityManager.EXTRA_NETWORK_INFO);
                    String type = netInfo.getTypeName();
                    NetworkInfo.State state = netInfo.getState();
                    if (state == NetworkInfo.State.CONNECTED) {
                        Log.d(TAG, "Connectivity alert: CONNECTED " + type);
                        onChanged(type, true);
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        Log.d(TAG, "Connectivity alert: DISCONNECTED " + type);
                        onChanged(type, false);
                    } else {
                        Log.d(TAG, "Connectivity alert not processed: " + state
                                + " " + type);
                    }
                }
            }
        }

        private void onChanged(String type, boolean connected) {
            synchronized (SipServiceBinder.this) {
                // When turning on WIFI, it needs some time for network
                // connectivity to get stabile so we defer good news (because
                // we want to skip the interim ones) but deliver bad news
                // immediately
                if (connected) {
                    if (mTask != null) mTask.cancel();
                    mTask = new MyTimerTask(type, connected);
                    mTimer.schedule(mTask, 3 * 1000L);
                    // TODO: hold wakup lock so that we can finish change before
                    // the device goes to sleep
                } else {
                    if ((mTask != null) && mTask.mNetworkType.equals(type)) {
                        mTask.cancel();
                    }
                    onConnectivityChanged(type, false);
                }
            }
        }

        private class MyTimerTask extends TimerTask {
            private boolean mConnected;
            private String mNetworkType;

            public MyTimerTask(String type, boolean connected) {
                mNetworkType = type;
                mConnected = connected;
            }

            @Override
            public void run() {
                synchronized (SipServiceBinder.this) {
                    if (mTask != this) {
                        Log.w(TAG, "  unexpected task: " + mNetworkType
                                + (mConnected ? " CONNECTED" : "DISCONNECTED"));
                        return;
                    }
                    mTask = null;
                    Log.v(TAG, " deliver change for " + mNetworkType
                            + (mConnected ? " CONNECTED" : "DISCONNECTED"));
                    onConnectivityChanged(mNetworkType, mConnected);
                }
            }
        }
    }

    private class MyWakeupTimer extends BroadcastReceiver
            implements WakeupTimer, Comparator<MyEvent> {
        private static final String EVENT_ID = "EventID";

        // runnable --> time to execute in SystemClock
        private PriorityQueue<MyEvent> mEventQueue =
                new PriorityQueue<MyEvent>(1, this);

        public MyWakeupTimer() {
            IntentFilter filter = new IntentFilter(getAction());
            registerReceiver(this, filter);
        }

        public synchronized void stop() {
            unregisterReceiver(this);
            for (MyEvent event : mEventQueue) {
                mAlarmManager.cancel(event.mPendingIntent);
            }
            mEventQueue.clear();
            mEventQueue = null;
        }

        private boolean stopped() {
            if (mEventQueue == null) {
                Log.w(TIMER_TAG, "Timer stopped");
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
                    PendingIntent.getBroadcast(
                    SipServiceBinder.this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            return event;
        }

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

            Log.v(TIMER_TAG, " add event " + event + " scheduled at "
                    + showTime(triggerTime) + " at " + showTime(t)
                    + ", #events=" + mEventQueue.size());
            printQueue();
        }

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
                    Log.d(TIMER_TAG, "time's up for " + eventId
                            + " but event q is:");
                    printQueue();
                }
            }
        }

        private void printQueue() {
            int count = 0;
            for (MyEvent event : mEventQueue) {
                Log.d(TIMER_TAG, "     " + event + ": scheduled at "
                        + showTime(event));
                if (++count >= 5) break;
            }
            if (mEventQueue.size() > count) {
                Log.d(TIMER_TAG, "     .....");
            }
        }

        // Comparator
        public int compare(MyEvent e1, MyEvent e2) {
            return (int) ((e1.mTriggerTime - e2.mTriggerTime) >> 32);
        }

        // Comparator
        public boolean equals(Object that) {
            return (this == that);
        }

        private synchronized void execute() {
            if (stopped()) return;

            MyEvent firstEvent = mEventQueue.poll();
            if (firstEvent != null) {
                Log.d(TIMER_TAG, "execute " + firstEvent + " at "
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
    }

    private class MyEvent {
        PendingIntent mPendingIntent;
        long mTriggerTime;
        Runnable mCallback;
    }

    private static long showTime() {
        return showTime(SystemClock.elapsedRealtime());
    }

    private static long showTime(MyEvent event) {
        return showTime(event.mTriggerTime);
    }

    private static long showTime(long time) {
        return (time / 60 / 1000 % 10000);
    }

    // TODO: clean up pending SipSession(s) periodically
}
