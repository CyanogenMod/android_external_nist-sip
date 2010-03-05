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

import android.util.Log;

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
 * @hide
 * Creates and manages multiple {@link SipSession}.
 */
public class SipManager implements SipListener {
    private static final String TAG = SipManager.class.getSimpleName();
    private static final int    EXPIRY_TIME = 3600;

    private static final EventObject REGISTER = new EventObject("Register");
    private static final EventObject DEREGISTER = new EventObject("Deregister");
    private static final EventObject END_CALL = new EventObject("End call");
    private static final EventObject HOLD_CALL = new EventObject("Hold call");
    private static final EventObject CONTINUE_CALL
            = new EventObject("Continue call");

    private SipHelper mSipHelper;

    // myself@domain-to-SipSession map
    private Map<String, SipSessionImpl> mSessionMap =
            new HashMap<String, SipSessionImpl>();

    public SipManager(String myIp) {
        this(myIp, ListeningPoint.PORT_5060, "Android SIP Stack");
    }

    public SipManager(String myIp, int port, String stackName) {
        SipFactory sipFactory = SipFactory.getInstance();
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", stackName);
        try {
            SipStack stack = sipFactory.createSipStack(properties);
            SipProvider sipProvider = stack.createSipProvider(
                    stack.createListeningPoint(myIp, port, ListeningPoint.UDP));
            sipProvider.addListeningPoint(
                    stack.createListeningPoint(myIp, port, ListeningPoint.TCP));
            sipProvider.addSipListener(this);
            mSipHelper = new SipHelper(stack, sipProvider);
            stack.start();
        } catch (TooManyListenersException e) {
            // must never happen
            Log.e(TAG, "", e);
        } catch (SipException e) {
            Log.e(TAG, "", e);
        }
    }

    public synchronized void release() {
        if (mSipHelper != null) {
            mSipHelper.getSipStack().stop();
            mSipHelper = null;
        }
    }

    public synchronized SipSession createSipSession(SipProfile myself,
            SipSessionListener listener) throws SipException {
        SipSessionImpl session = new SipSessionImpl(myself, listener);
        String key = mSipHelper.getSessionKey(session);
        Log.v(TAG, "add a session with key:  '" + key + "'");
        mSessionMap.put(key, session);
        for (String k : mSessionMap.keySet()) {
            Log.v(TAG, "   -----  " + k + ": " + mSessionMap.get(k));
        }
        return session;
    }

    private synchronized SipSessionImpl getSipSession(EventObject event) {
        String[] keys = mSipHelper.getPossibleSessionKeys(event);
        Log.v(TAG, " possible keys " + keys.length + " for event: " + event);
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
        return null;
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
        Log.v(TAG, " -----  remove session with key:  '" + key + "'");
        mSessionMap.remove(key);
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
        // any clean-up?
    }

    public void processDialogTerminated(DialogTerminatedEvent event) {
        // any clean-up?
    }

