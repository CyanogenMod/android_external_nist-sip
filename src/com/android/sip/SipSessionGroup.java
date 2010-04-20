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

package com.android.sip;

import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SessionDescription;
import android.net.sip.SipProfile;
import android.net.sip.SipSessionAdapter;
import android.net.sip.SipSessionState;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.text.ParseException;
import java.util.EventObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TooManyListenersException;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.DialogTerminatedEvent;
import javax.sip.IOExceptionEvent;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.RequestEvent;
import javax.sip.ResponseEvent;
import javax.sip.ServerTransaction;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipListener;
import javax.sip.SipProvider;
import javax.sip.SipStack;
import javax.sip.TimeoutEvent;
import javax.sip.Transaction;
import javax.sip.TransactionTerminatedEvent;
import javax.sip.address.Address;
import javax.sip.address.SipURI;
import javax.sip.header.CSeqHeader;
import javax.sip.header.ExpiresHeader;
import javax.sip.header.FromHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;
import javax.sip.message.Response;

/**
 * Manages {@link ISipSession}'s for a SIP account.
 */
class SipSessionGroup implements SipListener {
    private static final String TAG = SipSessionGroup.class.getSimpleName();
    private static final int EXPIRY_TIME = 3600;
    private static final int SHORT_EXPIRY_TIME = 10;
    private static final int MIN_EXPIRY_TIME = 60;

    private static final EventObject DEREGISTER = new EventObject("Deregister");
    private static final EventObject END_CALL = new EventObject("End call");
    private static final EventObject HOLD_CALL = new EventObject("Hold call");
    private static final EventObject CONTINUE_CALL
            = new EventObject("Continue call");

    private SipStack mSipStack;
    private SipHelper mSipHelper;
    private SipProfile mLocalProfile;

    // session that processes INVITE requests
    private SipSessionImpl mCallReceiverSession;
    private String mLocalIp;

    private AutoRegistrationProcess mAutoRegistration =
            new AutoRegistrationProcess();

    // call-id-to-SipSession map
    private Map<String, SipSessionImpl> mSessionMap =
            new HashMap<String, SipSessionImpl>();

    public SipSessionGroup(String localIp, SipProfile myself)
            throws SipException {
        mLocalIp = localIp;
        SipFactory sipFactory = SipFactory.getInstance();
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", getStackName());
        String outboundProxy = myself.getOutboundProxy();
        if (!TextUtils.isEmpty(outboundProxy)) {
            properties.setProperty("javax.sip.OUTBOUND_PROXY", outboundProxy);
        }
        SipStack stack = mSipStack = sipFactory.createSipStack(properties);

        SipProvider provider = stack.createSipProvider(
                stack.createListeningPoint(localIp, allocateLocalPort(),
                        ListeningPoint.UDP));
        try {
            provider.addSipListener(this);
        } catch (TooManyListenersException e) {
            // must never happen
            throw new SipException("SipSessionGroup constructor", e);
        }
        mSipHelper = new SipHelper(stack, provider);
        mLocalProfile = myself;
        stack.start();
    }

    private String getStackName() {
        return "stack" + System.currentTimeMillis();
    }

    public synchronized void close() {
        if (mSipStack != null) {
            mSipStack.stop();
            mSipStack = null;
        }
        mSessionMap.clear();
        mAutoRegistration.stop();
    }

    public synchronized boolean isClosed() {
        return (mSipStack == null);
    }

    public synchronized void onNetworkDisconnected() {
        for (SipSessionImpl session : mSessionMap.values()) {
            session.onNetworkDisconnected();
        }
        close();
    }

    public synchronized void openToReceiveCalls(ISipSessionListener listener)
            throws SipException {
        if (mCallReceiverSession == null) {
            mCallReceiverSession = new SipSessionCallReceiverImpl(listener);
        } else {
            mCallReceiverSession.setListener(listener);
        }
        mAutoRegistration.start(listener);
    }

