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
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import javax.sip.SipException;

/**
 */
class SipServiceImpl extends ISipService.Stub {
    private static final String TAG = "SipService";
    private static final int EXPIRY_TIME = 3600;
    private static final int SHORT_EXPIRY_TIME = 10;
    private static final int MIN_EXPIRY_TIME = 60;

    private Context mContext;
    private String mLocalIp;
    private String mNetworkType;
    private boolean mConnected;

    // SipProfile URI --> group
    private Map<String, SipSessionGroupExt> mSipGroups =
            new HashMap<String, SipSessionGroupExt>();

    // session ID --> session
    private Map<String, ISipSession> mPendingSessions =
            new HashMap<String, ISipSession>();

    private ConnectivityReceiver mConnectivityReceiver;

    public SipServiceImpl(Context context) {
        FLog.d(TAG, " service started!");
        mContext = context;
        mConnectivityReceiver = new ConnectivityReceiver();
        context.registerReceiver(mConnectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

    }

    public synchronized void openToReceiveCalls(SipProfile localProfile,
            String incomingCallBroadcastAction,
            ISipSessionListener listener) {
        FLog.d(TAG, "openToReceiveCalls: " + localProfile.getUriString() + ": "
                + incomingCallBroadcastAction + ": " + listener);
        try {
            SipSessionGroupExt group = createGroup(localProfile,
                    incomingCallBroadcastAction, listener);
            group.openToReceiveCalls();
        } catch (SipException e) {
            FLog.e(TAG, "openToReceiveCalls()", e);
            // TODO: how to send the exception back
        }
    }

    public synchronized void close(String localProfileUri) {
        SipSessionGroupExt group = mSipGroups.remove(localProfileUri);
        if (group != null) group.closeToNotReceiveCalls();
    }

    public synchronized boolean isOpened(String localProfileUri) {
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        return ((group != null) ? group.isOpened() : false);
    }

    public synchronized boolean isRegistered(String localProfileUri) {
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        return ((group != null) ? group.isRegistered() : false);
    }

    public synchronized void setRegistrationListener(String localProfileUri,
            ISipSessionListener listener) {
        SipSessionGroupExt group = mSipGroups.get(localProfileUri);
        if (group != null) group.setListener(listener);
    }

    public synchronized ISipSession createSession(SipProfile localProfile,
            ISipSessionListener listener) {
        if (!mConnected) return null;
        try {
            SipSessionGroupExt group = createGroup(localProfile);
            return group.createSession(listener);
        } catch (SipException e) {
            Log.w(TAG, "createSession()", e);
            return null;
        }
    }

    public synchronized ISipSession getPendingSession(String callId) {
        if (callId == null) return null;
        return mPendingSessions.get(callId);
    }

    private void determineLocalIp() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("192.168.1.1"), 80);
            mLocalIp = s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            FLog.w(TAG, "determineLocalIp()", e);
            // dont do anything; suppose there should be a connectivity change
        }
    }

    private SipSessionGroupExt createGroup(SipProfile localProfile)
            throws SipException {
        String key = localProfile.getUriString();
        SipSessionGroupExt group = mSipGroups.get(key);
        if (group == null) {
            group = new SipSessionGroupExt(localProfile, null, null);
            mSipGroups.put(key, group);
        }
        return group;
    }

    private synchronized SipSessionGroupExt createGroup(SipProfile localProfile,
            String incomingCallBroadcastAction, ISipSessionListener listener)
            throws SipException {
        String key = localProfile.getUriString();
        SipSessionGroupExt group = mSipGroups.get(key);
        if (group != null) {
            group.setIncomingCallBroadcastAction(
                    incomingCallBroadcastAction);
            group.setListener(listener);
        } else {
            group = new SipSessionGroupExt(localProfile,
                    incomingCallBroadcastAction, listener);
            mSipGroups.put(key, group);
        }
        return group;
    }

    private synchronized void onConnectivityChanged(
            String type, boolean connected) {
        FLog.d(TAG, "onConnectivityChanged(): "
                + mNetworkType + (mConnected? " CONNECTED" : " DISCONNECTED")
                + " --> " + type + (connected? " CONNECTED" : " DISCONNECTED"));

        if (type.equals(mNetworkType)) {
            if (mConnected == connected) return;
        } else {
            if (!connected) return;
        }

        try {
            boolean wasConnected = mConnected;
            mNetworkType = type;
            mConnected = connected;

            if (wasConnected) {
                for (SipSessionGroupExt group : mSipGroups.values()) {
                    group.onConnectivityChanged(false);
                }
            }

            if (connected) {
                determineLocalIp();
                for (SipSessionGroupExt group : mSipGroups.values()) {
                    group.onConnectivityChanged(true);
                }
            }
        } catch (SipException e) {
            FLog.e(TAG, "onConnectivityChanged()", e);
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

    private class SipSessionGroupExt extends SipSessionAdapter {
        private SipSessionGroup mSipGroup;
        private String mIncomingCallBroadcastAction;
        private boolean mOpened;

        private AutoRegistrationProcess mAutoRegistration =
                new AutoRegistrationProcess();

        public SipSessionGroupExt(SipProfile localProfile,
                String incomingCallBroadcastAction,
                ISipSessionListener listener) throws SipException {
            mSipGroup = new SipSessionGroup(mLocalIp, localProfile);
            mIncomingCallBroadcastAction = incomingCallBroadcastAction;
            mAutoRegistration.setListener(listener);
        }

        public void setListener(ISipSessionListener listener) {
            mAutoRegistration.setListener(listener);
        }

        public void setIncomingCallBroadcastAction(String action) {
            mIncomingCallBroadcastAction = action;
        }

        public synchronized void openToReceiveCalls() throws SipException {
            mOpened = true;
            if (mConnected) {
                mSipGroup.openToReceiveCalls(this);
                mAutoRegistration.start(mSipGroup);
            }
            FLog.v(TAG, "  openToReceiveCalls: " + getUri() + ": "
                    + mIncomingCallBroadcastAction);
        }

        public synchronized void onConnectivityChanged(boolean connected)
                throws SipException {
            if (connected) {
                mSipGroup = new SipSessionGroup(mLocalIp,
                        mSipGroup.getLocalProfile());
                if (mOpened) openToReceiveCalls();
            } else {
                // close mSipGroup but remember mOpened
                FLog.v(TAG, "  close auto reg temporarily: " + getUri() + ": "
                        + mIncomingCallBroadcastAction);
                mSipGroup.close();
                mAutoRegistration.stop();
            }
        }

        public synchronized void closeToNotReceiveCalls() {
            mOpened = false;
            mSipGroup.closeToNotReceiveCalls();
            mAutoRegistration.stop();
            FLog.v(TAG, "   close: " + getUri() + ": "
                    + mIncomingCallBroadcastAction);
        }

        public ISipSession createSession(ISipSessionListener listener) {
            return mSipGroup.createSession(listener);
        }

        @Override
        public void onRinging(ISipSession session, SipProfile caller,
                byte[] sessionDescription) {
            try {
                if (!isRegistered()) {
                    session.endCall();
                    return;
                }

                // send out incoming call broadcast
                Log.d(TAG, " ringing~~ " + getUri() + ": " + caller.getUri()
                        + ": " + session.getCallId());
                addPendingSession(session);
                Intent intent = SipManager.createIncomingCallBroadcast(
                        mIncomingCallBroadcastAction, session.getCallId(),
                        sessionDescription);
                Log.d(TAG, "   send out intent: " + intent);
                mContext.sendBroadcast(intent);
            } catch (RemoteException e) {
                // should never happen with a local call
                Log.e(TAG, "processCall()", e);
            }
        }

        @Override
        public void onError(ISipSession session, String errorClass,
                String message) {
            FLog.d(TAG, "sip session error: " + errorClass + ": " + message);
        }

        public synchronized boolean isOpened() {
            return mOpened;
        }

        public boolean isRegistered() {
            return mAutoRegistration.isRegistered();
        }

        private String getUri() {
            return mSipGroup.getLocalProfileUri();
        }
    }

    private class KeepAliveProcess implements Runnable {
        private SipSessionGroup.SipSessionImpl mSession;
        private WakeupTimer mTimer;
        private static final int DURATION = 15;

        public KeepAliveProcess(SipSessionGroup.SipSessionImpl session) {
            mSession = session;
        }

        private synchronized void scheduleNextHeartbeat() {
            if (mTimer == null) mTimer = new WakeupTimer(mContext);
            Log.d(TAG, "Send SIP keepalive request " + DURATION + "s later.");
            mTimer.cancel(this);
            mTimer.set(DURATION * 1000L, this);
        }

        public void start() {
            scheduleNextHeartbeat();
        }

        public void run() {
            Log.d(TAG, "  ~~~ keepalive");
            try {
                mSession.sendKeepAlive();
            } catch (SipException e) {
                Log.e(TAG, "Cannot send keepalive", e);
            }
            scheduleNextHeartbeat();
        }

        public synchronized void stop() {
            if (mTimer != null) {
                mTimer.stop();
                mTimer = null;
            }
        }
    }

    private class AutoRegistrationProcess extends SipSessionAdapter
            implements Runnable {
        private SipSessionGroup.SipSessionImpl mSession;
        private ISipSessionListener mListener;
        private KeepAliveProcess mKeepAliveProcess;
        private WakeupTimer mTimer;
        private int mBackoff = 1;
        private boolean mRegistered;
        private long mExpiryTime;

        private String getAction() {
            return toString();
        }

        public void start(SipSessionGroup group) {
            if (mSession == null) {
                mBackoff = 1;
                mSession = (SipSessionGroup.SipSessionImpl)
                        group.createSession(this);
                // start unregistration to clear up old registration at server
                // TODO: when rfc5626 is deployed, use reg-id and sip.instance
                // in registration to avoid adding duplicate entries to server
                mSession.unregister();
                FLog.v(TAG, "start AutoRegistrationProcess for "
                        + mSession.getLocalProfile().getUriString());
            }
        }

        public synchronized void stop() {
            if (mSession == null) return;
            if (mConnected) mSession.unregister();
            if (mTimer != null) {
                mTimer.stop();
                mTimer = null;
            }
            if (mKeepAliveProcess != null) {
                mKeepAliveProcess.stop();
                mKeepAliveProcess = null;
            }
            mSession = null;
            mRegistered = false;
        }

        public void setListener(ISipSessionListener listener) {
            Log.v(TAG, "setListener(): " + listener);
            mListener = listener;
            if (mSession == null) return;

            // TODO: separate thread for callback
            try {
                if ((mSession != null) && SipSessionState.REGISTERING.equals(
                        mSession.getState())) {
                    mListener.onRegistering(mSession);
                } else if (mRegistered) {
                    int duration = (int)
                            (mExpiryTime - System.currentTimeMillis());
                    mListener.onRegistrationDone(mSession, duration);
                }
            } catch (Throwable t) {
                Log.w(TAG, "setListener(): " + t);
            }
        }

        public boolean isRegistered() {
            return mRegistered;
        }

        public void run() {
            FLog.d(TAG, "  ~~~ registering");
            if (mConnected) mSession.register(EXPIRY_TIME);
        }

        private boolean isBehindNAT(String address) {
            try {
                byte[] d = InetAddress.getByName(address).getAddress();
                if ((d[0] == 10) ||
                        (((0x000000FF & ((int)d[0])) == 172) &&
                        ((0x000000F0 & ((int)d[1])) == 16)) ||
                        (((0x000000FF & ((int)d[0])) == 192) &&
                        ((0x000000FF & ((int)d[1])) == 168))) {
                    return true;
                }
            } catch (UnknownHostException e) {
                Log.e(TAG, "isBehindAT()" + address, e);
            }
            return false;
        }

        private synchronized void scheduleNextRegistration(int duration) {
            if (mTimer == null) mTimer = new WakeupTimer(mContext);
            FLog.d(TAG, "Refresh registration " + duration + "s later.");
            mTimer.cancel(this);
            mTimer.set(duration * 1000L, this);
        }

        private int backoffDuration() {
            int duration = SHORT_EXPIRY_TIME * mBackoff;
            if (duration > 3600) {
                duration = 3600;
            } else {
                mBackoff *= 2;
            }
            return duration;
        }

        @Override
        public void onRegistering(ISipSession session) {
            FLog.d(TAG, "onRegistering(): " + session + ": " + mSession);
            if (session != mSession) return;
            // TODO: separate thread for callback
            if (mListener != null) {
                try {
                    mListener.onRegistering(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistering()", t);
                }
            }
        }

        @Override
        public void onRegistrationDone(ISipSession session, int duration) {
            FLog.d(TAG, "onRegistrationDone(): " + session + ": " + mSession);
            if (session != mSession) return;
            // TODO: separate thread for callback
            if (mListener != null) {
                try {
                    mListener.onRegistrationDone(session, duration);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationDone()", t);
                }
            }

            if (duration > 0) {
                mRegistered = true;
                mExpiryTime = System.currentTimeMillis() + (duration * 1000);

                // allow some overlap to avoid missing calls during renew
                duration -= MIN_EXPIRY_TIME;
                if (duration < MIN_EXPIRY_TIME) duration = MIN_EXPIRY_TIME;
                scheduleNextRegistration(duration);
                if (isBehindNAT(mLocalIp) ||
                        mSession.getLocalProfile().getSendKeepAlive() == true) {
                    if (mKeepAliveProcess == null) {
                        mKeepAliveProcess = new KeepAliveProcess(mSession);
                    }
                    mKeepAliveProcess.start();
                }
            } else {
                mRegistered = false;
                mExpiryTime = -1L;
                FLog.d(TAG, "Refresh registration immediately");
                run();
            }
        }

        @Override
        public void onRegistrationFailed(ISipSession session, String className,
                String message) {
            FLog.d(TAG, "onRegistrationFailed(): " + session + ": " + mSession
                    + ": " + className + ": " + message);
            if (session != mSession) return;
            mRegistered = false;
            // TODO: separate thread for callback
            if (mListener != null) {
                try {
                    mListener.onRegistrationFailed(session, className, message);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationFailed(): " + t);
                }
            }
            scheduleNextRegistration(backoffDuration());
        }

        @Override
        public void onRegistrationTimeout(ISipSession session) {
            FLog.d(TAG, "onRegistrationTimeout(): " + session + ": " + mSession);
            if (session != mSession) return;
            mRegistered = false;
            // TODO: separate thread for callback
            if (mListener != null) {
                try {
                    mListener.onRegistrationTimeout(session);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationTimeout(): " + t);
                }
            }
            scheduleNextRegistration(backoffDuration());
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
                        FLog.d(TAG, "Connectivity alert: CONNECTED " + type);
                        onChanged(type, true);
                    } else if (state == NetworkInfo.State.DISCONNECTED) {
                        FLog.d(TAG, "Connectivity alert: DISCONNECTED " + type);
                        onChanged(type, false);
                    } else {
                        Log.d(TAG, "Connectivity alert not processed: " + state
                                + " " + type);
                    }
                }
            }
        }

        private void onChanged(String type, boolean connected) {
            synchronized (SipServiceImpl.this) {
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
                synchronized (SipServiceImpl.this) {
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

    // TODO: clean up pending SipSession(s) periodically
}
