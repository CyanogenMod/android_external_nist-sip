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
public class SipAudioCallImpl extends SipSessionAdapter
        implements SipAudioCall {
    private static final String TAG = SipAudioCallImpl.class.getSimpleName();
    private static final boolean RELEASE_SOCKET = true;
    private static final boolean DONT_RELEASE_SOCKET = false;
    private static final String AUDIO = "audio";

    private Context mContext;
    private SipProfile mLocalProfile;
    private SipAudioCall.Listener mListener;
    private ISipSession mSipSession;
    private SdpSessionDescription mPeerSd;

    private RtpSession mRtpSession;
    private DatagramSocket mMediaSocket;
    private boolean mChangingSession = false;
    private boolean mInCall = false;

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
        if (mPeerSd == null) return false;
        return (mPeerSd.isSendOnly(AUDIO) || mPeerSd.isReceiveOnly(AUDIO));
    }

    public synchronized void close() {
        stopCall(RELEASE_SOCKET);
        stopRingbackTone();
        stopRinging();
        mSipSession = null;
        mInCall = false;
        mChangingSession = false;
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
        if (mInCall) startRingbackTone();
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
            byte[] sessionDescription) throws SipException {
        mSipSession = session;
        try {
            mPeerSd = new SdpSessionDescription(sessionDescription);
            session.setListener(this);
        } catch (Throwable e) {
            Log.e(TAG, "attachCall()", e);
            throwSipException(e);
        }
    }

    public synchronized void makeCall(SipProfile peerProfile,
            ISipService sipService) throws SipException {
        try {
            mSipSession = sipService.createSession(mLocalProfile, this);
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
            mSipSession.changeCall(createContinueSessionDescription());
        } catch (Throwable e) {
            throwSipException(e);
        }
    }

    private SessionDescription createOfferSessionDescription() {
        RtpSession[] rtpSessions = RtpFactory.getSystemSupportedAudioSessions();
        return createSdpBuilder(rtpSessions).build();
    }

    private SessionDescription createAnswerSessionDescription() {
        try {
            // choose an acceptable media from mPeerSd to answer
            RtpSession rtpSession =
                    RtpFactory.createAudioSession(getCodecId(mPeerSd));
            SdpSessionDescription.Builder sdpBuilder =
                    createSdpBuilder(rtpSession);
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
            return createSdpBuilder(mRtpSession)
                    .addMediaAttribute(AUDIO, "sendonly", (String) null)
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
            sdpBuilder.addMedia(AUDIO, getLocalMediaPort(), 1, "RTP/AVP",
                    codecIds.toArray(new Integer[codecIds.size()]));
            for (RtpSession s : rtpSessions) {
                sdpBuilder.addMediaAttribute(AUDIO, "rtpmap",
                        getMediaDescription(s));
            }
            sdpBuilder.addMediaAttribute(AUDIO, "rtpmap",
                    "101 telephone-event/8000");
            // FIXME: deal with vbr codec
            sdpBuilder.addMediaAttribute(AUDIO, "ptime", "20");
        } catch (SdpException e) {
            throw new RuntimeException(e);
        }
        return sdpBuilder;
    }

    public synchronized void toggleMute() {
        if (mRtpSession != null) {
            mRtpSession.toggleMute();
        }
    }

    public synchronized boolean isMuted() {
        return ((mRtpSession != null) ? mRtpSession.isMuted() : false);
    }

    public synchronized void setInCallMode() {
        ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                .setMode(AudioManager.MODE_IN_CALL);
    }

    public synchronized void setSpeakerMode() {
        ((AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE))
                .setMode(AudioManager.MODE_NORMAL);
    }

    public synchronized void sendDtmf() {
        if (mSipSession == null) return;
        if (SipSessionState.IN_CALL == getState()) {
            mRtpSession.sendDtmf();
        }
    }

    private int getCodecId(SdpSessionDescription sd) {
        Set<Integer> acceptableFormats = new HashSet<Integer>();
        for (RtpSession session :
                RtpFactory.getSystemSupportedAudioSessions()) {
            acceptableFormats.add(session.getCodecId());
        }
        for (int id : sd.getMediaFormats(AUDIO)) {
            if (acceptableFormats.contains(id)) return id;
        }
        Log.w(TAG, "no common codec is found, use 0");
        return 0;
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
            mMediaSocket.connect(InetAddress.getByName(peerMediaAddress),
                    peerMediaPort);
            mRtpSession = RtpFactory.createAudioSession(
                    getCodecId(peerSd), sampleRate, mMediaSocket);
            if (!peerSd.isReceiveOnly(AUDIO)) mRtpSession.startReceiving();
            if (!peerSd.isSendOnly(AUDIO)) mRtpSession.startSending();
            setInCallMode();
        } catch (Exception e) {
            Log.e(TAG, "call()", e);
        }
        Log.d(TAG, " ~~~~~~~~~~~   start media: localPort=" + localPort
                + ", peer=" + peerMediaAddress + ":" + peerMediaPort);
    }

    private void stopCall(boolean releaseSocket) {
        Log.d(TAG, "stop audiocall");
        if (mRtpSession != null) {
            mRtpSession.stop();
            if (releaseSocket) {
                if (mMediaSocket != null) mMediaSocket.close();
                mMediaSocket = null;
            }
        }
        setSpeakerMode();
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
        setInCallMode();
        mRingbackTone.startTone(ToneGenerator.TONE_CDMA_LOW_PBX_L);
    }

    private void stopRingbackTone() {
        if (mRingbackTone != null) {
            mRingbackTone.stopTone();
            setSpeakerMode();
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
