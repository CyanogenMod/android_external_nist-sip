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
import android.net.sip.ISipSession;
import android.net.sip.SdpSessionDescription;
import android.net.sip.SessionDescription;
import android.net.sip.SipAudioCall;
import android.net.sip.SipProfile;
import android.net.sip.ISipService;
import android.net.sip.SipSessionAdapter;
import android.net.sip.SipSessionState;
import android.os.RemoteException;
import android.os.Vibrator;
import android.provider.Settings;
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
 * Class that handles an audio call over SIP.
 */
public class SipAudioCallImpl implements SipAudioCall {
    private static final String TAG = SipAudioCallImpl.class.getSimpleName();

    private Context mContext;
    private SipProfile mLocalProfile;
    private SipAudioCall.Listener mListener;
    private ISipSession mSipSession;
    private SdpSessionDescription mOfferSd;

    private RtpSession mRtpSession;
    private DatagramSocket mMediaSocket;
    private boolean mHolding = false;

    private boolean mRingbackToneEnabled = true;
    private boolean mRingtoneEnabled = true;
    private Ringtone mRingtone;
    private ToneGenerator mRingbackTone;

    private SipProfile mPendingCallRequest;

    public SipAudioCallImpl(Context context, SipProfile localProfile) {
        mContext = context;
        mLocalProfile = localProfile;
    }

    public void setListener(SipAudioCall.Listener listener) {
        mListener = listener;
        if (listener == null) return;
        try {
            SipSessionState state = getState();
            switch (state) {
            case READY_TO_CALL:
                listener.onReadyToCall(this);
                break;
            case INCOMING_CALL:
                listener.onRinging(this, getPeerProfile(mSipSession));
                startRinging();
                break;
            default:
                listener.onError(this, "wrong state to attach call: " + state);
            }
        } catch (Throwable t) {
            Log.e(TAG, "setListener()", t);
        }
    }

    public void close() {
        if (mSipSession != null) {
            stopCall();
            mSipSession = null;
        }
        stopRingbackTone();
        stopRinging();
    }

    public SipSessionState getState() {
        if (mSipSession == null) return SipSessionState.READY_TO_CALL;
        try {
            return Enum.valueOf(SipSessionState.class, mSipSession.getState());
        } catch (RemoteException e) {
            return SipSessionState.REMOTE_ERROR;
        }
    }


    public ISipSession getSipSession() {
        return mSipSession;
    }

    private SipSessionAdapter createSipSessionListener() {
        return new SipSessionAdapter() {
            public void onCalling(ISipSession session) {
                Log.d(TAG, "calling... " + session);
                if (mListener != null) {
                    try {
                        mListener.onCalling(SipAudioCallImpl.this);
                    } catch (Throwable t) {
                        Log.e(TAG, "onCalling()", t);
                    }
                }
            }

            public void onRingingBack(ISipSession session) {
                Log.d(TAG, "sip call ringing back: " + session);
                startRingbackTone();
                if (mListener != null) {
                    try {
                        mListener.onRingingBack(SipAudioCallImpl.this);
                    } catch (Throwable t) {
                        Log.e(TAG, "onRingingBack()", t);
                    }
                }
            }

            public void onCallEstablished(
                    ISipSession session, byte[] sessionDescription) {
                stopRingbackTone();
                stopRinging();
                try {
                    SdpSessionDescription sd =
                            new SdpSessionDescription(sessionDescription);
                    Log.d(TAG, "sip call established: " + session + ": " + sd);
                    startCall(sd);
                } catch (SdpException e) {
                    Log.e(TAG, "createSessionDescription()", e);
                }
                if (mListener != null) {
                    try {
                        mListener.onCallEstablished(SipAudioCallImpl.this);
                    } catch (Throwable t) {
                        Log.e(TAG, "onCallEstablished()", t);
                    }
                }
            }

            public void onCallEnded(ISipSession session) {
                Log.d(TAG, "sip call ended: " + session);
                stopCall();
                stopRingbackTone();
                stopRinging();
                mSipSession = null;
                mHolding = false;
                if (mListener != null) {
                    try {
                        mListener.onCallEnded(SipAudioCallImpl.this);
                    } catch (Throwable t) {
                        Log.e(TAG, "onCallEnded()", t);
                    }
                }
            }

            public void onCallBusy(ISipSession session) {
                Log.d(TAG, "sip call busy: " + session);
                if (mListener != null) {
                    try {
                        mListener.onCallBusy(SipAudioCallImpl.this);
                    } catch (Throwable t) {
                        Log.e(TAG, "onCallBusy()", t);
                    }
                }
            }

            public void onCallChanged(
                    ISipSession session, byte[] sessionDescription) {
                String message = new String(sessionDescription);
                Log.d(TAG, "sip call " + message + ": " + session);
                mHolding = !mHolding;

                if (mListener == null) return;
                if (mHolding) {
                    try {
                        mListener.onCallHeld(SipAudioCallImpl.this);
                    } catch (Throwable t) {
                        Log.e(TAG, "onCallHeld()", t);
                    }
                } else {
                    try {
                        mListener.onCallEstablished(SipAudioCallImpl.this);
                    } catch (Throwable t) {
                        Log.e(TAG, "onCallEstablished()", t);
                    }
                }
            }

            public void onError(ISipSession session, String className,
                    String message) {
                Log.d(TAG, "sip session error: " + className + ": " + message);
                stopCall();
                stopRingbackTone();
                stopRinging();
                mSipSession = null;
                mHolding = false;
                if (mListener != null) {
                    try {
                        mListener.onError(SipAudioCallImpl.this,
                                className + ": " + message);
                    } catch (Throwable t) {
                        Log.e(TAG, "onError()", t);
                    }
                }
            }
        };
    }