    public ISipSession createSession(ISipSessionListener listener) {
        return new SipSessionImpl(listener);
    }

    private static int allocateLocalPort() throws SipException {
        try {
            DatagramSocket s = new DatagramSocket();
            int localPort = s.getLocalPort();
            s.close();
            return localPort;
        } catch (IOException e) {
            throw new SipException("allocateLocalPort()", e);
        }
    }

    private synchronized SipSessionImpl getSipSession(EventObject event) {
        String key = SipHelper.getCallId(event);
        Log.d(TAG, " sesssion key from event: " + key);
        Log.d(TAG, " active sessions:");
        for (String k : mSessionMap.keySet()) {
            Log.d(TAG, "   .....  '" + k + "': " + mSessionMap.get(k));
        }
        SipSessionImpl session = mSessionMap.get(key);
        return ((session != null) ? session : mCallReceiverSession);
    }

    private synchronized void addSipSession(SipSessionImpl newSession) {
        removeSipSession(newSession);
        String key = newSession.getCallId();
        Log.d(TAG, " +++++  add a session with key:  '" + key + "'");
        mSessionMap.put(key, newSession);
        for (String k : mSessionMap.keySet()) {
            Log.d(TAG, "   .....  " + k + ": " + mSessionMap.get(k));
        }
    }

    private synchronized void removeSipSession(SipSessionImpl session) {
        if (session == mCallReceiverSession) return;
        String key = session.getCallId();
        SipSessionImpl s = mSessionMap.remove(key);
        // sanity check
        if ((s != null) && (s != session)) {
            Log.w(TAG, "session " + session + " is not associated with key '"
                    + key + "'");
            mSessionMap.put(key, s);
            for (Map.Entry<String, SipSessionImpl> entry
                    : mSessionMap.entrySet()) {
                if (entry.getValue() == s) {
                    key = entry.getKey();
                    mSessionMap.remove(key);
                }
            }
        }
        Log.d(TAG, "   remove session " + session + " with key '" + key + "'");

        for (String k : mSessionMap.keySet()) {
            Log.d(TAG, "   .....  " + k + ": " + mSessionMap.get(k));
        }
    }

    public void processRequest(RequestEvent event) {
        process(event);
    }

    public void processResponse(ResponseEvent event) {
        process(event);
    }

    public void processIOException(IOExceptionEvent event) {
        process(event);
    }

    public void processTimeout(TimeoutEvent event) {
        process(event);
    }

    public void processTransactionTerminated(TransactionTerminatedEvent event) {
        process(event);
    }

    public void processDialogTerminated(DialogTerminatedEvent event) {
        process(event);
    }

    private void process(EventObject event) {
        SipSessionImpl session = getSipSession(event);
        try {
            if ((session == null) || !session.process(event)) {
                Log.d(TAG, "event not processed: " + event);
            } else {
                Log.d(TAG, " ~~~~~   new state: " + session.mState);
            }
        } catch (Throwable e) {
            Log.e(TAG, "event process error: " + event, e);
            session.onError(e);
        }
    }

    private class SipSessionCallReceiverImpl extends SipSessionImpl {
        public SipSessionCallReceiverImpl(ISipSessionListener listener) {
            super(listener);
        }

        public boolean process(EventObject evt) throws SipException {
            Log.d(TAG, " ~~~~~   " + this + ": " + mState + ": processing "
                    + log(evt));
            if (isRequestEvent(Request.INVITE, evt)) {
                RequestEvent event = (RequestEvent) evt;
                SipSessionImpl newSession = new SipSessionImpl(mListener);
                newSession.mServerTransaction = mSipHelper.sendRinging(event);
                newSession.mDialog = newSession.mServerTransaction.getDialog();
                newSession.mInviteReceived = event;
                newSession.mPeerProfile = createPeerProfile(event.getRequest());
                newSession.mState = SipSessionState.INCOMING_CALL;
                newSession.mPeerSessionDescription =
                        event.getRequest().getRawContent();
                addSipSession(newSession);
                newSession.onRinging();
                return true;
            } else {
                return false;
            }
        }
    }

