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

import gov.nist.javax.sdp.fields.SDPKeywords;

import com.android.sip.media.RtpFactory;
import com.android.sip.media.RtpSession;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.net.sip.SdpSessionDescription;
import android.net.sip.SessionDescription;
import android.net.sip.SipProfile;
import android.net.sip.SipSession;
import android.net.sip.SipSessionLayer;
import android.net.sip.SipSessionListener;
import android.net.sip.SipSessionState;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sdp.SdpException;
import javax.sip.SipException;

/**
 * Class that handles audio call over SIP.
 */
public class SipAudioCall {
    private static final String TAG = SipAudioCall.class.getSimpleName();

    public interface Listener {
        void onReadyForCall(SipAudioCall call);
        void onCalling(SipAudioCall call);
        void onRinging(SipAudioCall call, SipProfile caller);
        void onRingingBack(SipAudioCall call);
        void onCallEstablished(SipAudioCall call);
        void onCallEnded(SipAudioCall call);
        void onCallBusy(SipAudioCall call);
        void onCallHeld(SipAudioCall call);
        void onError(SipAudioCall call, Throwable e);
    }

    private Context mContext;
    private SipProfile mLocalProfile;
    private Listener mListener;
    private SipSessionLayer mSipSessionLayer;
    private SipSession mSipSession;
    private SipSession mSipCallSession;
    private SdpSessionDescription mOfferSd;

    private RtpSession mRtpSession;
    private DatagramSocket mMediaSocket;
    private boolean mHolding = false;

    private boolean mRingbackToneEnabled = true;
    private boolean mRingtoneEnabled = true;
    private Ringtone mRingtone;
    private ToneGenerator mRingbackTone;

    private SipProfile mPendingCallRequest;

    public SipAudioCall(Context context, SipProfile localProfile,
            Listener listener) throws SipException {
        if (listener == null) {
            throw new NullPointerException("listener can't be null");
        }
        mContext = context;
        mLocalProfile = localProfile;
        mListener = listener;
        mSipSessionLayer = new SipSessionLayer();
        mSipSession = mSipSessionLayer.createSipSession(
                localProfile, createSipSessionListener());
    }

    // TODO: remove this after SipService is done
    public void register() throws SipException {
        if (mSipSession != null) mSipSession.register();
    }

    public void close() {
        if (mSipSessionLayer != null) {
            stopCall();
            mSipSessionLayer.close();
            mSipSessionLayer = null;
            mSipSession = null;
        }
        stopRingbackTone();
        stopRinging();
    }

    public SipSessionState getState() {
        return getActiveSession().getState();
    }

    private SipSession getActiveSession() {
        return ((mSipCallSession == null) ? mSipSession
                                          : mSipCallSession);
    }

    private SipSessionListener createSipSessionListener() {
        return new SipSessionListener() {
            public void onCalling(SipSession session) {
                Log.v(TAG, "calling... " + session);
                mListener.onCalling(SipAudioCall.this);
            }

            public void onRinging(SipSession session, SipProfile caller,
                    byte[] sessionDescription) {
                startRinging();
                try {
                    SdpSessionDescription sd = mOfferSd =
                            new SdpSessionDescription(sessionDescription);
                    Log.v(TAG, "sip call ringing: " + session + ": " + sd);
                } catch (SdpException e) {
                    Log.e(TAG, "create SDP", e);
                }
                mListener.onRinging(SipAudioCall.this, caller);
            }

            public void onRingingBack(SipSession session) {
                Log.v(TAG, "sip call ringing back: " + session);
                startRingbackTone();
                mListener.onRingingBack(SipAudioCall.this);
            }

            public void onCallEstablished(
                    SipSession session, byte[] sessionDescription) {
                stopRingbackTone();
                stopRinging();
                try {
                    SdpSessionDescription sd =
                            new SdpSessionDescription(sessionDescription);
                    Log.v(TAG, "sip call established: " + session + ": " + sd);
                    mSipCallSession = session;
                    startCall(sd);
                } catch (SdpException e) {
                    Log.e(TAG, "createSessionDescription()", e);
                }
                mListener.onCallEstablished(SipAudioCall.this);
            }

            public void onCallEnded(SipSession session) {
                Log.v(TAG, "sip call ended: " + session);
                stopCall();
                stopRingbackTone();
                stopRinging();
                mSipCallSession = null;
                mHolding = false;
                mListener.onCallEnded(SipAudioCall.this);
            }

            public void onCallBusy(SipSession session) {
                Log.v(TAG, "sip call busy: " + session);
                mListener.onCallBusy(SipAudioCall.this);
            }

            public void onCallChanged(
                    SipSession session, byte[] sessionDescription) {
                String message = new String(sessionDescription);
                Log.v(TAG, "sip call " + message + ": " + session);
                mHolding = !mHolding;
                if (mHolding) {
                    mListener.onCallHeld(SipAudioCall.this);
                } else {
                    mListener.onCallEstablished(SipAudioCall.this);
                }
            }

            public void onError(SipSession session, Throwable e) {
                Log.v(TAG, "sip session error: " + e);
                stopRingbackTone();
                stopRinging();
                mHolding = false;
                mListener.onError(SipAudioCall.this, e);
            }

            public void onRegistrationDone(SipSession session) {
                Log.v(TAG, "sip registration done: " + session);
                synchronized (session) {
                    if (mPendingCallRequest != null) {
                        SipProfile peerProfile = mPendingCallRequest;
                        mPendingCallRequest = null;
                        try {
                            makeCall(peerProfile);
                        } catch (SipException e) {
                            mListener.onError(SipAudioCall.this, e);
                            return;
                        }
                    } else {
                        mListener.onReadyForCall(SipAudioCall.this);
                    }
                }
            }

            public void onRegistrationFailed(SipSession session, Throwable e) {
                Log.v(TAG, "sip registration failed: " + session + ": " + e);
                if (mPendingCallRequest != null) mPendingCallRequest = null;
                mListener.onError(SipAudioCall.this, e);
            }

            public void onRegistrationTimeout(SipSession session) {
                Log.v(TAG, "sip registration timed out: " + session);
                if (mPendingCallRequest != null) mPendingCallRequest = null;
                mListener.onError(SipAudioCall.this,
                        new SipException("SIP registration timed out"));
            }
        };
    }

