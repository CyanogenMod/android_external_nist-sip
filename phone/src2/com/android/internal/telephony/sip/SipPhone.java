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

package com.android.internal.telephony.sip;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.sip.SipAudioCall;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSessionState;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.preference.PreferenceManager;
import android.provider.Telephony;
import android.telephony.CellLocation;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DataConnection;
import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.IccFileHandler;
import com.android.internal.telephony.IccPhoneBookInterfaceManager;
import com.android.internal.telephony.IccSmsInterfaceManager;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneNotifier;
import com.android.internal.telephony.PhoneProxy;
import com.android.internal.telephony.PhoneSubInfo;
import com.android.internal.telephony.TelephonyProperties;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;

import javax.sip.SipException;

/**
 * {@hide}
 */
public class SipPhone extends SipPhoneBase {
    private static final String LOG_TAG = "SipPhone";
    private static final boolean LOCAL_DEBUG = true;

    //private List<SipConnection> connections = new ArrayList<SipConnection>();

    // A call that is ringing or (call) waiting
    private SipCall ringingCall = new SipCall();
    private SipCall foregroundCall = new SipCall();
    private SipCall backgroundCall = new SipCall();

    private SipManager mSipManager;
    private SipProfile mProfile;

    SipPhone (Context context, PhoneNotifier notifier, SipProfile profile) {
        super(context, notifier);

        Log.v(LOG_TAG, "  +++++++++++++++++++++ new SipPhone: " + profile.getUriString());
        ringingCall = new SipCall();
        foregroundCall = new SipCall();
        backgroundCall = new SipCall();
        mProfile = profile;
        mSipManager = SipManager.getInstance(context);

        // FIXME: what's this for SIP?
        //Change the system property
        //SystemProperties.set(TelephonyProperties.CURRENT_ACTIVE_PHONE,
        //        new Integer(Phone.PHONE_TYPE_GSM).toString());
    }

    public String getPhoneName() {
        return mProfile.getProfileName();
    }

    public boolean canTake(Object incomingCall) {
        synchronized (SipPhone.class) {
            if (!(incomingCall instanceof SipAudioCall)) return false;
            if (ringingCall.getState().isAlive()) return false;

            // FIXME: is it true that we cannot take any incoming call if
            // both foreground and background are active
            if (foregroundCall.getState().isAlive()
                    && backgroundCall.getState().isAlive()) {
                return false;
            }

            SipAudioCall sipAudioCall = (SipAudioCall) incomingCall;
            Log.v(LOG_TAG, "  ++++++ taking call from: "
                    + sipAudioCall.getPeerProfile().getUriString());
            String localUri = sipAudioCall.getLocalProfile().getUriString();
            if (localUri.equals(mProfile.getUriString())) {
                boolean makeCallWait = foregroundCall.getState().isAlive();
                ringingCall.take(sipAudioCall, makeCallWait);
                return true;
            }
            return false;
        }
    }

    public void acceptCall() throws CallStateException {
        synchronized (SipPhone.class) {
            // FIXME if SWITCH fails, should retry with ANSWER
            // in case the active/holding call disappeared and this
            // is no longer call waiting

            if (ringingCall.getState() == Call.State.INCOMING) {
                Log.v(LOG_TAG, "acceptCall");
                // Always unmute when answering a new call
                setMute(false);
                // make it foreground
                foregroundCall.switchWith(ringingCall);
                foregroundCall.acceptCall();
            } else if (ringingCall.getState() == Call.State.WAITING) {
                // TODO: what does this mean?
                /*
                Log.v(LOG_TAG, "switchWaitingAndActive");
                setMute(false);
                switchWaitingOrHoldingAndActive();
                */
            } else {
                throw new CallStateException("phone not ringing");
            }
        }
    }

    public void rejectCall() throws CallStateException {
        synchronized (SipPhone.class) {
            if (ringingCall.getState().isRinging()) {
                Log.v(LOG_TAG, "rejectCall");
                ringingCall.rejectCall();
            } else {
                throw new CallStateException("phone not ringing");
            }
        }
    }

    public Connection dial(String dialString) throws CallStateException {
        synchronized (SipPhone.class) {
            return dialInternal(dialString);
        }
    }