    private class SipSessionImpl extends ISipSession.Stub {
        SipProfile mPeerProfile;
        ISipSessionListener mListener;
        SipSessionState mState = SipSessionState.READY_TO_CALL;
        RequestEvent mInviteReceived;
        Dialog mDialog;
        ServerTransaction mServerTransaction;
        ClientTransaction mClientTransaction;
        byte[] mPeerSessionDescription;

        public SipSessionImpl(ISipSessionListener listener) {
            mListener = listener;
        }

        private void reset() {
            removeSipSession(this);
            mPeerProfile = null;
            mState = SipSessionState.READY_TO_CALL;
            mInviteReceived = null;
            mDialog = null;
            mServerTransaction = null;
            mClientTransaction = null;
            mPeerSessionDescription = null;
        }

        public void onNetworkDisconnected() {
            onError(new SipException("Network disconnected"));
        }

        public String getLocalIp() {
            return mLocalIp;
        }

        public SipProfile getLocalProfile() {
            return mLocalProfile;
        }

        public SipProfile getPeerProfile() {
            return mPeerProfile;
        }

        public String getCallId() {
            return SipHelper.getCallId(getTransaction());
        }

        private Transaction getTransaction() {
            if (mClientTransaction != null) return mClientTransaction;
            if (mServerTransaction != null) return mServerTransaction;
            return null;
        }

        public String getState() {
            return mState.toString();
        }

        public void setListener(ISipSessionListener listener) {
            synchronized (SipSessionGroup.this) {
                mListener = listener;
            }
        }

        public void makeCall(SipProfile peerProfile,
                SessionDescription sessionDescription) {
            try {
                process(new MakeCallCommand(peerProfile, sessionDescription));
            } catch (SipException e) {
                onError(e);
            }
        }

        public void answerCall(SessionDescription sessionDescription) {
            try {
                process(new MakeCallCommand(mPeerProfile, sessionDescription));
            } catch (SipException e) {
                onError(e);
            }
        }

        public void endCall() {
            try {
                process(END_CALL);
            } catch (SipException e) {
                onError(e);
            }
        }

        // http://www.tech-invite.com/Ti-sip-service-1.html#fig5
        public void changeCall(SessionDescription sessionDescription) {
            try {
                process(new MakeCallCommand(mPeerProfile, sessionDescription));
            } catch (SipException e) {
                onError(e);
            }
        }

        public void register(int duration) {
            try {
                process(new RegisterCommand(duration));
            } catch (SipException e) {
                onRegistrationFailed(e);
            }
        }

        public void unregister() {
            try {
                process(DEREGISTER);
            } catch (SipException e) {
                onRegistrationFailed(e);
            }
        }

        private String generateTag() {
            // TODO: based on myself's profile
            return String.valueOf((long) (Math.random() * 1000000L));
        }

        public String toString() {
            try {
                String s = super.toString();
                return s.substring(s.indexOf("@")) + ":" + mState;
            } catch (Throwable e) {
                return super.toString();
            }
        }

        public boolean process(EventObject evt) throws SipException {
            synchronized (SipSessionGroup.this) {
                Log.d(TAG, " ~~~~~   " + this + ": " + mState + ": processing "
                        + log(evt));
                boolean processed;

                switch (mState) {
                case REGISTERING:
                case DEREGISTERING:
                    processed = registeringToReady(evt);
                    break;
                case READY_TO_CALL:
                    processed = readyForCall(evt);
                    break;
                case INCOMING_CALL:
                    processed = incomingCall(evt);
                    break;
                case INCOMING_CALL_ANSWERING:
                    processed = incomingCallToInCall(evt);
                    break;
                case OUTGOING_CALL:
                case OUTGOING_CALL_RING_BACK:
                    processed = outgoingCall(evt);
                    break;
                case OUTGOING_CALL_CANCELING:
                    processed = outgoingCallToReady(evt);
                    break;
                case IN_CALL:
                    processed = inCall(evt);
                    break;
                case IN_CALL_ANSWERING:
                    processed = inCallAnsweringToInCall(evt);
                    break;
                case IN_CALL_CHANGING:
                    processed = inCallChanging(evt);
                    break;
                case IN_CALL_CHANGING_CANCELING:
                    processed = inCallChangingToInCall(evt);
                    break;
                default:
                    processed = false;
                }
                return (processed || processExceptions(evt));
            }
        }

