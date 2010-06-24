/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.content.Context;
import android.net.sip.SipAudioCall;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Registrant;
import android.os.SystemClock;
import android.util.Config;
import android.util.Log;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;

import com.android.internal.telephony.*;

/**
 * {@hide}
 */
public class SipConnection extends Connection {
    //***** Event Constants
    private static final int EVENT_DTMF_DONE = 1;
    private static final int EVENT_PAUSE_DONE = 2;
    private static final int EVENT_NEXT_POST_DIAL = 3;
    private static final int EVENT_WAKE_LOCK_TIMEOUT = 4;

    //***** Constants
    private static final int PAUSE_DELAY_FIRST_MILLIS = 100;
    private static final int PAUSE_DELAY_MILLIS = 3 * 1000;
    private static final int WAKE_LOCK_TIMEOUT_MILLIS = 60*1000;

    private static final String LOG_TAG = "SIP_CONN";

    //***** Instance Variables

    SipCallTracker owner;
    SipCall parent;

    private SipAudioCall mSipAudioCall;

    final String address;     // MAY BE NULL!!!
    private String dialString;          // outgoing calls only
    private String postDialString;      // outgoing calls only
    private int nextPostDialChar;       // index into postDialString
    private boolean isIncoming;
    private boolean disconnected;

    int index;          // index in SipCallTracker.connections[], -1 if unassigned
                        // The Sip index is 1 + this

    /*
     * These time/timespan values are based on System.currentTimeMillis(),
     * i.e., "wall clock" time.
     */
    private long createTime;
    private long connectTime;
    private long disconnectTime;

    /*
     * These time/timespan values are based on SystemClock.elapsedRealTime(),
     * i.e., time since boot.  They are appropriate for comparison and
     * calculating deltas.
     */
    private long connectTimeReal;
    private long duration;
    private long holdingStartTime;  // The time when the Connection last transitioned
                            // into HOLDING

    private DisconnectCause mCause = DisconnectCause.NOT_DISCONNECTED;
    private PostDialState postDialState = PostDialState.NOT_STARTED;

    private Handler h;

    /** This is probably an MT call that we first saw in a CLCC response */
    SipConnection (Context context, DriverCall dc, SipCallTracker ct, int index) {
        owner = ct;

        address = dc.number;

        isIncoming = dc.isMT;
        createTime = System.currentTimeMillis();
        //numberPresentation = dc.numberPresentation;

        this.index = index;

        parent = parentFromDCState (dc.state);
        parent.attach(this, dc);
    }

    /** This is an MO call, created when dialing */
    SipConnection (Context context, String dialString, SipCallTracker ct, SipCall parent) {
        owner = ct;

        this.dialString = dialString;

        this.address = PhoneNumberUtils.extractNetworkPortionAlt(dialString);
        this.postDialString = PhoneNumberUtils.extractPostDialPortion(dialString);

        index = -1;

        isIncoming = false;
        createTime = System.currentTimeMillis();

        this.parent = parent;
        parent.attachFake(this, SipCall.State.DIALING);
    }

    public void dispose() {
    }

    static boolean equalsHandlesNulls (Object a, Object b) {
        return (a == null) ? (b == null) : a.equals (b);
    }

    /*package*/ boolean compareTo(DriverCall c) {
        // On mobile originated (MO) calls, the phone number may have changed
        // due to a SIM Toolkit call control modification.
        //
        // We assume we know when MO calls are created (since we created them)
        // and therefore don't need to compare the phone number anyway.
        if (! (isIncoming || c.isMT)) return true;

        // ... but we can compare phone numbers on MT calls, and we have
        // no control over when they begin, so we might as well

        String cAddress = PhoneNumberUtils.stringFromStringAndTOA(c.number, c.TOA);
        return isIncoming == c.isMT && equalsHandlesNulls(address, cAddress);
    }

    public String getAddress() {
        return address;
    }

    public SipCall getCall() {
        return parent;
    }

    public long getCreateTime() {
        return createTime;
    }

    public long getConnectTime() {
        return connectTime;
    }

    public long getDisconnectTime() {
        return disconnectTime;
    }

    public long getDurationMillis() {
        if (connectTimeReal == 0) {
            return 0;
        } else if (duration == 0) {
            return SystemClock.elapsedRealtime() - connectTimeReal;
        } else {
            return duration;
        }
    }

    public long getHoldDurationMillis() {
        if (getState() != SipCall.State.HOLDING) {
            // If not holding, return 0
            return 0;
        } else {
            return SystemClock.elapsedRealtime() - holdingStartTime;
        }
    }

    public DisconnectCause getDisconnectCause() {
        return mCause;
    }

    void setDisconnectCause(DisconnectCause cause) {
        mCause = cause;
    }

