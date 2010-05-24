/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.sip.demo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.sip.SipProfile;
import android.net.sip.SipManager;
import android.os.Parcelable;
import android.util.Log;

import com.android.settings.sip.ProfileUtil;
import com.android.settings.sip.SipAutoRegistration;

import java.util.List;
import javax.sip.SipException;

/**
 * Broadcast receiver that handles incoming call intents.
 */
public class OutgoingCallReceiver extends BroadcastReceiver {
    private static final String TAG = OutgoingCallReceiver.class.getSimpleName();
    private SipManager mSipManager;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String number = getResultData();

        if (mSipManager == null) {
            mSipManager = SipManager.getInstance(context);
            setResultData(null);
            return;
        }

        if (!action.equals(Intent.ACTION_NEW_OUTGOING_CALL) || number == null) {
            Log.v(TAG, "action not processed: " + action);
            return;
        }
        SharedPreferences settings = context.getSharedPreferences(
                SipAutoRegistration.SIP_SHARED_PREFERENCES,
                Context.MODE_WORLD_READABLE);
        boolean sipCallFirst = settings.getBoolean(
                SipAutoRegistration.SIP_CALL_FIRST_FLAG, false);

        if (!sipCallFirst || !makeCall(context, number)) {
            setResultData(number);
            return;
        }
        setResultData(null);
    }

    private boolean makeCall(Context context, String number) {
        List<SipProfile> profiles = ProfileUtil.retrieveSipProfiles(
                "/data/data/com.android.settings.sip/files/");
        for (SipProfile profile: profiles) {
            try {
            
                if (mSipManager.isOpened(profile.getUriString())) {
                    Log.v(TAG, "CALLING " + number + "@" +
                            profile.getSipDomain());

                    Intent intent = new Intent(context, SipCallUi.class);
                    intent.setAction("call");
                    intent.putExtra("caller", (Parcelable) profile);
                    intent.putExtra("callee", number + "@" +
                            profile.getSipDomain());
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    return true;
                }
            } catch (SipException e) {
                Log.e(TAG, "can not get status of profile" +
                        profile.getProfileName(), e);
            }
        }
        return false;
    }
}

