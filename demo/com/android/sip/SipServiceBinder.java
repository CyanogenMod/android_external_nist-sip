/*
 * Copyright (C) 2010, The Android Open Source Project
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

import com.android.settings.sip.R;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.IBinder;

// Remove this class once we move the code to framework.
public class SipServiceBinder extends Service {
    private static final String START_AUTO = "android.net.sip.START_AUTO";
    private static final String SIP_NOTIFY = "android.net.sip.NOTIFY";
    private static final int NOTIFICATION_ID = 1;

    private SipServiceImpl mService;

    @Override
    public void onCreate() {
        super.onCreate();
        mService = new SipServiceImpl(this);
        sendBroadcast(new Intent(START_AUTO));
        startForeground(NOTIFICATION_ID, createNotification());
        registerOutgoingCallReceiver();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mService;
    }

    private void registerOutgoingCallReceiver() {
        IntentFilter intentfilter = new IntentFilter();
        intentfilter.addAction(Intent.ACTION_NEW_OUTGOING_CALL);
        intentfilter.setPriority(-1);
        registerReceiver(new com.android.sip.demo.OutgoingCallReceiver(),
                intentfilter);
    }

    private Notification createNotification() {
        String title = "SIP service on";
        Notification n = new Notification(R.drawable.voip, title,
                System.currentTimeMillis());
        n.setLatestEventInfo(SipServiceBinder.this, title, "",
                prepareNotificationIntent());
        n.flags |= Notification.FLAG_NO_CLEAR;
        n.flags |= Notification.FLAG_ONGOING_EVENT;
        return n;
    }

    private PendingIntent prepareNotificationIntent() {
        // bogus intent
        return PendingIntent.getActivity(SipServiceBinder.this, 0,
                new Intent(SIP_NOTIFY), 0);
    }
}
