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

import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.text.ParseException;
import java.util.EventObject;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;
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
 * Manages SipSession's for a SIP account.
 */
class SipSessionGroup implements SipListener {
    private static final String TAG = SipSessionGroup.class.getSimpleName();
    private static final int EXPIRY_TIME = 3600;

    private static final EventObject REGISTER = new EventObject("Register");
    private static final EventObject DEREGISTER = new EventObject("Deregister");
    private static final EventObject END_CALL = new EventObject("End call");
    private static final EventObject HOLD_CALL = new EventObject("Hold call");
    private static final EventObject CONTINUE_CALL
            = new EventObject("Continue call");

    private static final String STACK_NAME = "A SIP STACK";

    private SipStack mSipStack;
    private SipHelper mSipHelper;
    private SipProfile mLocalProfile;

    // default session that processes all the un-attended messages received
    // from the UDP port
    private SipSessionImpl mDefaultSession;

    // call-id-to-SipSession map
    private Map<String, SipSessionImpl> mSessionMap =
            new HashMap<String, SipSessionImpl>();

    SipSessionGroup(String localIp, SipProfile myself,
            SipSessionListener listener) throws SipException {
        SipFactory sipFactory = SipFactory.getInstance();
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", STACK_NAME);
        String outboundProxy = myself.getOutboundProxy();
        if (!TextUtils.isEmpty(outboundProxy)) {
            properties.setProperty("javax.sip.OUTBOUND_PROXY", outboundProxy);
        }
        SipStack stack = mSipStack = sipFactory.createSipStack(properties);
        stack.start();

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
        mDefaultSession = new SipSessionImpl(listener);
    }

    public synchronized void close() {
        if (mSipStack != null) {
            mSipStack.stop();
            mSipStack = null;
        }
    }

    SipSession getDefaultSession() {
        return mDefaultSession;
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
        String[] keys = mSipHelper.getPossibleSessionKeys(event);
        Log.v(TAG, " possible keys " + keys.length + " for evt: " + event);
        for (String key : keys) Log.v(TAG, "    '" + key + "'");
        Log.v(TAG, " active sessions:");
        for (String k : mSessionMap.keySet()) {
            Log.v(TAG, "   -----  '" + k + "': " + mSessionMap.get(k));
        }
        for (String uri : mSipHelper.getPossibleSessionKeys(event)) {
            SipSessionImpl callSession = mSessionMap.get(uri);
            // return first match
            if (callSession != null) return callSession;
        }
        return mDefaultSession;
    }

    private synchronized void addSipSession(SipSessionImpl newSession) {
        String key = mSipHelper.getSessionKey(newSession);
        Log.v(TAG, " +++++  add a session with key:  '" + key + "'");
        mSessionMap.put(key, newSession);
        for (String k : mSessionMap.keySet()) {
            Log.v(TAG, "   .....  " + k + ": " + mSessionMap.get(k));
        }
    }

    private synchronized void removeSipSession(SipSessionImpl session) {
        String key = mSipHelper.getSessionKey(session);
        SipSessionImpl s = mSessionMap.remove(key);
        // sanity check
        if ((s != null) && (s != session)) {
            Log.w(TAG, "session " + session + " is not associated with key '"
                    + key + "'");
            mSessionMap.put(key, s);
        } else {
            Log.v(TAG, "   remove session " + session + " with key '" + key
                    + "'");
        }
        for (String k : mSessionMap.keySet()) {
            Log.v(TAG, "   .....  " + k + ": " + mSessionMap.get(k));
        }
    }

    public void processRequest(RequestEvent event) {
        process(event);
    }

    public void processResponse(ResponseEvent event) {
        process(event);
    }

    public void processIOException(IOExceptionEvent event) {
        // TODO: find proper listener to pass the exception
        process(event);
    }

    public void processTimeout(TimeoutEvent event) {
        // TODO: find proper listener to pass the exception
        process(event);
    }