    public boolean isIncoming() {
        return isIncoming;
    }

    public SipCall.State getState() {
        if (disconnected) {
            return SipCall.State.DISCONNECTED;
        } else {
            return super.getState();
        }
    }

    public void hangup() throws CallStateException {
        if (!disconnected) {
            owner.hangup(this);
        } else {
            throw new CallStateException ("disconnected");
        }
    }

    public void separate() throws CallStateException {
        if (!disconnected) {
            owner.separate(this);
        } else {
            throw new CallStateException ("disconnected");
        }
    }

    public PostDialState getPostDialState() {
        return postDialState;
    }

    public void proceedAfterWaitChar() {
        if (postDialState != PostDialState.WAIT) {
            Log.w(LOG_TAG, "SipConnection.proceedAfterWaitChar(): Expected "
                + "getPostDialState() to be WAIT but was " + postDialState);
            return;
        }

        setPostDialState(PostDialState.STARTED);

        processNextPostDialChar();
    }

    public void proceedAfterWildChar(String str) {
        if (postDialState != PostDialState.WILD) {
            Log.w(LOG_TAG, "SipConnection.proceedAfterWaitChar(): Expected "
                + "getPostDialState() to be WILD but was " + postDialState);
            return;
        }

        setPostDialState(PostDialState.STARTED);

        if (false) {
            boolean playedTone = false;
            int len = (str != null ? str.length() : 0);

            for (int i=0; i<len; i++) {
                char c = str.charAt(i);
                Message msg = null;

                if (i == len-1) {
                    msg = h.obtainMessage(EVENT_DTMF_DONE);
                }

                if (PhoneNumberUtils.is12Key(c)) {
                    sendDtmf(c, msg);
                    playedTone = true;
                }
            }

            if (!playedTone) {
                processNextPostDialChar();
            }
        } else {
            // make a new postDialString, with the wild char replacement string
            // at the beginning, followed by the remaining postDialString.

            StringBuilder buf = new StringBuilder(str);
            buf.append(postDialString.substring(nextPostDialChar));
            postDialString = buf.toString();
            nextPostDialChar = 0;
            if (Phone.DEBUG_PHONE) {
                log("proceedAfterWildChar: new postDialString is " +
                        postDialString);
            }

            processNextPostDialChar();
        }
    }

    public void cancelPostDial() {
        setPostDialState(PostDialState.CANCELLED);
    }

    /**
     * Called when this Connection is being hung up locally (eg, user pressed "end")
     * Note that at this point, the hangup request has been dispatched to the radio
     * but no response has yet been received so update() has not yet been called
     */
    void onHangupLocal() {
        mCause = DisconnectCause.LOCAL;
    }

    DisconnectCause disconnectCauseFromCode(int causeCode) {
        /**
         * See 22.001 Annex F.4 for mapping of cause codes
         * to local tones
         */

        switch (causeCode) {
            case CallFailCause.USER_BUSY:
                return DisconnectCause.BUSY;

            case CallFailCause.NO_CIRCUIT_AVAIL:
            case CallFailCause.TEMPORARY_FAILURE:
            case CallFailCause.SWITCHING_CONGESTION:
            case CallFailCause.CHANNEL_NOT_AVAIL:
            case CallFailCause.QOS_NOT_AVAIL:
            case CallFailCause.BEARER_NOT_AVAIL:
                return DisconnectCause.CONGESTION;

            case CallFailCause.ACM_LIMIT_EXCEEDED:
                return DisconnectCause.LIMIT_EXCEEDED;

            case CallFailCause.CALL_BARRED:
                return DisconnectCause.CALL_BARRED;

            case CallFailCause.FDN_BLOCKED:
                return DisconnectCause.FDN_BLOCKED;

            case CallFailCause.ERROR_UNSPECIFIED:
            case CallFailCause.NORMAL_CLEARING:
            default:
                SipPhone phone = owner.phone;
                int serviceState = phone.getServiceState().getState();
                if (serviceState == ServiceState.STATE_POWER_OFF) {
                    return DisconnectCause.POWER_OFF;
                } else if (serviceState == ServiceState.STATE_OUT_OF_SERVICE
                        || serviceState == ServiceState.STATE_EMERGENCY_ONLY ) {
                    return DisconnectCause.OUT_OF_SERVICE;
                } else if (causeCode == CallFailCause.ERROR_UNSPECIFIED) {
                    return DisconnectCause.ERROR_UNSPECIFIED;
                } else if (causeCode == CallFailCause.NORMAL_CLEARING) {
                    return DisconnectCause.NORMAL;
                } else {
                    // If nothing else matches, report unknown call drop reason
                    // to app, not NORMAL call end.
                    return DisconnectCause.ERROR_UNSPECIFIED;
                }
        }
    }