    private Connection dialInternal(String dialString)
            throws CallStateException {
        // TODO: parse SIP URL?
        // Need to make sure dialString gets parsed properly
        //String newDialString = PhoneNumberUtils.stripSeparators(dialString);
        //return mCT.dial(newDialString);
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }
        if (foregroundCall.getState() == SipCall.State.ACTIVE) {
            switchHoldingAndActive();
            // TODO:
            //fakeHoldForegroundBeforeDial();
        }
        if (foregroundCall.getState() != SipCall.State.IDLE) {
            //we should have failed in !canDial() above before we get here
            throw new CallStateException("cannot dial in current state");
        }

        setMute(false);
        //cm.dial(pendingMO.address, clirMode, obtainCompleteMessage());
        try {
            Connection c = foregroundCall.dial(dialString);
            return c;
        } catch (SipException e) {
            Log.e(LOG_TAG, "dial()", e);
            throw new CallStateException("dial error: " + e);
        }
    }

    public void switchHoldingAndActive() throws CallStateException {
        synchronized (SipPhone.class) {
            foregroundCall.switchWith(backgroundCall);
            if (foregroundCall.getState().isAlive()) foregroundCall.unhold();
            if (backgroundCall.getState().isAlive()) backgroundCall.hold();
            // TODO: state changes will be notified in callback; will it
            // confuse PhoneApp during transition?
        }
    }

    private void switchWaitingOrHoldingAndActive() {
        // TODO
    }

    public boolean canConference() {
        //TODO
        //return mCT.canConference();
        return false;
    }

    public void conference() throws CallStateException {
        // TODO
    }

    public boolean canTransfer() {
        // TODO
        //return mCT.canTransfer();
        return false;
    }

    public void explicitCallTransfer() throws CallStateException {
        //mCT.explicitCallTransfer();
    }

    public void clearDisconnected() {
        ringingCall.clearDisconnected();
        foregroundCall.clearDisconnected();
        backgroundCall.clearDisconnected();

        updatePhoneState();
        notifyPreciseCallStateChanged();
    }

    public void sendDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                    "sendDtmf called with invalid character '" + c + "'");
        } else if (foregroundCall.getState().isAlive()) {
            foregroundCall.sendDtmf(c);
        }
    }

    public void startDtmf(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                "startDtmf called with invalid character '" + c + "'");
        } else {
            sendDtmf(c);
        }
    }

    public void stopDtmf() {
        // no op
    }

    public void sendBurstDtmf(String dtmfString) {
        Log.e(LOG_TAG, "[SipPhone] sendBurstDtmf() is a CDMA method");
    }

    public void getOutgoingCallerIdDisplay(Message onComplete) {
        // FIXME: what to reply?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setOutgoingCallerIdDisplay(int commandInterfaceCLIRMode,
                                           Message onComplete) {
        // FIXME: what's this for SIP?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void getCallWaiting(Message onComplete) {
        // FIXME: what to reply?
        AsyncResult.forMessage(onComplete, null, null);
        onComplete.sendToTarget();
    }

    public void setCallWaiting(boolean enable, Message onComplete) {
        // FIXME: what to reply?
        Log.e(LOG_TAG, "call waiting not supported");
    }

    public void setMute(boolean muted) {
        foregroundCall.setMute(muted);
    }

    public boolean getMute() {
        return foregroundCall.getMute();
    }

    public Call getForegroundCall() {
        return foregroundCall;
    }

    public Call getBackgroundCall() {
        return backgroundCall;
    }

    public Call getRingingCall() {
        return ringingCall;
    }

    public ServiceState getServiceState() {
        // FIXME: we may need to provide this when data connectivity is lost
        // or when server is down
        return super.getServiceState();
    }

    private class SipCall extends SipCallBase {
        SipAudioCall mSipAudioCall;

        void switchWith(SipCall that) {
            synchronized (SipPhone.class) {
                SipCall tmp = new SipCall();
                tmp.takeOver(this);
                this.takeOver(that);
                that.takeOver(tmp);
            }
        }

        private void takeOver(SipCall that) {
            connections = that.connections;
            state = that.state;
            mSipAudioCall = that.mSipAudioCall;
            for (Connection c : connections) {
                ((SipConnection) c).changeOwner(this);
            }
        }

        public Phone getPhone() {
            return SipPhone.this;
        }

        public List<Connection> getConnections() {
            synchronized (SipPhone.class) {
                // FIXME should return Collections.unmodifiableList();
                return connections;
            }
        }

        public Connection dial(String calleeSipUri) throws SipException {
            try {
                SipProfile callee =
                        new SipProfile.Builder(calleeSipUri).build();
                SipConnection c = new SipConnection(this, callee);
                connections.add(c);
                c.dial();
                mSipAudioCall = c.mSipAudioCall;
                setState(Call.State.DIALING);
                return c;
            } catch (ParseException e) {
                // TODO: notify someone
                throw new SipException("dial", e);
            }
        }

        // TODO: if this is the foreground call and a background call exists,
        // resume the background call
        public void hangup() throws CallStateException {
            Log.v(LOG_TAG, "hang up call: " + getState() + ": " + this
                    + " on phone " + getPhone());
            CallStateException excp = null;
            for (Connection c : connections) {
                try {
                    c.hangup();
                } catch (CallStateException e) {
                    excp = e;
                }
            }
            if (excp != null) throw excp;
            setState(State.DISCONNECTING);
        }

        public void take(SipAudioCall sipAudioCall, boolean makeCallWait) {
            mSipAudioCall = sipAudioCall;
            SipProfile callee = sipAudioCall.getPeerProfile();
            SipConnection c = new SipConnection(this, callee);
            connections.add(c);

            Call.State newState = makeCallWait ? State.WAITING : State.INCOMING;
            c.take(sipAudioCall, newState);

            setState(newState);
            notifyNewRingingConnectionP(c);
        }

        public void rejectCall() throws CallStateException {
            hangup();
        }

        public void acceptCall() throws CallStateException {
            try {
                mSipAudioCall.answerCall();
            } catch (SipException e) {
                throw new CallStateException("acceptCall(): " + e);
            }
        }

        public void hold() {
            if (mSipAudioCall == null) return;
            try {
                mSipAudioCall.holdCall();
                setState(State.HOLDING);
            } catch (SipException e) {
                // FIXME: ignore?
            }
        }

        public void unhold() {
            if (mSipAudioCall == null) return;
            try {
                mSipAudioCall.continueCall();
                setState(State.ACTIVE);
            } catch (SipException e) {
                // FIXME: ignore?
            }
        }

        public void setMute(boolean muted) {
            if (mSipAudioCall == null) return;
            if (mSipAudioCall.isMuted() != muted) {
                mSipAudioCall.toggleMute();
            }
        }

        public boolean getMute() {
            if (mSipAudioCall == null) return false;
            return mSipAudioCall.isMuted();
        }

        public void sendDtmf(char c) {
            if (mSipAudioCall != null) {
                mSipAudioCall.sendDtmf((int) (c - '0'));
            }
        }

        protected void clearDisconnected() {
            super.clearDisconnected();
            if (state == State.IDLE) mSipAudioCall = null;
        }

        protected void setState(State newState) {
            if (state != newState) {
                Log.v(LOG_TAG, "++******++ call state changed: " + state
                        + " --> " + newState + ": " + this + ": on phone "
                        + getPhone() + " " + connections.size());

                if (newState == Call.State.ALERTING) {
                    state = newState; // need in ALERTING to enable ringback
                    SipPhone.this.startRingbackTone();
                } else if (state == Call.State.ALERTING) {
                    SipPhone.this.stopRingbackTone();
                }
                state = newState;
                updatePhoneState();
                notifyPreciseCallStateChanged();

            }
        }

        private void onConnectionStateChanged(SipConnection conn) {
            // this can be called back when a conf call is formed
            if ((state != State.ACTIVE) && (state != State.WAITING)) {
                setState(conn.getState());
            }
        }

        private void onConnectionEnded(SipConnection conn) {
            // set state to DISCONNECTED only when all conns are disconnected
            if (state != State.DISCONNECTED) {
                boolean allConnectionsDisconnected = true;
                for (Connection c : connections) {
                    if (c.getState() != State.DISCONNECTED) {
                        allConnectionsDisconnected = false;
                        break;
                    }
                }
                if (allConnectionsDisconnected) setState(State.DISCONNECTED);
            }
            notifyDisconnectP(conn);
        }
    }

    private class SipConnection extends SipConnectionBase {
        private SipCall mOwner;
        private SipAudioCall mSipAudioCall;
        private Call.State mState = Call.State.IDLE;
        private SipProfile mPeer;
        private boolean mIncoming = false;

        private SipAudioCallAdapter mAdapter = new SipAudioCallAdapter() {
            protected void onCallEnded(DisconnectCause cause) {
                if (getDisconnectCause() != DisconnectCause.LOCAL) {
                    setDisconnectCause(cause);
                }
                synchronized (SipPhone.class) {
                    mState = Call.State.DISCONNECTED;
                    mOwner.onConnectionEnded(SipConnection.this);
                    Log.v(LOG_TAG, "-------- connection ended: "
                            + mPeer.getUriString() + ": "
                            + mSipAudioCall.getState() + ", cause: "
                            + getDisconnectCause() + ", on phone "
                            + getPhone());
                }
            }

            public void onChanged(SipAudioCall call) {
                synchronized (SipPhone.class) {
                    Call.State newState = getCallStateFrom(call);
                    if (mState == newState) return;
                    if (newState == Call.State.INCOMING) {
                        mState = mOwner.getState(); // INCOMING or WAITING
                    } else {
                        mState = newState;
                    }
                    mOwner.onConnectionStateChanged(SipConnection.this);
                    Log.v(LOG_TAG, "++******++ connection state changed: "
                            + mPeer.getUriString() + ": " + mState
                            + " on phone " + getPhone());
                }
            }
        };

        public SipConnection(SipCall owner, SipProfile callee) {
            super(callee.getSipDomain(), callee.getUriString());
            mOwner = owner;
            mPeer = callee;
        }

        public void take(SipAudioCall sipAudioCall, Call.State newState) {
            mState = newState;
            mSipAudioCall = sipAudioCall;
            sipAudioCall.setListener(mAdapter); // call back to set state
            mIncoming = true;
        }

        public void changeOwner(SipCall owner) {
            mOwner = owner;
        }

        @Override
        public Call.State getState() {
            return mState;
        }

        @Override
        public boolean isIncoming() {
            return mIncoming;
        }

        @Override
        public String getAddress() {
            return mPeer.getUriString();
        }

        public void dial() throws SipException {
            mState = Call.State.DIALING;
            mSipAudioCall = mSipManager.makeAudioCall(mContext, mProfile,
                    mPeer, null);
            mSipAudioCall.setRingbackToneEnabled(false);
            mSipAudioCall.setListener(mAdapter);
        }

        @Override
        public SipCall getCall() {
            return mOwner;
        }

        @Override
        protected Phone getPhone() {
            return mOwner.getPhone();
        }

        @Override
        public Object getUserData() {
            Object o = super.getUserData();
            if (o == null) {
                CallerInfo info = new CallerInfo();
                info.name = mPeer.getUserName();
                info.phoneNumber = getAddress();
                setUserData(info);
                o = info;
            }
            return o;
        }

        @Override
        public void hangup() throws CallStateException {
            Log.v(LOG_TAG, "hangup conn: " + mPeer.getUriString() + ": "
                    + ": on phone " + getPhone());
            try {
                mSipAudioCall.endCall();
                mState = Call.State.DISCONNECTING;
                setDisconnectCause(DisconnectCause.LOCAL);
            } catch (SipException e) {
                throw new CallStateException("hangup(): " + e);
            }
        }

        @Override
        public void separate() throws CallStateException {
            // TODO: what's this for SIP?
            /*
            if (!disconnected) {
                owner.separate(this);
            } else {
                throw new CallStateException ("disconnected");
            }
            */
        }

    }

    private static Call.State getCallStateFrom(SipAudioCall sipAudioCall) {
        SipSessionState sessionState = sipAudioCall.getState();
        switch (sessionState) {
            case READY_TO_CALL:            return Call.State.IDLE;
            case INCOMING_CALL:
            case INCOMING_CALL_ANSWERING:  return Call.State.INCOMING;
            case OUTGOING_CALL:            return Call.State.DIALING;
            case OUTGOING_CALL_RING_BACK:  return Call.State.ALERTING;
            case OUTGOING_CALL_CANCELING:  return Call.State.DISCONNECTING;
            case IN_CALL:                  return Call.State.ACTIVE;
            default:
                Log.w(LOG_TAG, "illegal connection state: " + sessionState);
                return Call.State.DISCONNECTED;
        }
    }

    private abstract class SipAudioCallAdapter extends SipAudioCall.Adapter {
        private SipException mError;

        protected abstract void onCallEnded(Connection.DisconnectCause cause);

        public void onCallEnded(SipAudioCall call) {
            onCallEnded(Connection.DisconnectCause.NORMAL);
        }

        public void onCallBusy(SipAudioCall call) {
            onCallEnded(Connection.DisconnectCause.BUSY);
        }

        public void onError(SipAudioCall call, String errorMessage) {
            mError = new SipException(errorMessage);
            // FIXME: specify error
            onCallEnded(Connection.DisconnectCause.ERROR_UNSPECIFIED);
        }

        public SipException getError() {
            return mError;
        }
    }
}