    public void makeCall(SipProfile peerProfile) throws SipException {
        synchronized (mSipSession) {
            if (mSipSession.getState() == SipSessionState.READY_FOR_CALL) {
                Log.v(TAG, "making call...");
                mSipSession.makeCall(
                        peerProfile, createOfferSessionDescription());
            } else {
                Log.v(TAG, "hold the call request...");
                mPendingCallRequest = peerProfile;
            }
        }
    }

    public void endCall() throws SipException {
        stopRinging();
        getActiveSession().endCall();
    }

    public void holdCall() throws SipException {
        if (mHolding) return;
        getActiveSession().changeCall(createHoldSessionDescription());
        mHolding = true;
    }

    public void answerCall() throws SipException {
        stopRinging();
        getActiveSession().answerCall(createAnswerSessionDescription());
    }

    public void continueCall() throws SipException {
        if (!mHolding) return;
        getActiveSession().changeCall(createContinueSessionDescription());
        mHolding = false;
    }

    private SessionDescription createOfferSessionDescription() {
        RtpSession[] rtpSessions = RtpFactory.getSystemSupportedAudioSessions();
        return createSdpBuilder(rtpSessions).build();
    }

    private SessionDescription createAnswerSessionDescription() {
        // choose an acceptable media from mOfferSd to answer
        RtpSession rtpSession =
                RtpFactory.createAudioSession(getCodecId(mOfferSd));
        return createSdpBuilder(rtpSession).build();
    }

    private SessionDescription createHoldSessionDescription() {
        try {
            return createSdpBuilder(mRtpSession)
                    .addMediaAttribute("sendonly", (String) null)
                    .build();
        } catch (SdpException e) {
            throw new RuntimeException(e);
        }
    }

    private SessionDescription createContinueSessionDescription() {
        return createSdpBuilder(mRtpSession).build();
    }

    private String getMediaDescription(RtpSession session) {
        return String.format("%d %s/%d", session.getCodecId(),
                session.getName(), session.getSampleRate());
    }

    private SdpSessionDescription.Builder createSdpBuilder(RtpSession... rtpSessions) {
        String localIp = getLocalIp();
        SdpSessionDescription.Builder sdpBuilder;
        try {
            long sessionId = (long) (Math.random() * 10000000L);
            long sessionVersion = (long) (Math.random() * 10000000L);
            sdpBuilder = new SdpSessionDescription.Builder("SIP Call")
                    .setOrigin(mLocalProfile, sessionId, sessionVersion,
                            SDPKeywords.IN, SDPKeywords.IPV4, localIp)
                    .setConnectionInfo(SDPKeywords.IN, SDPKeywords.IPV4,
                            localIp);
            List<Integer> codecIds = new ArrayList<Integer>();
            for (RtpSession s : rtpSessions) {
                codecIds.add(s.getCodecId());
            }
            sdpBuilder.addMedia("audio", getLocalMediaPort(), 1, "RTP/AVP",
                    codecIds.toArray(new Integer[codecIds.size()]));
            for (RtpSession s : rtpSessions) {
                sdpBuilder.addMediaAttribute("rtpmap", getMediaDescription(s));
            }
            sdpBuilder.addMediaAttribute("rtpmap", "101 telephone-event/8000");
            // FIXME: deal with vbr codec
            sdpBuilder.addMediaAttribute("ptime", "20");
        } catch (SdpException e) {
            throw new RuntimeException(e);
        }
        return sdpBuilder;
    }

