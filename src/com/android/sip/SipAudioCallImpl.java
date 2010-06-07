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

package com.android.sip;

import gov.nist.javax.sdp.fields.SDPKeywords;

import com.android.sip.rtp.AudioCodec;
import com.android.sip.rtp.AudioStream;
import com.android.sip.rtp.RtpSocket;

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
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.sip.SipSessionAdapter;
import android.net.sip.SipSessionState;
import android.os.RemoteException;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sdp.SdpException;
import javax.sip.SipException;

/**
 * Class that handles an audio call over SIP.
 */
public class SipAudioCallImpl extends SipSessionAdapter
        implements SipAudioCall {
    private static final String TAG = SipAudioCallImpl.class.getSimpleName();
    private static final boolean RELEASE_SOCKET = true;
    private static final boolean DONT_RELEASE_SOCKET = false;
    private static final String AUDIO = "audio";
    private static final int DTMF = 101;

    private Context mContext;
    private SipProfile mLocalProfile;
    private SipAudioCall.Listener mListener;
    private ISipSession mSipSession;
    private SdpSessionDescription mPeerSd;

    private AudioStream mRtpSession;
    private SdpSessionDescription.AudioCodec mCodec;
    private RtpSocket mMediaSocket;
    private long mSessionId = -1L; // SDP session ID
    private boolean mChangingSession = false;
    private boolean mInCall = false;
    private boolean mMuted = false;
    private boolean mHold = false;

    private boolean mRingbackToneEnabled = true;
    private boolean mRingtoneEnabled = true;
    private Ringtone mRingtone;
    private ToneGenerator mRingbackTone;

    private SipProfile mPendingCallRequest;

    public SipAudioCallImpl(Context context, SipProfile localProfile) {
        mContext = context;
        mLocalProfile = localProfile;
    }

    public synchronized void setListener(SipAudioCall.Listener listener) {
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

    public synchronized boolean isInCall() {
        return mInCall;
    }

    public synchronized boolean isOnHold() {
        return mHold;
    }

    public synchronized void close() {
        stopCall(RELEASE_SOCKET);
        stopRingbackTone();
        stopRinging();
        mSipSession = null;
        mInCall = false;
        mHold = false;
        mChangingSession = false;
        mSessionId = -1L;
    }

    public synchronized SipProfile getLocalProfile() {
        return mLocalProfile;
    }

    public synchronized SipProfile getPeerProfile() {
        try {
            return (mSipSession == null) ? null : mSipSession.getPeerProfile();
        } catch (RemoteException e) {
            return null;
        }
    }

    public synchronized SipSessionState getState() {
        if (mSipSession == null) return SipSessionState.READY_TO_CALL;
        try {
            return Enum.valueOf(SipSessionState.class, mSipSession.getState());
        } catch (RemoteException e) {
            return SipSessionState.REMOTE_ERROR;
        }
    }


    public synchronized ISipSession getSipSession() {
        return mSipSession;
    }

    @Override
    public synchronized void onCalling(ISipSession session) {
        Log.d(TAG, "calling... " + session);
        if (mListener != null) {
            try {
                mListener.onCalling(SipAudioCallImpl.this);
            } catch (Throwable t) {
                Log.e(TAG, "onCalling()", t);
            }
        }
    }

    @Override
    public synchronized void onRingingBack(ISipSession session) {
        Log.d(TAG, "sip call ringing back: " + session);
        if (!mInCall) startRingbackTone();
        if (mListener != null) {
            try {
                mListener.onRingingBack(SipAudioCallImpl.this);
            } catch (Throwable t) {
                Log.e(TAG, "onRingingBack()", t);
            }
        }
    }

    @Override
    public synchronized void onRinging(ISipSession session,
            SipProfile peerProfile, byte[] sessionDescription) {
        try {
            if ((mSipSession == null) || !mInCall
                    || !session.getCallId().equals(mSipSession.getCallId())) {
                // should not happen
                session.endCall();
                return;
            }

            // session changing request
            try {
                mPeerSd = new SdpSessionDescription(sessionDescription);
                answerCall();
            } catch (Throwable e) {
                Log.e(TAG, "onRinging()", e);
                session.endCall();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "onRinging()", e);
        }
    }

    @Override
    public synchronized void onCallEstablished(ISipSession session,
            byte[] sessionDescription) {
        stopRingbackTone();
        stopRinging();
        mChangingSession = false;
        try {
            SdpSessionDescription sd =
                    new SdpSessionDescription(sessionDescription);
            Log.d(TAG, "sip call established: " + session + ": " + sd);
            startCall(sd);
            mInCall = true;
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

    @Override
    public synchronized void onCallEnded(ISipSession session) {
        Log.d(TAG, "sip call ended: " + session);
        close();
        if (mListener != null) {
            try {
                mListener.onCallEnded(SipAudioCallImpl.this);
            } catch (Throwable t) {
                Log.e(TAG, "onCallEnded()", t);
            }
        }
    }

    @Override
    public synchronized void onCallBusy(ISipSession session) {
        Log.d(TAG, "sip call busy: " + session);
        mChangingSession = false;
        if (mListener != null) {
            try {
                mListener.onCallBusy(SipAudioCallImpl.this);
            } catch (Throwable t) {
                Log.e(TAG, "onCallBusy()", t);
            }
        }
    }

    @Override
    public synchronized void onCallChangeFailed(ISipSession session,
            String className, String message) {
        // TODO:
        mChangingSession = false;
    }

    @Override
    public synchronized void onError(ISipSession session, String className,
            String message) {
        Log.d(TAG, "sip session error: " + className + ": " + message);
        // don't stop RTP session on SIP error
        // TODO: what to do if call is on hold
        mSipSession = null;
        mChangingSession = false;
        if (mListener != null) {
            try {
                mListener.onError(SipAudioCallImpl.this,
                        className + ": " + message);
            } catch (Throwable t) {
                Log.e(TAG, "onError()", t);
            }
        }
    }

    public synchronized void attachCall(ISipSession session,
            SdpSessionDescription sdp) throws SipException {
        mSipSession = session;
        mPeerSd = sdp;
        try {
            session.setListener(this);
        } catch (Throwable e) {
            Log.e(TAG, "attachCall()", e);
            throwSipException(e);
        }
    }

    public synchronized void makeCall(SipProfile peerProfile,
            SipManager sipManager) throws SipException {
        try {
            mSipSession = sipManager.createSipSession(mLocalProfile, this);
            mSipSession.makeCall(peerProfile, createOfferSessionDescription());
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    public synchronized void endCall() throws SipException {
        try {
            stopRinging();
            mSipSession.endCall();
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    public synchronized void holdCall() throws SipException {
        try {
            if (mChangingSession) return;
            mChangingSession = true;
            mHold = true;
            mSipSession.changeCall(createHoldSessionDescription());
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    public synchronized void answerCall() throws SipException {
        try {
            stopRinging();
            mSipSession.answerCall(createAnswerSessionDescription());
        } catch (Throwable e) {
            Log.e(TAG, "answerCall()", e);
            throwSipException(e);
        }
    }

    public synchronized void continueCall() throws SipException {
        try {
            if (mChangingSession) return;
            mChangingSession = true;
            mHold = false;
            mSipSession.changeCall(createContinueSessionDescription());
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    private SessionDescription createOfferSessionDescription() {
        AudioCodec[] codecs = AudioCodec.getSystemSupportedCodecs();
        return createSdpBuilder(true, convert(codecs)).build();
    }

    private SessionDescription createAnswerSessionDescription() {
        try {
            // choose an acceptable media from mPeerSd to answer
            SdpSessionDescription.AudioCodec codec = getCodec(mPeerSd);
            SdpSessionDescription.Builder sdpBuilder =
                    createSdpBuilder(false, codec);
            if (mPeerSd.isSendOnly(AUDIO)) {
                sdpBuilder.addMediaAttribute(AUDIO, "recvonly", (String) null);
            } else if (mPeerSd.isReceiveOnly(AUDIO)) {
                sdpBuilder.addMediaAttribute(AUDIO, "sendonly", (String) null);
            }
            return sdpBuilder.build();
        } catch (SdpException e) {
            throw new RuntimeException(e);
        }
    }

    private SessionDescription createHoldSessionDescription() {
        try {
            return createSdpBuilder(false, mCodec)
                    .addMediaAttribute(AUDIO, "sendonly", (String) null)
                    .build();
        } catch (SdpException e) {
            throw new RuntimeException(e);
        }
    }

    private SessionDescription createContinueSessionDescription() {
        return createSdpBuilder(true, mCodec).build();
    }

    private String getMediaDescription(SdpSessionDescription.AudioCodec codec) {
        return String.format("%d %s/%d", codec.payloadType, codec.name,
                codec.sampleRate);
    }

    private long getSessionId() {
        if (mSessionId < 0) {
            mSessionId = System.currentTimeMillis();
        }
        return mSessionId;
    }

    private SdpSessionDescription.Builder createSdpBuilder(
            boolean addTelephoneEvent,
            SdpSessionDescription.AudioCodec... codecs) {
        String localIp = getLocalIp();
        SdpSessionDescription.Builder sdpBuilder;
        try {
            long sessionVersion = System.currentTimeMillis();
            sdpBuilder = new SdpSessionDescription.Builder("SIP Call")
                    .setOrigin(mLocalProfile, getSessionId(), sessionVersion,
                            SDPKeywords.IN, SDPKeywords.IPV4, localIp)
                    .setConnectionInfo(SDPKeywords.IN, SDPKeywords.IPV4,
                            localIp);
            List<Integer> codecIds = new ArrayList<Integer>();
            for (SdpSessionDescription.AudioCodec codec : codecs) {
                codecIds.add(codec.payloadType);
            }
            if (addTelephoneEvent) codecIds.add(DTMF);
            sdpBuilder.addMedia(AUDIO, getLocalMediaPort(), 1, "RTP/AVP",
                    codecIds.toArray(new Integer[codecIds.size()]));
            for (SdpSessionDescription.AudioCodec codec : codecs) {
                sdpBuilder.addMediaAttribute(AUDIO, "rtpmap",
                        getMediaDescription(codec));
            }
            if (addTelephoneEvent) {
                sdpBuilder.addMediaAttribute(AUDIO, "rtpmap",
                        DTMF + " telephone-event/8000");
            }
            // FIXME: deal with vbr codec
            sdpBuilder.addMediaAttribute(AUDIO, "ptime", "20");
        } catch (SdpException e) {
            throw new RuntimeException(e);
        }
        return sdpBuilder;
    }

    public synchronized void toggleMute() {
        if (mRtpSession != null) {
            if (mMuted) {
                mRtpSession.startSending();
            } else {
                mRtpSession.stopSending();
            }
            mMuted = !mMuted;
        }
    }

    public synchronized boolean isMuted() {
        return mMuted;
    }

    public synchronized void setInCallMode() {
        ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                .setSpeakerphoneOn(false);
    }

    public synchronized void setSpeakerMode() {
        ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                .setSpeakerphoneOn(true);
    }

    public synchronized void sendDtmf(int code) {
        if (mSipSession == null) return;
        if (SipSessionState.IN_CALL == getState()) {
            mRtpSession.sendDtmf(code);
        }
    }

    private SdpSessionDescription.AudioCodec getCodec(SdpSessionDescription sd) {
        HashMap<String, AudioCodec> acceptableCodecs =
                new HashMap<String, AudioCodec>();
        for (AudioCodec codec : AudioCodec.getSystemSupportedCodecs()) {
            acceptableCodecs.put(codec.name, codec);
        }
        for (SdpSessionDescription.AudioCodec codec : sd.getAudioCodecs()) {
            AudioCodec matchedCodec = acceptableCodecs.get(codec.name);
            if (matchedCodec != null) return codec;
        }
        Log.w(TAG, "no common codec is found, use PCM/0");
        return convert(AudioCodec.ULAW);
    }

    private AudioCodec convert(SdpSessionDescription.AudioCodec codec) {
        AudioCodec c = AudioCodec.getSystemSupportedCodec(codec.name);
        return ((c == null) ? AudioCodec.ULAW : c);
    }

    private SdpSessionDescription.AudioCodec convert(AudioCodec codec) {
        return new SdpSessionDescription.AudioCodec(codec.defaultType,
                codec.name, codec.sampleRate, codec.sampleCount);
    }

    private SdpSessionDescription.AudioCodec[] convert(AudioCodec[] codecs) {
        SdpSessionDescription.AudioCodec[] copies =
                new SdpSessionDescription.AudioCodec[codecs.length];
        for (int i = 0, len = codecs.length; i < len; i++) {
            copies[i] = convert(codecs[i]);
        }
        return copies;
    }

    private void startCall(SdpSessionDescription peerSd) {
        stopCall(DONT_RELEASE_SOCKET);

        mPeerSd = peerSd;
        String peerMediaAddress = peerSd.getPeerMediaAddress(AUDIO);
        // TODO: handle multiple media fields
        int peerMediaPort = peerSd.getPeerMediaPort(AUDIO);
        Log.i(TAG, "start audiocall " + peerMediaAddress + ":" + peerMediaPort);

        int localPort = getLocalMediaPort();
        int sampleRate = 8000;
        int frameSize = sampleRate / 50; // 160
        try {
            // TODO: get sample rate from sdp
            mMediaSocket.associate(InetAddress.getByName(peerMediaAddress),
                    peerMediaPort);
            mCodec = getCodec(peerSd);
            mRtpSession = new AudioStream(mMediaSocket);
            mRtpSession.setCodec(convert(mCodec), mCodec.payloadType);
            mRtpSession.setDtmf(DTMF);
            mRtpSession.prepare();
            Log.d(TAG, "start media: localPort=" + localPort + ", peer="
                    + peerMediaAddress + ":" + peerMediaPort);
            if (mHold) {
                Log.d(TAG, "   on hold");
                mRtpSession.stopSending();
                mRtpSession.stopReceiving();
            } else {
                if (peerSd.isSending(AUDIO)) {
                    Log.d(TAG, "   start receiving");
                    mRtpSession.startReceiving();
                }
                if (peerSd.isReceiving(AUDIO)) {
                    Log.d(TAG, "   start sending");
                    mRtpSession.startSending();
                }
            }
            setInCallMode();
        } catch (Exception e) {
            Log.e(TAG, "call()", e);
        }
    }

    private void stopCall(boolean releaseSocket) {
        Log.d(TAG, "stop audiocall");
        if (mRtpSession != null) {
            mRtpSession.close();
            if (releaseSocket) {
                mMediaSocket = null;
            }
        }
        setInCallMode();
    }

    private int getLocalMediaPort() {
        if (mMediaSocket != null) return mMediaSocket.getLocalPort();
        try {
            RtpSocket s = mMediaSocket =
                    new RtpSocket(InetAddress.getByName(getLocalIp()));
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

    public synchronized void setRingbackToneEnabled(boolean enabled) {
        mRingbackToneEnabled = enabled;
    }

    public synchronized void setRingtoneEnabled(boolean enabled) {
        mRingtoneEnabled = enabled;
    }

    private void startRingbackTone() {
        if (!mRingbackToneEnabled) return;
        if (mRingbackTone == null) {
            // The volume relative to other sounds in the stream
            int toneVolume = 80;
            mRingbackTone = new ToneGenerator(
                    AudioManager.STREAM_MUSIC, toneVolume);
        }
        mRingbackTone.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L);
    }

    private void stopRingbackTone() {
        if (mRingbackTone != null) {
            mRingbackTone.stopTone();
            mRingbackTone.release();
            mRingbackTone = null;
        }
    }

    private void startRinging() {
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

    private void stopRinging() {
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

    // TODO: fix listener callback deadlock
}
