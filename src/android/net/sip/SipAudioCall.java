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

import javax.sip.SipException;

/**
 * {@hide}
 * Interface for making audio calls over SIP.
 */
public interface SipAudioCall {
    /** Listener class for all event callbacks. */
    public interface Listener {
        void onReadyToCall(SipAudioCall call);
        void onCalling(SipAudioCall call);
        void onRinging(SipAudioCall call, SipProfile caller);
        void onRingingBack(SipAudioCall call);
        void onCallEstablished(SipAudioCall call);
        void onCallEnded(SipAudioCall call);
        void onCallBusy(SipAudioCall call);
        void onCallHeld(SipAudioCall call);
        void onError(SipAudioCall call, String errorMessage);
    }

    /** Listener adapter class. */
    public class Adapter implements Listener {
        /**
         * All the event callbacks are routed here. The default implementation
         * is no-op.
         */
        protected void onChanged(SipAudioCall call) {
        }

        public void onReadyToCall(SipAudioCall call) {
            onChanged(call);
        }
        public void onCalling(SipAudioCall call) {
            onChanged(call);
        }
        public void onRinging(SipAudioCall call, SipProfile caller) {
            onChanged(call);
        }
        public void onRingingBack(SipAudioCall call) {
            onChanged(call);
        }
        public void onCallEstablished(SipAudioCall call) {
            onChanged(call);
        }
        public void onCallEnded(SipAudioCall call) {
            onChanged(call);
        }
        public void onCallBusy(SipAudioCall call) {
            onChanged(call);
        }
        public void onCallHeld(SipAudioCall call) {
            onChanged(call);
        }
        public void onError(SipAudioCall call, String errorMessage) {
            onChanged(call);
        }
    }

    void setListener(Listener listener);
    void close();
    void makeCall(SipProfile peerProfile, ISipService service)
            throws SipException;
    void attachCall(ISipSession session, byte[] sessionDescription)
            throws SipException;
    void endCall() throws SipException;
    void holdCall() throws SipException;
    void answerCall() throws SipException;
    void continueCall() throws SipException;
    void setInCallMode();
    void setSpeakerMode();
    void toggleMute();
    boolean isMuted();
    void sendDtmf();
    SipSessionState getState();
    ISipSession getSipSession();
    void setRingbackToneEnabled(boolean enabled);
    void setRingtoneEnabled(boolean enabled);
}
