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

package android.net.sip;

import com.android.sip.SipAudioCallImpl;

import android.content.Context;
import android.content.Intent;
import android.os.RemoteException;

import javax.sip.SipException;

/**
 * @hide
 */
public class SipManager {
    private static final String CALL_ID_KEY = "CallID";
    private static final String OFFER_SD_KEY = "OfferSD";

    private static ISipService sSipService;
    private static BinderHelper<ISipService> sBinderHelper;

    private SipManager() {
    }


    /**
     * ISipService must be obtained from non-main thread.
     * (tentative; will be relaxed after integrated into framework)
     */
    public static ISipService getSipService(Context context) {
        if (sSipService != null) return sSipService;
        if (sBinderHelper == null) {
            sBinderHelper = new BinderHelper<ISipService>(
                    context, ISipService.class);
            sBinderHelper.startService();
        }
        sSipService = ISipService.Stub.asInterface(sBinderHelper.getBinder());
        return sSipService;
    }

    public static SipAudioCall createSipAudioCall(Context context,
            SipProfile localProfile, SipAudioCall.Listener listener) {
        SipAudioCall call = new SipAudioCallImpl(context, localProfile);
        call.setListener(listener);
        return call;
    }

    public static SipAudioCall createSipAudioCall(Context context,
            String sessionId, byte[] offerSd, SipAudioCall.Listener listener)
            throws SipException {
        try {
            ISipSession session = sSipService.getPendingSession(sessionId);
            if (session == null) return null;
            SipAudioCall call = new SipAudioCallImpl(
                    context, session.getLocalProfile());
            call.attachCall(session, offerSd);
            call.setListener(listener);
            return call;
        } catch (RemoteException e) {
            throw new SipException("createSipAudioCall()", e);
        }
    }

    public static String getCallId(Intent incomingCallIntent) {
        return incomingCallIntent.getStringExtra(CALL_ID_KEY);
    }

    public static byte[] getOfferSessionDescription(Intent incomingCallIntent) {
        return incomingCallIntent.getByteArrayExtra(OFFER_SD_KEY);
    }

    public static Intent createIncomingCallBroadcast(String action,
            String callId, byte[] sessionDescription) {
        Intent intent = new Intent(action);
        intent.putExtra(CALL_ID_KEY, callId);
        intent.putExtra(OFFER_SD_KEY, sessionDescription);
        return intent;
    }
}