    public void processTransactionTerminated(TransactionTerminatedEvent event) {
        // TODO: clean up if session is in in-transaction states
        //process(event);
    }

    public void processDialogTerminated(DialogTerminatedEvent event) {
        process(event);
    }

    private void process(EventObject event) {
        SipSessionImpl session = getSipSession(event);
        if (session == null) {
            Log.w(TAG, "event not processed: " + event);
            return;
        }
        try {
            session.process(event);
        } catch (Throwable e) {
            Log.e(TAG, "event process error: " + event, e);
            session.mListener.onError(session, e);
        }
    }

    private class SipSessionImpl implements SipSession {
        private SipProfile mPeerProfile;
        private SipSessionListener mListener;
        private SipSessionState mState = SipSessionState.READY_FOR_CALL;
        private RequestEvent mInviteReceived;
        private Dialog mDialog;
        private ServerTransaction mServerTransaction;
        private ClientTransaction mClientTransaction;
        private byte[] mPeerSessionDescription;

        SipSessionImpl(SipSessionListener listener) {
            mListener = listener;
        }

        private void reset() {
            mPeerProfile = null;
            mState = SipSessionState.READY_FOR_CALL;
            mInviteReceived = null;
            mDialog = null;
            mServerTransaction = null;
            mClientTransaction = null;
            mPeerSessionDescription = null;
        }

        public SipProfile getLocalProfile() {
            return mLocalProfile;
        }

        public synchronized SipProfile getPeerProfile() {
            return mPeerProfile;
        }

        public synchronized Dialog getDialog() {
            return mDialog;
        }

        public synchronized SipSessionState getState() {
            return mState;
        }

        public void makeCall(SipProfile peerProfile,
                SessionDescription sessionDescription) throws SipException {
            process(new MakeCallCommand(peerProfile, sessionDescription));
        }

        public void answerCall(SessionDescription sessionDescription)
                throws SipException {
            process(new MakeCallCommand(mPeerProfile, sessionDescription));
        }

        public void endCall() throws SipException {
            process(END_CALL);
        }

        // http://www.tech-invite.com/Ti-sip-service-1.html#fig5
        public void changeCall(SessionDescription sessionDescription)
                throws SipException {
            process(new MakeCallCommand(mPeerProfile, sessionDescription));
        }

        public void register() throws SipException {
            process(REGISTER);
        }

        public void deRegister() throws SipException {
            process(DEREGISTER);
        }

        private void scheduleNextRegistration(
                ExpiresHeader expiresHeader) {
            int expires = EXPIRY_TIME;
            if (expiresHeader != null) expires = expiresHeader.getExpires();
            Log.v(TAG, "Refresh registration " + expires + "s later.");
            new Timer().schedule(new TimerTask() {
                    public void run() {
                        try {
                            process(REGISTER);
                        } catch (SipException e) {
                            Log.e(TAG, "", e);
                        }
                    }}, expires * 1000L);
        }

        private String generateTag() {
            // TODO: based on myself's profile
            return String.valueOf((long) (Math.random() * 1000000L));
        }

        private String log(EventObject evt) {
            if (evt instanceof RequestEvent) {
                return ((RequestEvent) evt).getRequest().toString();
            } else if (evt instanceof ResponseEvent) {
                return ((ResponseEvent) evt).getResponse().toString();
            } else {
                return evt.toString();
            }
        }

        public String toString() {
            try {
                String s = super.toString();
                return s.substring(s.indexOf("@")) + ":" + mState;
            } catch (Throwable e) {
                return super.toString();
            }
        }

