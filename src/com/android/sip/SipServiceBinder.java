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
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import javax.sip.SipException;

/**
 * The service class for managing a VPN connection. It implements the
 * {@link IVpnService} binder interface.
 */
public class SipServiceBinder extends Service {
    private static final String TAG = SipServiceBinder.class.getSimpleName();

    private SipSessionLayer mSipSessionLayer;

    // SipProfile URI --> receiver
    private Map<String, SipCallReceiver> mSipReceivers =
            new HashMap<String, SipCallReceiver>();

    // session ID --> session
    private Map<String, ISipSession> mPendingSessions =
            new HashMap<String, ISipSession>();

    private ConnectivityReceiver mConnectivityReceiver;
    private boolean mConnected;

    public SipServiceBinder() throws SipException {
        mSipSessionLayer = new SipSessionLayer();
    }

    private final IBinder mBinder = new ISipService.Stub() {
        public void openToReceiveCalls(SipProfile localProfile,
                String incomingCallBroadcastAction) {
            try {
                Log.d(TAG, "openToReceiveCalls: " + localProfile + ": "
                        + incomingCallBroadcastAction);
                createCallReceiver(localProfile, incomingCallBroadcastAction);
            } catch (SipException e) {
                Log.e(TAG, "openToReceiveCalls()", e);
                // TODO: how to send the exception back
            }
        }

        public void close(SipProfile localProfile) {
            closeReceiver(localProfile);
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

        public void onError(ISipSession session, String errorClass,
                String message) {
            Log.d(TAG, "sip session error: " + errorClass + ": " + message);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mConnectivityReceiver = new ConnectivityReceiver();
        registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private synchronized SipCallReceiver getReceiver(SipProfile localProfile) {
        String key = localProfile.getUri().toString();
        return mSipReceivers.get(key);
    }

    private synchronized void createCallReceiver(SipProfile localProfile,
            String incomingCallBroadcastAction) throws SipException {
        String key = localProfile.getUri().toString();
        SipCallReceiver receiver = mSipReceivers.get(key);
        if (receiver != null) {
            receiver.setIncomingCallBroadcastAction(
                    incomingCallBroadcastAction);
        } else {
            receiver = new SipCallReceiver(localProfile,
                    incomingCallBroadcastAction);
            mSipReceivers.put(key, receiver);
        }

        if (mConnected) receiver.openToReceiveCalls();
    }

    private synchronized void closeReceiver(SipProfile localProfile) {
        String key = localProfile.getUri().toString();
        SipCallReceiver receiver = mSipReceivers.remove(key);
        if (receiver != null) receiver.close();
    }

    private synchronized void onConnectivityChanged(boolean connected) {
        mConnected = connected;
        try {
            if (connected) {
                mSipSessionLayer = new SipSessionLayer();
                openAllReceivers();
            } else {
                mSipSessionLayer.onNetworkDisconnected();
                mSipSessionLayer = null;
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

        public SipCallReceiver(SipProfile localProfile,
                String incomingCallBroadcastAction) {
            mLocalProfile = localProfile;
            mIncomingCallBroadcastAction = incomingCallBroadcastAction;
        }

        public void setIncomingCallBroadcastAction(String action) {
            mIncomingCallBroadcastAction = action;
        }

        public void openToReceiveCalls() throws SipException {
            mSipSessionLayer.openToReceiveCalls(mLocalProfile, mCallReceiver);
        }

        public void close() {
            mSipSessionLayer.close(mLocalProfile);
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
    }

    private class ConnectivityReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(
                    ConnectivityManager.CONNECTIVITY_ACTION)) {
                Bundle b = intent.getExtras();
                if (b != null) {
                    NetworkInfo netInfo = (NetworkInfo)
                            b.get(ConnectivityManager.EXTRA_NETWORK_INFO);
                    String info = "Connectivity alert:" + netInfo.getTypeName();
                    NetworkInfo.State state = netInfo.getState();
                    if (state == NetworkInfo.State.CONNECTED) {
                        Log.d(TAG, info + " CONNECTED");
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        Log.d(TAG, info + " DISCONNECTED");
                    }
                    onConnectivityChanged(state == NetworkInfo.State.CONNECTED);
                }
            }
        }
    }


    // TODO: clean up pending SipSession(s) periodically
}