        private boolean processExceptions(EventObject evt) throws SipException {
            // process INVITE and CANCEL
            if (isRequestEvent(Request.INVITE, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.BUSY_HERE);
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt,
                        Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST);
                return true;
            } else if (evt instanceof TransactionTerminatedEvent) {
                if (evt instanceof TimeoutEvent) {
                    processTimeout((TimeoutEvent) evt);
                } else {
                    // TODO: any clean up?
                }
                return true;
            } else if (evt instanceof DialogTerminatedEvent) {
                processDialogTerminated((DialogTerminatedEvent) evt);
                return true;
            }
            return false;
        }

        private void processDialogTerminated(DialogTerminatedEvent event) {
            if (mDialog == event.getDialog()) {
                endCallNormally();
            } else {
                Log.d(TAG, "not the current dialog; current=" + mDialog
                        + ", terminated=" + event.getDialog());
            }
        }

        private void processTimeout(TimeoutEvent event) {
            Log.d(TAG, "processing Timeout..." + event);
            Transaction current = event.isServerTransaction()
                    ? mServerTransaction
                    : mClientTransaction;
            Transaction target = event.isServerTransaction()
                    ? event.getServerTransaction()
                    : event.getClientTransaction();

            if (current != target) {
                Log.d(TAG, "not the current transaction; current=" + current
                        + ", timed out=" + target);
                return;
            }
            switch (mState) {
            case REGISTERING:
            case DEREGISTERING:
                reset();
                onRegistrationTimeout();
                break;
            case INCOMING_CALL:
            case INCOMING_CALL_ANSWERING:
            case OUTGOING_CALL:
            case OUTGOING_CALL_RING_BACK:
            case OUTGOING_CALL_CANCELING:
            case IN_CALL_CHANGING:
                // rfc3261#section-14.1
                // if re-invite gets timed out, terminate the dialog
                endCallOnError(new SipException("timed out"));
                break;
            case IN_CALL_ANSWERING:
            case IN_CALL_CHANGING_CANCELING:
                mState = SipSessionState.IN_CALL;
                onError(new SipException("timed out"));
                break;
            default:
                // do nothing
                break;
            }
        }

        private int getExpiryTime(Response response) {
            int expires = EXPIRY_TIME;
            ExpiresHeader expiresHeader = (ExpiresHeader)
                    response.getHeader(ExpiresHeader.NAME);
            if (expiresHeader != null) expires = expiresHeader.getExpires();
            // TODO: check MIN_EXPIRES header
            return expires;
        }

        private boolean registeringToReady(EventObject evt)
                throws SipException {
            if (expectResponse(Request.REGISTER, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                case Response.OK:
                    SipSessionState state = mState;
                    reset();
                    onRegistrationDone((state == SipSessionState.REGISTERING)
                            ? getExpiryTime(((ResponseEvent) evt).getResponse())
                            : -1);
                    return true;
                case Response.UNAUTHORIZED:
                case Response.PROXY_AUTHENTICATION_REQUIRED:
                    mSipHelper.handleChallenge(
                            (ResponseEvent)evt, mLocalProfile);
                    return true;
                default:
                    if (statusCode >= 500) {
                        reset();
                        onRegistrationFailed(createCallbackException(response));
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean readyForCall(EventObject evt) throws SipException {
            // expect MakeCallCommand, RegisterCommand, DEREGISTER
            if (evt instanceof MakeCallCommand) {
                MakeCallCommand cmd = (MakeCallCommand) evt;
                mPeerProfile = cmd.getPeerProfile();
                SessionDescription sessionDescription =
                        cmd.getSessionDescription();
                mClientTransaction = mSipHelper.sendInvite(mLocalProfile,
                        mPeerProfile, sessionDescription, generateTag());
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                mState = SipSessionState.OUTGOING_CALL;
                onCalling();
                return true;
            } else if (evt instanceof RegisterCommand) {
                int duration = ((RegisterCommand) evt).getDuration();
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), duration);
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                mState = SipSessionState.REGISTERING;
                return true;
            } else if (DEREGISTER == evt) {
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), 0);
                mDialog = mClientTransaction.getDialog();
                addSipSession(this);
                mState = SipSessionState.DEREGISTERING;
                return true;
            }
            return false;
        }