    void onRemoteDisconnect(int causeCode) {
        onDisconnect(disconnectCauseFromCode(causeCode));
    }

    /** Called when the radio indicates the connection has been disconnected */
    void onDisconnect(DisconnectCause cause) {
        mCause = cause;

        if (!disconnected) {
            index = -1;

            disconnectTime = System.currentTimeMillis();
            duration = SystemClock.elapsedRealtime() - connectTimeReal;
            disconnected = true;

            if (Config.LOGD) Log.d(LOG_TAG,
                    "[SipConn] onDisconnect: cause=" + cause);

            owner.phone.notifyDisconnect(this);

            if (parent != null) {
                parent.connectionDisconnected(this);
            }
        }
    }

    // Returns true if state has changed, false if nothing changed
    /*
    boolean update (DriverCall dc) {
        SipCall newParent;
        boolean changed = false;
        boolean wasConnectingInOrOut = isConnectingInOrOut();
        boolean wasHolding = (getState() == SipCall.State.HOLDING);

        newParent = parentFromDCState(dc.state);

        if (!equalsHandlesNulls(address, dc.number)) {
            if (Phone.DEBUG_PHONE) log("update: phone # changed!");
            address = dc.number;
            changed = true;
        }

        if (newParent != parent) {
            if (parent != null) {
                parent.detach(this);
            }
            newParent.attach(this, dc);
            parent = newParent;
            changed = true;
        } else {
            boolean parentStateChange;
            parentStateChange = parent.update (this, dc);
            changed = changed || parentStateChange;
        }

        // Some state-transition events

        if (Phone.DEBUG_PHONE) log(
                "update: parent=" + parent +
                ", hasNewParent=" + (newParent != parent) +
                ", wasConnectingInOrOut=" + wasConnectingInOrOut +
                ", wasHolding=" + wasHolding +
                ", isConnectingInOrOut=" + isConnectingInOrOut() +
                ", changed=" + changed);


        if (wasConnectingInOrOut && !isConnectingInOrOut()) {
            onConnectedInOrOut();
        }

        if (changed && !wasHolding && (getState() == SipCall.State.HOLDING)) {
            // We've transitioned into HOLDING
            onStartedHolding();
        }

        return changed;
    }
    */

    /**
     * Called when this Connection is in the foregroundCall
     * when a dial is initiated.
     * We know we're ACTIVE, and we know we're going to end up
     * HOLDING in the backgroundCall
     */
    void fakeHoldBeforeDial() {
        if (parent != null) {
            parent.detach(this);
        }

        parent = owner.backgroundCall;
        parent.attachFake(this, SipCall.State.HOLDING);

        onStartedHolding();
    }

    int getSipIndex() throws CallStateException {
        if (index >= 0) {
            return index + 1;
        } else {
            throw new CallStateException ("Sip index not yet assigned");
        }
    }

    /**
     * An incoming or outgoing call has connected
     */
    void onConnectedInOrOut() {
        connectTime = System.currentTimeMillis();
        connectTimeReal = SystemClock.elapsedRealtime();
        duration = 0;

        // bug #678474: incoming call interpreted as missed call, even though
        // it sounds like the user has picked up the call.
        if (Phone.DEBUG_PHONE) {
            log("onConnectedInOrOut: connectTime=" + connectTime);
        }

        if (!isIncoming) {
            // outgoing calls only
            processNextPostDialChar();
        }
    }

    private void onStartedHolding() {
        holdingStartTime = SystemClock.elapsedRealtime();
    }

    private void sendDtmf(char c, Message result) {
        mSipAudioCall.sendDtmf(c, result);
    }

    /**
     * Performs the appropriate action for a post-dial char, but does not
     * notify application. returns false if the character is invalid and
     * should be ignored
     */
    private boolean processPostDialChar(char c) {
        if (PhoneNumberUtils.is12Key(c)) {
            sendDtmf(c, h.obtainMessage(EVENT_DTMF_DONE));
        } else if (c == PhoneNumberUtils.PAUSE) {
            // From TS 22.101:

            // "The first occurrence of the "DTMF Control Digits Separator"
            //  shall be used by the ME to distinguish between the addressing
            //  digits (i.e. the phone number) and the DTMF digits...."

            if (nextPostDialChar == 1) {
                // The first occurrence.
                // We don't need to pause here, but wait for just a bit anyway
                h.sendMessageDelayed(h.obtainMessage(EVENT_PAUSE_DONE),
                                            PAUSE_DELAY_FIRST_MILLIS);
            } else {
                // It continues...
                // "Upon subsequent occurrences of the separator, the UE shall
                //  pause again for 3 seconds (\u00B1 20 %) before sending any
                //  further DTMF digits."
                h.sendMessageDelayed(h.obtainMessage(EVENT_PAUSE_DONE),
                                            PAUSE_DELAY_MILLIS);
            }
        } else if (c == PhoneNumberUtils.WAIT) {
            setPostDialState(PostDialState.WAIT);
        } else if (c == PhoneNumberUtils.WILD) {
            setPostDialState(PostDialState.WILD);
        } else {
            return false;
        }

        return true;
    }

