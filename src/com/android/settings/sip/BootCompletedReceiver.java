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

package com.android.settings.sip;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * The BroadcastReceiver class for enabling sip profiles in the storage after
 * system boots up.
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";

    public void onReceive(Context context, Intent intent) {
        // TODO: once we move sip service to framework, it could depend on the
        // sip service to bring up this.
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.e(TAG, "should not be here " + intent.getAction());
            return;
        }
        SharedPreferences settings = context.getSharedPreferences(
                SipAutoRegistration.SIP_SHARED_PREFERENCES,
                Context.MODE_WORLD_READABLE);
        boolean autoReg = settings.getBoolean(
                SipAutoRegistration.AUTOREG_FLAG, false);
        if (autoReg) {
            context.startService(
                    new Intent("android.net.sip.AUTO_REGISTRATOIN"));
        }
    }
}