        private boolean incomingCall(EventObject evt) throws SipException {
            // expect MakeCallCommand(answering) , END_CALL cmd , Cancel
            if (evt instanceof MakeCallCommand) {
                // answer call
                mSipHelper.sendInviteOk(mInviteReceived, mLocalProfile,
                        ((MakeCallCommand) evt).getSessionDescription(),
                        generateTag(), mServerTransaction);
                mState = SipSessionState.INCOMING_CALL_ANSWERING;
                return true;
            } else if (END_CALL == evt) {
                mSipHelper.sendInviteBusyHere(mInviteReceived,
                        mServerTransaction);
                endCallNormally();
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                RequestEvent event = (RequestEvent) evt;
                mSipHelper.sendResponse(event, Response.OK);
                mSipHelper.sendInviteRequestTerminated(
                        mInviteReceived.getRequest(), mServerTransaction);
                endCallNormally();
                return true;
            }
            return false;
        }

        private boolean incomingCallToInCall(EventObject evt)
                throws SipException {
            // expect ACK, CANCEL request
            if (isRequestEvent(Request.ACK, evt)) {
                establishCall();
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                RequestEvent event = (RequestEvent) evt;
                // TODO: what to do here? what happens when racing between
                // OK-to-invite from callee and Cancel from caller
                return true;
            }
            return false;
        }

        private boolean outgoingCall(EventObject evt) throws SipException {
            if (expectResponse(Request.INVITE, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                case Response.RINGING:
                    if (mState == SipSessionState.OUTGOING_CALL) {
                        mState = SipSessionState.OUTGOING_CALL_RING_BACK;
                        onRingingBack();
                    }
                    return true;
                case Response.OK:
                    mSipHelper.sendInviteAck(event, mDialog);
                    mPeerSessionDescription = response.getRawContent();
                    establishCall();
                    return true;
                case Response.PROXY_AUTHENTICATION_REQUIRED:
                    mClientTransaction = mSipHelper.handleChallenge(
                            (ResponseEvent)evt, mLocalProfile);
                    mDialog = mClientTransaction.getDialog();
                    addSipSession(this);
                    return true;
                default:
                    if (statusCode >= 400) {
                        // error: an ack is sent automatically by the stack
                        endCallOnError(createCallbackException(response));
                        return true;
                    } else if (statusCode >= 300) {
                        // TODO: handle 3xx (redirect)
                    } else {
                        return true;
                    }
                }
                return false;
            } else if (END_CALL == evt) {
                // RFC says that UA should not send out cancel when no
                // response comes back yet. We are cheating for not checking
                // response.
                mSipHelper.sendCancel(mClientTransaction);
                mState = SipSessionState.OUTGOING_CALL_CANCELING;
                return true;
            }
            return false;
        }