    public void attachCall(ISipSession session, byte[] sessionDescription)
            throws SipException {
        mSipSession = session;
        try {
            mOfferSd = new SdpSessionDescription(sessionDescription);
            session.setListener(createSipSessionListener());
        } catch (Throwable e) {
            Log.e(TAG, "attachCall()", e);
            throwSipException(e);
        }
    }

    public void makeCall(SipProfile peerProfile, ISipService sipService)
            throws SipException {
        try {
            mSipSession = sipService.createSession(mLocalProfile, 
                    createSipSessionListener());
            mSipSession.makeCall(peerProfile, createOfferSessionDescription());
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    public void endCall() throws SipException {
        try {
            stopRinging();
            if (mSipSession != null) mSipSession.endCall();
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    public void holdCall() throws SipException {
        try {
            if (mHolding) return;
            if (mSipSession != null) {
                mSipSession.changeCall(createHoldSessionDescription());
            }
            mHolding = true;
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    public void answerCall() throws SipException {
        try {
            stopRinging();
            if (mSipSession != null) {
                mSipSession.answerCall(createAnswerSessionDescription());
            }
        } catch (Throwable e) {
            Log.e(TAG, "answerCall()", e);
            throwSipException(e);
        }
    }

    public void continueCall() throws SipException {
        try {
            if (!mHolding) return;
            if (mSipSession != null) {
                mSipSession.changeCall(createContinueSessionDescription());
            }
            mHolding = false;
        } catch (Throwable e) {
            throwSipException(e);
        }
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
        Log.d(TAG, " ~~~~~~~~~~~   start media: localPort=" + localPort
                + ", peer=" + peerMediaAddress + ":" + peerMediaPort);
    }

    public void stopCall() {
        Log.d(TAG, "stop audiocall");
        if (mRtpSession != null) {
            mRtpSession.stop();
            if (mMediaSocket != null) mMediaSocket.close();
            mMediaSocket = null;
        }
        setSpeakerMode();
    }

    public void sendDtmf() {
        if (mSipSession == null) return;
        if (SipSessionState.IN_CALL == getState()) {
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

    private String getLocalIp() {
        try {
            return mSipSession.getLocalIp();
        } catch (RemoteException e) {
            // FIXME
            return "127.0.0.1";
        }
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

    private void throwSipException(Throwable throwable) throws SipException {
        if (throwable instanceof SipException) {
            throw (SipException) throwable;
        } else {
            throw new SipException("", throwable);
        }
    }

    private SipProfile getPeerProfile(ISipSession session) {
        try {
            return session.getPeerProfile();
        } catch (RemoteException e) {
            return null;
        }
    }
}