        synchronized void process(EventObject evt) throws SipException {
            Log.v(TAG, " ~~~~~   " + this + ": " + mState + ": processing "
                    + log(evt));
            boolean processed;

            switch (mState) {
            case REGISTERING:
            case DEREGISTERING:
                processed = registeringToReady(evt);
                break;
            case READY_FOR_CALL:
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
            if (!processed && !processExceptions(evt)) {
                Log.w(TAG, "event not processed: " + evt);
            } else {
                Log.v(TAG, " ~~~~~   new state: " + mState);
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
                endCall(isInCall());
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
            case INCOMING_CALL:
            case INCOMING_CALL_ANSWERING:
            case OUTGOING_CALL:
            case OUTGOING_CALL_RING_BACK:
            case OUTGOING_CALL_CANCELING:
            case IN_CALL_CHANGING:
                // rfc3261#section-14.1
                // if re-invite gets timed out, terminate the dialog
                endCallOnError((mState == SipSessionState.IN_CALL_CHANGING),
                        new SipException("timed out"));
                break;
            case IN_CALL_ANSWERING:
            case IN_CALL_CHANGING_CANCELING:
                mState = SipSessionState.IN_CALL;
                mListener.onError(this, new SipException("timed out"));
                break;
            default:
                // do nothing
                break;
            }
        }

        private boolean registeringToReady(EventObject evt)
                throws SipException {
            if (expectResponse(Request.REGISTER, evt)) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                case Response.OK:
                    if (mState == SipSessionState.REGISTERING) {
                        scheduleNextRegistration((ExpiresHeader)
                                ((ResponseEvent) evt).getResponse().getHeader(
                                        ExpiresHeader.NAME));
                    }
                    reset();
                    mListener.onRegistrationDone(this);
                    return true;
                case Response.UNAUTHORIZED:
                case Response.PROXY_AUTHENTICATION_REQUIRED:
                    mSipHelper.handleChallenge((ResponseEvent)evt, mLocalProfile);
                    return true;
                default:
                    if (statusCode >= 500) {
                        reset();
                        mListener.onRegistrationFailed(this,
                                createCallbackException(response));
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean readyForCall(EventObject evt) throws SipException {
            // expect MakeCallCommand, Invite
            if (evt instanceof MakeCallCommand) {
                MakeCallCommand cmd = (MakeCallCommand) evt;
                mPeerProfile = cmd.getPeerProfile();
                SessionDescription sessionDescription =
                        cmd.getSessionDescription();
                mClientTransaction = mSipHelper.sendInvite(mLocalProfile,
                        mPeerProfile, sessionDescription, generateTag());
                mDialog = mClientTransaction.getDialog();
                mState = SipSessionState.OUTGOING_CALL;
                mListener.onCalling(SipSessionImpl.this);
                return true;
            } else if (isRequestEvent(Request.INVITE, evt)) {
                RequestEvent event = (RequestEvent) evt;
                mServerTransaction = mSipHelper.sendRinging(event);
                mDialog = mServerTransaction.getDialog();
                mInviteReceived = event;
                mPeerProfile = createPeerProfile(event.getRequest());
                mState = SipSessionState.INCOMING_CALL;
                mPeerSessionDescription = event.getRequest().getRawContent();
                mListener.onRinging(SipSessionImpl.this, mPeerProfile,
                        mPeerSessionDescription);
                return true;
            } else if (REGISTER == evt) {
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), EXPIRY_TIME);
                mDialog = mClientTransaction.getDialog();
                mState = SipSessionState.REGISTERING;
                return true;
            } else if (DEREGISTER == evt) {
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), 0);
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
                endCall(false);
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                RequestEvent event = (RequestEvent) evt;
                mSipHelper.sendResponse(event, Response.OK);
                mSipHelper.sendInviteRequestTerminated(
                        mInviteReceived.getRequest(), mServerTransaction);
                endCall(false);
                return true;
            }
            return false;
        }

