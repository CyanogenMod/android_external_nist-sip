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

package com.android.phone2;

import com.android.internal.telephony.sip.SipPhoneFactory;
import com.android.internal.telephony.sip.SipPhoneProxy;
import com.android.internal.telephony.sip.SipPhone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.sip.SipAudioCall;
import android.net.sip.SipManager;
import android.util.Log;

import javax.sip.SipException;

/**
 * Broadcast receiver that handles SIP-related intents.
 */
public class SipBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = SipBroadcastReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, final Intent intent) {
        String action = intent.getAction();

        if (action.equals(SipManager.SIP_INCOMING_CALL_ACTION)) {
            // TODO: remove background thread when sip service becomes system
            // service
            new Thread(new Runnable() {
                public void run() {
                    takeCall(intent);
                }
            }).start();
            // TODO: bring up InCallScreen

            /*
            intent.setClass(context, SipCallUi.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            */
        } else if (action.equals(SipManager.SIP_ADD_PHONE_ACTION)) {
            String localSipUri = intent.getStringExtra(SipManager.LOCAL_URI_KEY);
            Log.v(TAG, "new profile: " + localSipUri);
            SipPhone phone = SipPhoneFactory.makePhone(localSipUri);
            if (phone != null) {
                // TODO: should call CallManager.getInstance().addPhone()
                SipPhoneProxy.getInstance().setPhone(phone);
            }
        } else {
            Log.v(TAG, "action not processed: " + action);
            return;
        }
    }

    private void takeCall(Intent intent) {
        Context phoneContext = SipPhoneProxy.getInstance().getContext();
        try {
            SipAudioCall sipAudioCall = SipManager.getInstance(phoneContext)
                    .takeAudioCall(phoneContext, intent, null, false);
            SipPhoneProxy.getInstance().onNewCall(sipAudioCall);
        } catch (SipException e) {
            Log.e(TAG, "process incoming SIP call", e);
        }
    }
}
