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
import android.net.sip.SipManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.EventLog;
import android.util.Log;

import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.CallTracker;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.DriverCall;
import com.android.internal.telephony.EventLogTags;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyProperties;

import java.util.List;
import java.util.ArrayList;

/**
 * {@hide}
 */
public final class SipCallTracker extends CallTracker {
    static final int MAX_CONNECTIONS = 7;   // only 7 connections allowed in GSM
    static final int MAX_CONNECTIONS_PER_CALL = 5; // only 5 connections allowed per call

    private static final String LOG_TAG = "SIP_CT";
    private static final boolean DBG_POLL = false;

    SipConnection connections[] = new SipConnection[MAX_CONNECTIONS];

    SipCall ringingCall = new SipCall(this);
            // A call that is ringing or (call) waiting
    SipCall foregroundCall = new SipCall(this);
    SipCall backgroundCall = new SipCall(this);

    SipConnection pendingMO;
    boolean hangupPendingMO;

    SipPhone phone;

    boolean desiredMute = false;    // false = mute off

    Phone.State state = Phone.State.IDLE;

    SipCallTracker (SipPhone phone) {
        this.phone = phone;
        cm = phone.mCM;
    }

    public void dispose() {
        for(SipConnection c : connections) {
            try {
                if(c != null) hangup(c);
            } catch (CallStateException ex) {
                Log.e(LOG_TAG, "unexpected error on hangup during dispose");
            }
        }

        try {
            if(pendingMO != null) hangup(pendingMO);
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "unexpected error on hangup during dispose");
        }