    public void setInCallMode() {
        ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                .setMode(AudioManager.MODE_IN_CALL);
    }

    public void setSpeakerMode() {
        ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                .setMode(AudioManager.MODE_NORMAL);
    }

    private int getCodecId(SdpSessionDescription sd) {
        Set<Integer> acceptableFormats = new HashSet<Integer>();
        for (RtpSession session :
                RtpFactory.getSystemSupportedAudioSessions()) {
            acceptableFormats.add(session.getCodecId());
        }
        for (int id : sd.getMediaFormats()) {
            if (acceptableFormats.contains(id)) return id;
        }
        Log.w(TAG, "no common codec is found, use 0");
        return 0;
    }

    private void startCall(SdpSessionDescription sd) {
        String peerMediaAddress = sd.getPeerMediaAddress();
        // TODO: handle multiple media fields
        int peerMediaPort = sd.getPeerMediaPort();
        Log.i(TAG, "start audiocall " + peerMediaAddress + ":" + peerMediaPort);

        int localPort = getLocalMediaPort();
        int sampleRate = 8000;
        int frameSize = sampleRate / 50; // 160
        try {
            // TODO: get sample rate from sdp
            mMediaSocket.connect(InetAddress.getByName(peerMediaAddress),
                    peerMediaPort);
            mRtpSession = RtpFactory.createAudioSession(getCodecId(sd));
            mRtpSession.start(sampleRate, mMediaSocket);
            setInCallMode();
        } catch (Exception e) {
            Log.e(TAG, "call()", e);
        }
        Log.v(TAG, " ~~~~~~~~~~~   start media: localPort=" + localPort
                + ", peer=" + peerMediaAddress + ":" + peerMediaPort);
    }

    public void stopCall() {
        Log.i(TAG, "stop audiocall");
        if (mRtpSession != null) {
            mRtpSession.stop();
            if (mMediaSocket != null) mMediaSocket.close();
            mMediaSocket = null;
        }
        setSpeakerMode();
    }

    public void sendDtmf() {
        SipSession activeSession = getActiveSession();
        if (SipSessionState.IN_CALL == activeSession.getState()) {
            mRtpSession.sendDtmf();
        }
    }

    private int getLocalMediaPort() {
        if (mMediaSocket != null) return mMediaSocket.getLocalPort();
        try {
            DatagramSocket s = mMediaSocket = new DatagramSocket();
            int localPort = s.getLocalPort();
            return localPort;
        } catch (IOException e) {
            Log.w(TAG, "getLocalMediaPort(): " + e);
            throw new RuntimeException(e);
        }
    }

    public String getLocalIp() {
        return mSipSessionLayer.getLocalIp();
    }

    public void setRingbackToneEnabled(boolean enabled) {
        mRingbackToneEnabled = enabled;
    }

    public void setRingtoneEnabled(boolean enabled) {
        mRingtoneEnabled = enabled;
    }

    private synchronized void startRingbackTone() {
        if (!mRingbackToneEnabled) return;
        if (mRingbackTone == null) {
            // The volume relative to other sounds in the stream
            int toneVolume = 80;
            mRingbackTone = new ToneGenerator(
                    AudioManager.STREAM_MUSIC, toneVolume);
        }
        setInCallMode();
        mRingbackTone.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L);
    }

    private synchronized void stopRingbackTone() {
        if (mRingbackTone != null) {
            mRingbackTone.stopTone();
            setSpeakerMode();
            mRingbackTone.release();
            mRingbackTone = null;
        }
    }

    private synchronized void startRinging() {
        if (!mRingtoneEnabled) return;
        ((Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE))
                .vibrate(new long[] {0, 1000, 1000}, 1);
        AudioManager am = (AudioManager)
                mContext.getSystemService(Context.AUDIO_SERVICE);
        if (am.getStreamVolume(AudioManager.STREAM_RING) > 0) {
            String ringtoneUri =
                    Settings.System.DEFAULT_RINGTONE_URI.toString();
            mRingtone = RingtoneManager.getRingtone(mContext,
                    Uri.parse(ringtoneUri));
            mRingtone.play();
        }
    }

    private synchronized void stopRinging() {
        ((Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE))
                .cancel();
        if (mRingtone != null) mRingtone.stop();
    }
}