    public String getRemainingPostDialString() {
        if (postDialState == PostDialState.CANCELLED
            || postDialState == PostDialState.COMPLETE
            || postDialString == null
            || postDialString.length() <= nextPostDialChar) {
            return "";
        }

        return postDialString.substring(nextPostDialChar);
    }

    private void processNextPostDialChar() {
        char c = 0;
        Registrant postDialHandler;

        if (postDialState == PostDialState.CANCELLED) {
            //Log.v("Sip", "##### processNextPostDialChar: postDialState == CANCELLED, bail");
            return;
        }

        if (postDialString == null ||
                postDialString.length() <= nextPostDialChar) {
            setPostDialState(PostDialState.COMPLETE);

            // notifyMessage.arg1 is 0 on complete
            c = 0;
        } else {
            setPostDialState(PostDialState.STARTED);

            c = postDialString.charAt(nextPostDialChar++);

            boolean isValid = processPostDialChar(c);

            if (!isValid) {
                // Will call processNextPostDialChar
                h.obtainMessage(EVENT_NEXT_POST_DIAL).sendToTarget();
                // Don't notify application
                Log.e("Sip", "processNextPostDialChar: c=" + c + " isn't valid!");
                return;
            }
        }

        postDialHandler = owner.phone.mPostDialHandler;

        Message notifyMessage;

        if (postDialHandler != null
                && (notifyMessage = postDialHandler.messageForRegistrant()) != null) {
            // The AsyncResult.result is the Connection object
            PostDialState state = postDialState;
            AsyncResult ar = AsyncResult.forMessage(notifyMessage);
            ar.result = this;
            ar.userObj = state;

            // arg1 is the character that was/is being processed
            notifyMessage.arg1 = c;

            //Log.v("Sip", "##### processNextPostDialChar: send msg to postDialHandler, arg1=" + c);
            notifyMessage.sendToTarget();
        }
    }


    /** "connecting" means "has never been ACTIVE" for both incoming
     *  and outgoing calls
     */
    private boolean isConnectingInOrOut() {
        return parent == null || parent == owner.ringingCall
            || parent.state == SipCall.State.DIALING
            || parent.state == SipCall.State.ALERTING;
    }

    private SipCall parentFromDCState (DriverCall.State state) {
        switch (state) {
            case ACTIVE:
            case DIALING:
            case ALERTING:
                return owner.foregroundCall;
            //break;

            case HOLDING:
                return owner.backgroundCall;
            //break;

            case INCOMING:
            case WAITING:
                return owner.ringingCall;
            //break;

            default:
                throw new RuntimeException("illegal call state: " + state);
        }
    }

    /**
     * Set post dial state and acquire wake lock while switching to "started"
     * state, the wake lock will be released if state switches out of "started"
     * state or after WAKE_LOCK_TIMEOUT_MILLIS.
     * @param s new PostDialState
     */
    private void setPostDialState(PostDialState s) {
        if (postDialState != PostDialState.STARTED
                && s == PostDialState.STARTED) {
            Message msg = h.obtainMessage(EVENT_WAKE_LOCK_TIMEOUT);
            h.sendMessageDelayed(msg, WAKE_LOCK_TIMEOUT_MILLIS);
        } else if (postDialState == PostDialState.STARTED
                && s != PostDialState.STARTED) {
            h.removeMessages(EVENT_WAKE_LOCK_TIMEOUT);
        }
        postDialState = s;
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[SipConn] " + msg);
    }

    @Override
    public int getNumberPresentation() {
        // FIXME: what's this for SIP?
        return Connection.PRESENTATION_ALLOWED;
    }

    @Override
    public UUSInfo getUUSInfo() {
        // FIXME: what's this for SIP?
        return null;
    }

    private class MyHandler extends Handler {
        MyHandler(Looper l) {
            super(l);
        }

        public void handleMessage(Message msg) {

            switch (msg.what) {
                case EVENT_NEXT_POST_DIAL:
                case EVENT_DTMF_DONE:
                case EVENT_PAUSE_DONE:
                    processNextPostDialChar();
                    break;
                case EVENT_WAKE_LOCK_TIMEOUT:
                    //releaseWakeLock();
                    break;
            }
        }
    }
}