        private boolean outgoingCallToReady(EventObject evt)
                throws SipException {
            if (evt instanceof ResponseEvent) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();
                int statusCode = response.getStatusCode();
                if (expectResponse(Request.CANCEL, evt)) {
                    if (statusCode == Response.OK) {
                        // do nothing; wait for REQUEST_TERMINATED
                        return true;
                    }
                } else if (expectResponse(Request.INVITE, evt)) {
                    if (statusCode == Response.REQUEST_TERMINATED) {
                        endCallNormally();
                        return true;
                    }
                } else {
                    return false;
                }

                if (statusCode >= 400) {
                    endCallOnError(createCallbackException(response));
                    return true;
                }
            }
            return false;
        }

        private boolean inCall(EventObject evt) throws SipException {
            // expect END_CALL cmd, BYE request, hold call (MakeCallCommand)
            // OK retransmission is handled in SipStack
            if (END_CALL == evt) {
                // rfc3261#section-15.1.1
                mSipHelper.sendBye(mDialog);
                endCallNormally();
                return true;
            } else if (isRequestEvent(Request.INVITE, evt)) {
                // got Re-INVITE
                RequestEvent event = (RequestEvent) evt;
                mSipHelper.sendReInviteOk(event, mLocalProfile);
                mState = SipSessionState.IN_CALL_ANSWERING;
                mPeerSessionDescription = event.getRequest().getRawContent();
                onCallChanged(mPeerSessionDescription);
                return true;
            } else if (isRequestEvent(Request.BYE, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.OK);
                endCallNormally();
                return true;
            } else if (evt instanceof MakeCallCommand) {
                // to change call
                mClientTransaction = mSipHelper.sendReinvite(mDialog,
                        ((MakeCallCommand) evt).getSessionDescription());
                mState = SipSessionState.IN_CALL_CHANGING;
                return true;
            }
            return false;
        }

        private boolean inCallChanging(EventObject evt)
                throws SipException {
            if (expectResponse(Request.INVITE, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                case Response.OK:
                    mSipHelper.sendInviteAck(event, mDialog);
                    establishCall();
                    return true;
                case Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST:
                case Response.REQUEST_TIMEOUT:
                    // rfc3261#section-14.1: re-invite failed; terminate
                    // the dialog
                    endCallOnError(createCallbackException(response));
                    return true;
                case Response.REQUEST_PENDING:
                    // TODO:
                    // rfc3261#section-14.1; re-schedule invite
                    return true;
                default:
                    if (statusCode >= 400) {
                        // error: an ack is sent automatically by the stack
                        mState = SipSessionState.IN_CALL;
                        onError(createCallbackException(response));
                        return true;
                    } else if (statusCode >= 300) {
                        // TODO: handle 3xx (redirect)
                    } else {
                        return true;
                    }
                }
                return false;
            } else if (END_CALL == evt) {
                mSipHelper.sendCancel(mClientTransaction);
                mState = SipSessionState.IN_CALL_CHANGING_CANCELING;
                return true;
            }
            return false;
        }

        private boolean inCallChangingToInCall(EventObject evt)
                throws SipException {
            if (expectResponse(Response.OK, Request.CANCEL, evt)) {
                // do nothing; wait for REQUEST_TERMINATED
                return true;
            } else if (expectResponse(Response.OK, Request.INVITE, evt)) {
                inCallChanging(evt); // abort Cancel
                return true;
            } else if (expectResponse(Response.REQUEST_TERMINATED,
                    Request.INVITE, evt)) {
                establishCall();
                return true;
            }
            return false;
        }

        private boolean inCallAnsweringToInCall(EventObject evt) {
            // expect ACK
            if (isRequestEvent(Request.ACK, evt)) {
                establishCall();
                return true;
            }
            return false;
        }

        private Exception createCallbackException(Response response) {
            return new SipException(String.format("Response: %s (%d)",
                    response.getReasonPhrase(), response.getStatusCode()));
        }

        private void establishCall() {
            mState = SipSessionState.IN_CALL;
            onCallEstablished(mPeerSessionDescription);
        }

        private void endCallNormally() {
            reset();
            onCallEnded();
        }

        private void endCallOnError(Throwable throwable) {
            reset();
            onError(throwable);
        }

        private void onCalling() {
            if (mListener == null) return;
            try {
                mListener.onCalling(this);
            } catch (Throwable t) {
                Log.w(TAG, "onCalling(): " + t);
            }
        }

        private void onRinging() {
            if (mListener == null) return;
            try {
                mListener.onRinging(this, mPeerProfile,
                        mPeerSessionDescription);
            } catch (Throwable t) {
                Log.w(TAG, "onRinging(): " + t);
            }
        }

        private void onRingingBack() {
            if (mListener == null) return;
            try {
                mListener.onRingingBack(this);
            } catch (Throwable t) {
                Log.w(TAG, "onRingingBack(): " + t);
            }
        }

        private void onCallEstablished(byte[] sessionDescription) {
            if (mListener == null) return;
            try {
                mListener.onCallEstablished(this, sessionDescription);
            } catch (Throwable t) {
                Log.w(TAG, "onCallEstablished(): " + t);
            }
        }

        private void onCallEnded() {
            if (mListener == null) return;
            try {
                mListener.onCallEnded(this);
            } catch (Throwable t) {
                Log.w(TAG, "onCallEnded(): " + t);
            }
        }

        private void onCallBusy() {
            if (mListener == null) return;
            try {
                mListener.onCallBusy(this);
            } catch (Throwable t) {
                Log.w(TAG, "onCallBusy(): " + t);
            }
        }

        private void onCallChanged(byte[] sessionDescription) {
            if (mListener == null) return;
            try {
                mListener.onCallChanged(this, sessionDescription);
            } catch (Throwable t) {
                Log.w(TAG, "onCallChanged(): " + t);
            }
        }

        private void onError(Throwable exception) {
            if (mListener == null) return;
            try {
                mListener.onError(this,
                        exception.getClass().getName(), exception.getMessage());
            } catch (Throwable t) {
                Log.w(TAG, "onError(): " + t);
            }
        }

        private void onRegistrationDone(int duration) {
            if (mListener == null) return;
            try {
                mListener.onRegistrationDone(this, duration);
            } catch (Throwable t) {
                Log.w(TAG, "onRegistrationDone()", t);
            }
        }

        private void onRegistrationFailed(Throwable exception) {
            if (mListener == null) return;
            try {
                mListener.onRegistrationFailed(this,
                        exception.getClass().getName(), exception.getMessage());
            } catch (Throwable t) {
                Log.w(TAG, "onRegistrationFailed(): " + t);
            }
        }

        private void onRegistrationTimeout() {
            if (mListener == null) return;
            try {
                mListener.onRegistrationTimeout(this);
            } catch (Throwable t) {
                Log.w(TAG, "onRegistrationTimeout(): " + t);
            }
        }
    }

    /**
     * @return true if the event is a request event matching the specified
     *      method; false otherwise
     */
    private static boolean isRequestEvent(String method, EventObject event) {
        try {
            if (event instanceof RequestEvent) {
                RequestEvent requestEvent = (RequestEvent) event;
                return method.equals(requestEvent.getRequest().getMethod());
            }
        } catch (Throwable e) {
        }
        return false;
    }

    private static String getCseqMethod(Message message) {
        return ((CSeqHeader) message.getHeader(CSeqHeader.NAME)).getMethod();
    }

    /**
     * @return true if the event is a response event and the CSeqHeader method
     * match the given arguments; false otherwise
     */
    private static boolean expectResponse(
            String expectedMethod, EventObject evt) {
        if (evt instanceof ResponseEvent) {
            ResponseEvent event = (ResponseEvent) evt;
            Response response = event.getResponse();
            return expectedMethod.equalsIgnoreCase(getCseqMethod(response));
        }
        return false;
    }

    /**
     * @return true if the event is a response event and the response code and
     *      CSeqHeader method match the given arguments; false otherwise
     */
    private static boolean expectResponse(
            int responseCode, String expectedMethod, EventObject evt) {
        if (evt instanceof ResponseEvent) {
            ResponseEvent event = (ResponseEvent) evt;
            Response response = event.getResponse();
            if (response.getStatusCode() == responseCode) {
                return expectedMethod.equalsIgnoreCase(getCseqMethod(response));
            }
        }
        return false;
    }

    private static SipProfile createPeerProfile(Request request)
            throws SipException {
        try {
            FromHeader fromHeader =
                    (FromHeader) request.getHeader(FromHeader.NAME);
            Address address = fromHeader.getAddress();
            SipURI uri = (SipURI) address.getURI();
            return new SipProfile.Builder(uri.getUser(), uri.getHost())
                    .setPort(uri.getPort())
                    .setDisplayName(address.getDisplayName())
                    .build();
        } catch (InvalidArgumentException e) {
            throw new SipException("createPeerProfile()", e);
        } catch (ParseException e) {
            throw new SipException("createPeerProfile()", e);
        }
    }

    private static String log(EventObject evt) {
        if (evt instanceof RequestEvent) {
            return ((RequestEvent) evt).getRequest().toString();
        } else if (evt instanceof ResponseEvent) {
            return ((ResponseEvent) evt).getResponse().toString();
        } else {
            return evt.toString();
        }
    }

    private class RegisterCommand extends EventObject {
        private int mDuration;

        public RegisterCommand(int duration) {
            super(SipSessionGroup.this);
            mDuration = duration;
        }

        public int getDuration() {
            return mDuration;
        }
    }

    private class MakeCallCommand extends EventObject {
        private SessionDescription mSessionDescription;

        public MakeCallCommand(SipProfile peerProfile,
                SessionDescription sessionDescription) {
            super(peerProfile);
            mSessionDescription = sessionDescription;
        }

        public SipProfile getPeerProfile() {
            return (SipProfile) getSource();
        }

        public SessionDescription getSessionDescription() {
            return mSessionDescription;
        }
    }

    private class AutoRegistrationProcess extends SipSessionAdapter {
        private SipSessionImpl mSession;
        private ISipSessionListener mListener;
        private WakeupTimer mTimer;
        private int mBackoff = 1;

        private String getAction() {
            return toString();
        }

        public void start(ISipSessionListener listener) {
            mListener = listener;
            if (mSession == null) {
                Log.v(TAG, "start AutoRegistrationProcess...");
                mBackoff = 1;
                mSession = (SipSessionImpl) createSession(this);
                // start unregistration to clear up old registration at server
                // TODO: when rfc5626 is deployed, use reg-id and sip.instance
                // in registration to avoid adding duplicate entries to server
                mSession.unregister();
            }
        }

        public void stop() {
            if (mTimer != null) {
                mTimer.stop();
                mTimer = null;
            }
            mSession = null;
        }

        private void register() {
            Log.d(TAG, "  ~~~ registering");
            synchronized (SipSessionGroup.this) {
                if (!isClosed()) mSession.register(EXPIRY_TIME);
            }
        }

        private void scheduleNextRegistration(int duration) {
            if (duration > 0) {
                if (mTimer == null) {
                    mTimer = WakeupTimer.Factory.getInstance().createTimer();
                }
                Log.d(TAG, "Refresh registration " + duration + "s later.");
                mTimer.set(duration * 1000L, new Runnable() {
                            public void run() {
                                register();
                            }
                        });
            } else {
                Log.d(TAG, "Refresh registration right away");
                register();
            }
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
        public void onRegistrationDone(ISipSession session, int duration) {
            if (session != mSession) return;
            if (mListener != null) {
                try {
                    mListener.onRegistrationDone(session, duration);
                } catch (Throwable t) {
                    Log.w(TAG, "onRegistrationDone()", t);
                }
            }

            if (duration > 0) {
                // allow some overlap to avoid missing calls during renew
                duration -= MIN_EXPIRY_TIME;
                if (duration < MIN_EXPIRY_TIME) duration = MIN_EXPIRY_TIME;
            }
            scheduleNextRegistration(duration);
        }

        @Override
        public void onRegistrationFailed(ISipSession session, String className,
                String message) {
            if (session != mSession) return;
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
            if (session != mSession) return;
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
}
