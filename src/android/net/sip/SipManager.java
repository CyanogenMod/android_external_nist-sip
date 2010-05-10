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
import android.os.Looper;
import android.os.RemoteException;

import javax.sip.SipException;

/**
 * The class provides API for various SIP related tasks. Specifically, the API
 * allows the application:
 * <ul>
 * <li>to register a {@link SipProfile} to have the background SIP service
 *      listen to incoming calls and broadcast them with registered command
 *      string. See
 *      {@link #openToReceiveCalls(SipProfile, String, SipRegistrationListener)},
 *      {@link #close(String)}, {@link #isOpened(String)} and
 *      {@link isRegistered(String)}. It also facilitates handling of the
 *      incoming call broadcast intent. See
 *      {@link #isIncomingCallIntent(Intent)}, {@link #getCallId(Intent)},
 *      {@link #getOfferSessionDescription(Intent)} and
 *      {@link #takeAudioCall(Context, Intent, SipAudioCall.Listener)}.</li>
 * <li>to make/take SIP-based audio calls. See
 *      {@link #makeAudioCall(Context, SipProfile, SipProfile, SipAudioCall.Listener)}
 *      and {@link #takeAudioCall(Context, Intent, SipAudioCall.Listener}.</li>
 * <li>to register/unregister with a SIP service provider. See
 *      {@link #register(SipProfile, int, ISipSessionListener)} and
 *      {@link #unregister(SipProfile, ISipSessionListener)}.</li>
 * </ul>
 * @hide
 */
public class SipManager {
    private static final String CALL_ID_KEY = "CallID";
    private static final String OFFER_SD_KEY = "OfferSD";

    private static ISipService sSipService;
    private static BinderHelper<ISipService> sBinderHelper;

    private SipManager() {
    }


    private static void createSipService(Context context) {
        if (sSipService != null) return;
        if (sBinderHelper == null) {
            sBinderHelper = new BinderHelper<ISipService>(
                    context, ISipService.class);
            sBinderHelper.startService();
        }
        sSipService = ISipService.Stub.asInterface(sBinderHelper.getBinder());
    }

    /**
     * Initializes the background SIP service. Will be removed once the SIP
     * service is integrated into framework.
     */
    public static void initialize(final Context context) {
        // ISipService must be created from non-main thread.
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            new Thread(new Runnable() {
                public void run() {
                    createSipService(context);
                }
            }).start();
        } else {
            createSipService(context);
        }
    }

    public static void openToReceiveCalls(SipProfile localProfile,
            String incomingCallBroadcastAction,
            SipRegistrationListener listener) throws SipException {
        try {
            sSipService.openToReceiveCalls(localProfile,
                    incomingCallBroadcastAction, createRelay(listener));
        } catch (RemoteException e) {
            throw new SipException("openToReceiveCalls()", e);
        }
    }

    public static void close(SipProfile localProfile) throws SipException {
        try {
            sSipService.close(localProfile);
        } catch (RemoteException e) {
            throw new SipException("close()", e);
        }
    }

    public static boolean isOpened(String localProfileUri) throws SipException {
        try {
            return sSipService.isOpened(localProfileUri);
        } catch (RemoteException e) {
            throw new SipException("isOpened()", e);
        }
    }

    public static boolean isRegistered(String localProfileUri) throws SipException {
        try {
            return sSipService.isRegistered(localProfileUri);
        } catch (RemoteException e) {
            throw new SipException("isRegistered()", e);
        }
    }

    public static SipAudioCall makeAudioCall(Context context,
            SipProfile localProfile, SipProfile peerProfile,
            SipAudioCall.Listener listener) throws SipException {
        SipAudioCall call = new SipAudioCallImpl(context, localProfile);
        call.setListener(listener);
        call.makeCall(peerProfile, sSipService);
        return call;
    }

    public static SipAudioCall takeAudioCall(Context context,
            Intent incomingCallIntent, SipAudioCall.Listener listener)
            throws SipException {
        if (incomingCallIntent == null) return null;

        String callId = getCallId(incomingCallIntent);
        if (callId == null) {
            throw new SipException("Call ID missing in incoming call intent");
        }

        byte[] offerSd = getOfferSessionDescription(incomingCallIntent);
        if (offerSd == null) {
            throw new SipException("Session description missing in incoming "
                    + "call intent");
        }

        try {
            ISipSession session = sSipService.getPendingSession(callId);
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

    private static SipAudioCall createSipAudioCall(Context context,
            String callId, byte[] offerSd, SipAudioCall.Listener listener)
            throws SipException {
        try {
            ISipSession session = sSipService.getPendingSession(callId);
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

    public static boolean isIncomingCallIntent(Intent intent) {
        if (intent == null) return false;
        String callId = getCallId(intent);
        byte[] offerSd = getOfferSessionDescription(intent);
        return ((callId != null) && (offerSd != null));
    }

    public static String getCallId(Intent incomingCallIntent) {
        return incomingCallIntent.getStringExtra(CALL_ID_KEY);
    }

    public static byte[] getOfferSessionDescription(Intent incomingCallIntent) {
        return incomingCallIntent.getByteArrayExtra(OFFER_SD_KEY);
    }

    /** @hide */
    public static Intent createIncomingCallBroadcast(String action,
            String callId, byte[] sessionDescription) {
        Intent intent = new Intent(action);
        intent.putExtra(CALL_ID_KEY, callId);
        intent.putExtra(OFFER_SD_KEY, sessionDescription);
        return intent;
    }

    public static void register(SipProfile localProfile, int expiryTime,
            SipRegistrationListener listener) throws SipException {
        try {
            ISipSession session = sSipService.createSession(
                    localProfile, createRelay(listener));
            session.register(expiryTime);
        } catch (RemoteException e) {
            throw new SipException("register()", e);
        }
    }

    public static void unregister(SipProfile localProfile,
            SipRegistrationListener listener) throws SipException {
        try {
            ISipSession session = sSipService.createSession(
                    localProfile, createRelay(listener));
            session.unregister();
        } catch (RemoteException e) {
            throw new SipException("unregister()", e);
        }
    }

    private static ISipSessionListener createRelay(
            SipRegistrationListener listener) {
        return ((listener == null) ? null : new ListenerRelay(listener));
    }

    public static ISipSession createSipSession(SipProfile localProfile,
            ISipSessionListener listener) throws SipException {
        try {
            return sSipService.createSession(localProfile, listener);
        } catch (RemoteException e) {
            throw new SipException("createSipSession()", e);
        }
    }

    private static class ListenerRelay extends SipSessionAdapter {
        private SipRegistrationListener mListener;

        // listener must not be null
        public ListenerRelay(SipRegistrationListener listener) {
            mListener = listener;
        }

        private String getUri(ISipSession session) {
            try {
                return session.getLocalProfile().getUriString();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onRegistering(ISipSession session) {
            mListener.onRegistering(getUri(session));
        }

        @Override
        public void onRegistrationDone(ISipSession session, int duration) {
            long expiryTime = duration;
            if (duration > 0) expiryTime += System.currentTimeMillis();
            mListener.onRegistrationDone(getUri(session), expiryTime);
        }

        @Override
        public void onRegistrationFailed(ISipSession session, String className,
                String message) {
            mListener.onRegistrationFailed(getUri(session), className, message);
        }

        @Override
        public void onRegistrationTimeout(ISipSession session) {
            mListener.onRegistrationFailed(getUri(session),
                    SipException.class.getName(), "registration timed out");
        }
    }
}