        clearDisconnected();
    }

    protected void finalize() {
        Log.d(LOG_TAG, "SipCallTracker finalized");
    }

    private void fakeHoldForegroundBeforeDial() {
        List<Connection> connCopy;

        // We need to make a copy here, since fakeHoldBeforeDial()
        // modifies the lists, and we don't want to reverse the order
        connCopy = (List<Connection>) foregroundCall.connections.clone();

        for (int i = 0, s = connCopy.size() ; i < s ; i++) {
            SipConnection conn = (SipConnection)connCopy.get(i);

            conn.fakeHoldBeforeDial();
        }
    }

    /**
     * clirMode is one of the CLIR_ constants
     */
    Connection dial(String dialString, int clirMode) throws CallStateException {

        // note that this triggers call state changed notif
        clearDisconnected();

        if (!canDial()) {
            throw new CallStateException("cannot dial in current state");
        }

        // The new call must be assigned to the foreground call.
        // That call must be idle, so place anything that's
        // there on hold
        if (foregroundCall.getState() == SipCall.State.ACTIVE) {
            // this will probably be done by the radio anyway
            // but the dial might fail before this happens
            // and we need to make sure the foreground call is clear
            // for the newly dialed connection
            switchWaitingOrHoldingAndActive();

            // Fake local state so that
            // a) foregroundCall is empty for the newly dialed connection
            // b) hasNonHangupStateChanged remains false in the
            // next poll, so that we don't clear a failed dialing call
            fakeHoldForegroundBeforeDial();
        }

        if (foregroundCall.getState() != SipCall.State.IDLE) {
            //we should have failed in !canDial() above before we get here
            throw new CallStateException("cannot dial in current state");
        }

        pendingMO = new SipConnection(phone.getContext(), dialString, this, foregroundCall);
        hangupPendingMO = false;

        if (pendingMO.address == null || pendingMO.address.length() == 0
            || pendingMO.address.indexOf(PhoneNumberUtils.WILD) >= 0
        ) {
            // Phone number is invalid
            pendingMO.setDisconnectCause(
                    Connection.DisconnectCause.INVALID_NUMBER);

            // handlePollCalls() will notice this call not present
            // and will mark it as dropped.
            pollCallsWhenSafe();
        } else {
            // Always unmute when initiating a new call
            setMute(false);

            cm.dial(pendingMO.address, clirMode, obtainCompleteMessage());
        }

        updatePhoneState();
        phone.notifyPreciseCallStateChanged();

        return pendingMO;
    }


    Connection dial(String dialString) throws CallStateException {
        return dial(dialString, CommandsInterface.CLIR_DEFAULT);
    }

    void acceptCall() throws CallStateException {
        // FIXME if SWITCH fails, should retry with ANSWER
        // in case the active/holding call disappeared and this
        // is no longer call waiting

        if (ringingCall.getState() == SipCall.State.INCOMING) {
            Log.i("phone", "acceptCall: incoming...");
            // Always unmute when answering a new call
            setMute(false);
            cm.acceptCall(obtainCompleteMessage());
        } else if (ringingCall.getState() == SipCall.State.WAITING) {
            setMute(false);
            switchWaitingOrHoldingAndActive();
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void rejectCall() throws CallStateException {
        // AT+CHLD=0 means "release held or UDUB"
        // so if the phone isn't ringing, this could hang up held
        if (ringingCall.getState().isRinging()) {
            cm.rejectCall(obtainCompleteMessage());
        } else {
            throw new CallStateException("phone not ringing");
        }
    }

    void switchWaitingOrHoldingAndActive() throws CallStateException {
        // Should we bother with this check?
        if (ringingCall.getState() == SipCall.State.INCOMING) {
            throw new CallStateException("cannot be in the incoming state");
        } else {
            cm.switchWaitingOrHoldingAndActive(
                    obtainCompleteMessage(EVENT_SWITCH_RESULT));
        }
    }

    void conference() throws CallStateException {
        cm.conference(obtainCompleteMessage(EVENT_CONFERENCE_RESULT));
    }

    void explicitCallTransfer() throws CallStateException {
        cm.explicitCallTransfer(obtainCompleteMessage(EVENT_ECT_RESULT));
    }

    void clearDisconnected() {
        internalClearDisconnected();

        updatePhoneState();
        phone.notifyPreciseCallStateChanged();
    }

    boolean canConference() {
        return foregroundCall.getState() == SipCall.State.ACTIVE
                && backgroundCall.getState() == SipCall.State.HOLDING
                && !backgroundCall.isFull()
                && !foregroundCall.isFull();
    }

    boolean canDial() {
        boolean ret;
        int serviceState = phone.getServiceState().getState();
        String disableCall = SystemProperties.get(
                TelephonyProperties.PROPERTY_DISABLE_CALL, "false");

        ret = (serviceState != ServiceState.STATE_POWER_OFF)
                && pendingMO == null
                && !ringingCall.isRinging()
                && !disableCall.equals("true")
                && (!foregroundCall.getState().isAlive()
                    || !backgroundCall.getState().isAlive());

        return ret;
    }

    boolean canTransfer() {
        return foregroundCall.getState() == SipCall.State.ACTIVE
                && backgroundCall.getState() == SipCall.State.HOLDING;
    }

    //***** Private Instance Methods

    private void internalClearDisconnected() {
        ringingCall.clearDisconnected();
        foregroundCall.clearDisconnected();
        backgroundCall.clearDisconnected();
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message obtainCompleteMessage() {
        return obtainCompleteMessage(EVENT_OPERATION_COMPLETE);
    }

    /**
     * Obtain a message to use for signalling "invoke getCurrentCalls() when
     * this operation and all other pending operations are complete
     */
    private Message obtainCompleteMessage(int what) {
        pendingOperations++;
        lastRelevantPoll = null;
        needsPoll = true;

        if (DBG_POLL) log("obtainCompleteMessage: pendingOperations=" +
                pendingOperations + ", needsPoll=" + needsPoll);

        return obtainMessage(what);
    }

    private void updatePhoneState() {
        Phone.State oldState = state;

        if (ringingCall.isRinging()) {
            state = Phone.State.RINGING;
        } else if (pendingMO != null ||
                !(foregroundCall.isIdle() && backgroundCall.isIdle())) {
            state = Phone.State.OFFHOOK;
        } else {
            state = Phone.State.IDLE;
        }

        if (state != oldState) {
            phone.notifyPhoneStateChanged();
        }
    }

    protected void handlePollCalls(AsyncResult ar) {
    }

    private void handleRadioNotAvailable() {
        // handlePollCalls will clear out its
        // call list when it gets the CommandException
        // error result from this
        pollCallsWhenSafe();
    }

    private void dumpState() {
        List l;

        Log.i(LOG_TAG,"Phone State:" + state);

        Log.i(LOG_TAG,"Ringing call: " + ringingCall.toString());

        l = ringingCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Log.i(LOG_TAG,l.get(i).toString());
        }

        Log.i(LOG_TAG,"Foreground call: " + foregroundCall.toString());

        l = foregroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Log.i(LOG_TAG,l.get(i).toString());
        }

        Log.i(LOG_TAG,"Background call: " + backgroundCall.toString());

        l = backgroundCall.getConnections();
        for (int i = 0, s = l.size(); i < s; i++) {
            Log.i(LOG_TAG,l.get(i).toString());
        }

    }

    //***** Called from SipConnection

    /*package*/ void hangup (SipConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException ("SipConnection " + conn
                                    + "does not belong to SipCallTracker " + this);
        }

        if (conn == pendingMO) {
            // We're hanging up an outgoing call that doesn't have it's
            // Sip index assigned yet

            if (Phone.DEBUG_PHONE) log("hangup: set hangupPendingMO to true");
            hangupPendingMO = true;
        } else {
            try {
                cm.hangupConnection (conn.getSipIndex(), obtainCompleteMessage());
            } catch (CallStateException ex) {
                // Ignore "connection not found"
                // Call may have hung up already
                Log.w(LOG_TAG,"SipCallTracker WARN: hangup() on absent connection "
                                + conn);
            }
        }

        conn.onHangupLocal();
    }

    /*package*/ void separate (SipConnection conn) throws CallStateException {
        if (conn.owner != this) {
            throw new CallStateException ("SipConnection " + conn
                                    + "does not belong to SipCallTracker " + this);
        }
        try {
            cm.separateConnection (conn.getSipIndex(),
                obtainCompleteMessage(EVENT_SEPARATE_RESULT));
        } catch (CallStateException ex) {
            // Ignore "connection not found"
            // Call may have hung up already
            Log.w(LOG_TAG,"SipCallTracker WARN: separate() on absent connection "
                          + conn);
        }
    }

    //***** Called from SipPhone

    /*package*/ void setMute(boolean mute) {
        desiredMute = mute;
        cm.setMute(desiredMute, null);
    }

    /*package*/ boolean getMute() {
        return desiredMute;
    }


    //***** Called from SipCall

    /* package */ void hangup (SipCall call) throws CallStateException {
        if (call.getConnections().size() == 0) {
            throw new CallStateException("no connections in call");
        }

        if (call == ringingCall) {
            if (Phone.DEBUG_PHONE) log("(ringing) hangup waiting or background");
            cm.hangupWaitingOrBackground(obtainCompleteMessage());
        } else if (call == foregroundCall) {
            if (call.isDialingOrAlerting()) {
                if (Phone.DEBUG_PHONE) {
                    log("(foregnd) hangup dialing or alerting...");
                }
                hangup((SipConnection)(call.getConnections().get(0)));
            } else {
                hangupForegroundResumeBackground();
            }
        } else if (call == backgroundCall) {
            if (ringingCall.isRinging()) {
                if (Phone.DEBUG_PHONE) {
                    log("hangup all conns in background call");
                }
                hangupAllConnections(call);
            } else {
                hangupWaitingOrBackground();
            }
        } else {
            throw new RuntimeException ("SipCall " + call +
                    "does not belong to SipCallTracker " + this);
        }

        call.onHangupLocal();
        phone.notifyPreciseCallStateChanged();
    }

    /* package */
    void hangupWaitingOrBackground() {
        if (Phone.DEBUG_PHONE) log("hangupWaitingOrBackground");
        cm.hangupWaitingOrBackground(obtainCompleteMessage());
    }

    /* package */
    void hangupForegroundResumeBackground() {
        if (Phone.DEBUG_PHONE) log("hangupForegroundResumeBackground");
        cm.hangupForegroundResumeBackground(obtainCompleteMessage());
    }

    void hangupAllConnections(SipCall call) throws CallStateException{
        try {
            int count = call.connections.size();
            for (int i = 0; i < count; i++) {
                SipConnection cn = (SipConnection)call.connections.get(i);
                cm.hangupConnection(cn.getSipIndex(), obtainCompleteMessage());
            }
        } catch (CallStateException ex) {
            Log.e(LOG_TAG, "hangupConnectionByIndex caught " + ex);
        }
    }

    /* package */
    SipConnection getConnectionByIndex(SipCall call, int index)
            throws CallStateException {
        int count = call.connections.size();
        for (int i = 0; i < count; i++) {
            SipConnection cn = (SipConnection)call.connections.get(i);
            if (cn.getSipIndex() == index) {
                return cn;
            }
        }

        return null;
    }

    private Phone.SuppService getFailedService(int what) {
        switch (what) {
            case EVENT_SWITCH_RESULT:
                return Phone.SuppService.SWITCH;
            case EVENT_CONFERENCE_RESULT:
                return Phone.SuppService.CONFERENCE;
            case EVENT_SEPARATE_RESULT:
                return Phone.SuppService.SEPARATE;
            case EVENT_ECT_RESULT:
                return Phone.SuppService.TRANSFER;
        }
        return Phone.SuppService.UNKNOWN;
    }

    //****** Overridden from Handler

    public void handleMessage(Message msg) {
        AsyncResult ar;

        switch (msg.what) {
            case EVENT_POLL_CALLS_RESULT:
                ar = (AsyncResult)msg.obj;
            break;

            case EVENT_OPERATION_COMPLETE:
                ar = (AsyncResult)msg.obj;
            break;

            case EVENT_SWITCH_RESULT:
            case EVENT_CONFERENCE_RESULT:
            case EVENT_SEPARATE_RESULT:
            case EVENT_ECT_RESULT:
                ar = (AsyncResult)msg.obj;
            break;
        }
    }

    protected void log(String msg) {
        Log.d(LOG_TAG, "[SipCallTracker] " + msg);
    }
}