    private void process(EventObject event) {
        try {
            getSipSession(event).process(event);
        } catch (Exception e) {
            // TODO: suppress stack trace after code gets stabilized
            Log.e(TAG, "event not processed: " + event, e);
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

    private class SipSessionImpl implements SipSession {
        private SipProfile mLocalProfile;
        private SipProfile mPeerProfile;
        private SipSessionListener mListener;
        private SipSessionState mState = SipSessionState.READY_FOR_CALL;
        private RequestEvent mInviteReceived;
        private Dialog mDialog;
        private ServerTransaction mServerTransaction;
        private ClientTransaction mClientTransaction;

        SipSessionImpl(SipProfile myself, SipSessionListener listener) {
            mLocalProfile = myself;
            mListener = listener;
        }

        private void reset() {
            mPeerProfile = null;
            mState = SipSessionState.READY_FOR_CALL;
            mInviteReceived = null;
            mDialog = null;
            mServerTransaction = null;
            mClientTransaction = null;
        }

        public SipProfile getLocalProfile() {
            return mLocalProfile;
        }

        public synchronized SipProfile getPeerProfile() {
            return mPeerProfile;
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

        synchronized void process(EventObject event) throws SipException {
            Log.v(TAG, " ~~~~~   " + this + ": " + mState + ": processing " + log(event));
            boolean processed;
            switch (mState) {
            case REGISTERING:
            case DEREGISTERING:
                processed = registeringToReady(event);
                break;
            case READY_FOR_CALL:
                processed = readyForCall(event);
                break;
            case INCOMING_CALL:
                processed = incomingCall(event);
                break;
            case INCOMING_CALL_ANSWERING:
                processed = incomingCallToInCall(event);
                break;
            case OUTGOING_CALL:
            case OUTGOING_CALL_RING_BACK:
                processed = outgoingCall(event);
                break;
            case OUTGOING_CALL_CANCELING:
                processed = outgoingCallToReady(event);
                break;
            case IN_CALL:
                processed = inCall(event);
                break;
            case ENDING_CALL:
                processed = endingCallToReady(event);
                break;
            default:
                processed = false;
            }
            if (!processed && !processOthers(event)) {
                throw new SipException("event not processed");
            } else {
                Log.v(TAG, " ~~~~~   new state: " + mState);
            }
        }

        private boolean processOthers(EventObject evt) throws SipException {
            // process INVITE and CANCEL
            if (isRequestEvent(Request.INVITE, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.BUSY_HERE);
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt,
                        Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST);
                return true;
            }
            return false;
        }

        private void scheduleRegistrationRefreshing(
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

        private boolean registeringToReady(EventObject evt)
                throws SipException {
            if (expectResponse(Response.OK, Request.REGISTER, evt)) {
                if (mState == SipSessionState.REGISTERING) {
                    scheduleRegistrationRefreshing((ExpiresHeader)(
                            ((ResponseEvent)evt).getResponse().getHeader(
                            ExpiresHeader.NAME)));
                }
                reset();
                mListener.onRegistrationDone(this);
                return true;
            }
            if (expectResponse(Response.UNAUTHORIZED, Request.REGISTER, evt) ||
                    expectResponse(Response.PROXY_AUTHENTICATION_REQUIRED,
                    Request.REGISTER, evt)) {
                mSipHelper.handleChallenge((ResponseEvent)evt, mLocalProfile);
                return true;
            }
            //TODO: handle error conditions
            return false;
        }

        private boolean readyForCall(EventObject evt) throws SipException {
            // expect MakeCallCommand, Invite
            if (evt instanceof MakeCallCommand) {
                MakeCallCommand cmd = (MakeCallCommand) evt;
                mPeerProfile = cmd.getPeerProfile();
                SessionDescription sessionDescription = cmd.getSessionDescription();
                mClientTransaction = mSipHelper.sendInvite(mLocalProfile,
                        mPeerProfile, sessionDescription, generateTag());
                mDialog = mClientTransaction.getDialog();
                mState = SipSessionState.OUTGOING_CALL;
                return true;
            } else if (isRequestEvent(Request.INVITE, evt)) {
                RequestEvent event = (RequestEvent) evt;
                mServerTransaction = mSipHelper.sendRinging(event);
                mDialog = mServerTransaction.getDialog();
                mInviteReceived = event;
                mPeerProfile = createPeerProfile(event.getRequest());
                mState = SipSessionState.INCOMING_CALL;
                mListener.onRinging(SipSessionImpl.this);
                return true;
            } else if (REGISTER == evt) {
                mClientTransaction = mSipHelper.sendRegister(mLocalProfile,
                        generateTag(), EXPIRY_TIME);
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
            // expect MakeCallCommand(answering) , END_CALL cmd , Cancel request
            if (evt instanceof MakeCallCommand) {
                // answer call
                MakeCallCommand cmd = (MakeCallCommand) evt;

                mSipHelper.sendInviteOk(mInviteReceived, mLocalProfile,
                        cmd.getSessionDescription(), generateTag(),
                        mServerTransaction);
                mState = SipSessionState.INCOMING_CALL_ANSWERING;
                return true;
            } else if (END_CALL == evt) {
                mSipHelper.sendInviteBusyHere(mInviteReceived,
                        mServerTransaction);
                reset();
                return true;
            } else if (isRequestEvent(Request.CANCEL, evt)) {
                RequestEvent event = (RequestEvent) evt;
                mSipHelper.sendResponse(event, Response.OK);
                mSipHelper.sendInviteRequestTerminated(
                        mInviteReceived.getRequest(),
                        mServerTransaction);
                reset();
                mListener.onCallEnded(this);
                return true;
            }
            return false;
        }

        private boolean incomingCallToInCall(EventObject evt)
                throws SipException {
            // expect ACK, CANCEL request
            if (isRequestEvent(Request.ACK, evt)) {
                SipSessionImpl newSession = createInCallSipSession();
                addSipSession(newSession);
                reset();
                mListener.onCallEstablished(newSession);
                return true;
            } else if (isRequestEvent(Request.ACK, evt)) {
                RequestEvent event = (RequestEvent) evt;
                // TODO: what to do here? what happens when racing between
                // OK-to-invite from callee and Cancel from caller
                return true;
            }
            return false;
        }

        private boolean outgoingCall(EventObject evt) throws SipException {
            if (evt instanceof ResponseEvent) {
                ResponseEvent event = (ResponseEvent) evt;
                Response response = event.getResponse();
                String method = getCseqMethod(response);
                if (!method.equalsIgnoreCase(Request.INVITE)) return false;

                int statusCode = response.getStatusCode();
                switch (statusCode) {
                case Response.TRYING:
                    break;
                case Response.RINGING:
                    mState = SipSessionState.OUTGOING_CALL_RING_BACK;
                    mListener.onRingingBack(this);
                    break;
                case Response.OK:
                    mSipHelper.sendInviteAck(event, mDialog);
                    SipSessionImpl newSession = createInCallSipSession();
                    addSipSession(newSession);
                    reset();
                    mListener.onCallEstablished(newSession);
                    break;
                default:
                    if (statusCode >= 400) {
                        // error: an ack is sent automatically by the stack
                        reset();
                        mListener.onCallEnded(this);
                        break;
                    } else if (statusCode >= 300) {
                        // TODO: handle 3xx (redirect)
                    }
                    return false;
                }
                return true;
            } else if (END_CALL == evt) {
                // RFC says that UA should not send out cancel when no response
                // comes back yet. We are cheating for not checking response.
                mSipHelper.sendCancel(mClientTransaction);
                mState = SipSessionState.OUTGOING_CALL_CANCELING;
                return true;
            }
            return false;
        }

        private boolean outgoingCallToReady(EventObject evt)
                throws SipException {
            if (expectResponse(Response.OK, Request.CANCEL, evt)) {
                reset();
                mListener.onCallEnded(this);
                return true;
            }
            return false;
        }

        private boolean inCall(EventObject evt) throws SipException {
            // expect END_CALL cmd, OK response retransmission, BYE request,
            // hold call (MakeCallCommand)
            if (END_CALL == evt) {
                mSipHelper.sendBye(mDialog);
                mState = SipSessionState.ENDING_CALL;
                return true;
            } else if (expectResponse(Response.OK, Request.INVITE, evt)) {
                // retransmit ack; this may be OK-to-re-invite
                mSipHelper.sendInviteAck((ResponseEvent) evt, mDialog);
                return true;
            } else if (isRequestEvent(Request.INVITE, evt)) {
                // got Re-INVITE
                RequestEvent event = (RequestEvent) evt;
                mSipHelper.sendReInviteOk(event, mLocalProfile);
                mListener.onCallChanged(this,
                        event.getRequest().getRawContent());
                return true;
            } else if (isRequestEvent(Request.BYE, evt)) {
                mSipHelper.sendResponse((RequestEvent) evt, Response.OK);
                removeSipSession(this);
                mListener.onCallEnded(this);
                return true;
            } else if (isRequestEvent(Request.ACK, evt)) {
                // ack the ok-to-re-invite; just consume it
                return true;
            } if (evt instanceof MakeCallCommand) {
                // to change call
                MakeCallCommand cmd = (MakeCallCommand) evt;
                mClientTransaction = mSipHelper.sendReinvite(
                        mDialog, cmd.getSessionDescription());
                return true;
            }
            return false;
        }

        private boolean endingCallToReady(EventObject evt)
                throws SipException {
            if (expectResponse(Response.OK, Request.BYE, evt)) {
                removeSipSession(this);
                mListener.onCallEnded(this);
                return true;
            }
            return false;
        }

        private SipSessionImpl createInCallSipSession() {
            SipSessionImpl newSession =
                    new SipSessionImpl(mLocalProfile, mListener);
            newSession.mPeerProfile = mPeerProfile;
            newSession.mState = SipSessionState.IN_CALL;
            newSession.mInviteReceived = mInviteReceived;
            newSession.mDialog = mDialog;
            return newSession;
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