        private boolean incomingCallToInCall(EventObject evt)
                throws SipException {
            // expect ACK, CANCEL request
            if (isRequestEvent(Request.ACK, evt)) {
                establishCall(false);
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
                        mListener.onRingingBack(this);
                    }
                    return true;
                case Response.OK:
                    mSipHelper.sendInviteAck(event, mDialog);
                    mPeerSessionDescription = response.getRawContent();
                    establishCall(false);
                    return true;
                case Response.PROXY_AUTHENTICATION_REQUIRED:
                    mClientTransaction = mSipHelper.handleChallenge(
                            (ResponseEvent)evt, mLocalProfile);
                    mDialog = mClientTransaction.getDialog();
                    return true;
                default:
                    if (statusCode >= 400) {
                        // error: an ack is sent automatically by the stack
                        endCallOnError(false,
                                createCallbackException(response));
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
                        endCall(false);
                        return true;
                    }
                } else {
                    return false;
                }

                if (statusCode >= 400) {
                    endCallOnError(true, createCallbackException(response));
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
                endCall(true);
                return true;
            } else if (isRequestEvent(Request.INVITE, evt)) {
                // got Re-INVITE
                RequestEvent event = (RequestEvent) evt;
                mSipHelper.sendReInviteOk(event, mLocalProfile);
                mState = SipSessionState.IN_CALL_ANSWERING;
                mPeerSessionDescription = event.getRequest().getRawContent();
                mListener.onCallChanged(this, mPeerSessionDescription);
                return true;
            } else if (isRequestEvent(Request.BYE, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.OK);
                endCall(true);
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
                    establishCall(true);
                    return true;
                case Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST:
                case Response.REQUEST_TIMEOUT:
                    // rfc3261#section-14.1: re-invite failed; terminate
                    // the dialog
                    endCallOnError(true, createCallbackException(response));
                    return true;
                case Response.REQUEST_PENDING:
                    // TODO:
                    // rfc3261#section-14.1; re-schedule invite
                    return true;
                default:
                    if (statusCode >= 400) {
                        // error: an ack is sent automatically by the stack
                        mState = SipSessionState.IN_CALL;
                        mListener.onError(this,
                                createCallbackException(response));
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
                establishCall(true);
                return true;
            }
            return false;
        }

        private boolean inCallAnsweringToInCall(EventObject evt) {
            // expect ACK
            if (isRequestEvent(Request.ACK, evt)) {
                establishCall(true);
                return true;
            }
            return false;
        }

        private Exception createCallbackException(Response response) {
            return new SipException(String.format("Response: %s (%d)",
                    response.getReasonPhrase(), response.getStatusCode()));
        }

        private void establishCall(boolean inCall) {
            if (inCall) {
                mState = SipSessionState.IN_CALL;
                mListener.onCallEstablished(this, mPeerSessionDescription);
            } else {
                SipSessionImpl newSession = createInCallSipSession();
                addSipSession(newSession);
                reset();
                newSession.establishCall(true);
            }
        }

        private void endCall(boolean inCall) {
            if (inCall) removeSipSession(this);
            reset();
            mListener.onCallEnded(this);
        }

        private void endCallOnError(
                boolean terminating, Throwable throwable) {
            if (terminating) removeSipSession(this);
            reset();
            mListener.onError(this, throwable);
        }

        private boolean isInCall() {
            return (mState.compareTo(SipSessionState.IN_CALL) >= 0);
        }

        private SipSessionImpl createInCallSipSession() {
            SipSessionImpl newSession = new SipSessionImpl(mListener);
            newSession.mPeerProfile = mPeerProfile;
            newSession.mState = SipSessionState.IN_CALL;
            newSession.mInviteReceived = mInviteReceived;
            newSession.mDialog = mDialog;
            newSession.mPeerSessionDescription = mPeerSessionDescription;
            return newSession;
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

    private class MakeCallCommand extends EventObject {
        private SessionDescription mSessionDescription;

        MakeCallCommand(SipProfile peerProfile,
                SessionDescription sessionDescription) {
            super(peerProfile);
            mSessionDescription = sessionDescription;
        }

        SipProfile getPeerProfile() {
            return (SipProfile) getSource();
        }

        SessionDescription getSessionDescription() {
            return mSessionDescription;
        }
    }
}
