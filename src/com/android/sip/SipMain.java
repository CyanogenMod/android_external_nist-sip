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

import com.android.sip.media.AudioStream;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.sip.SdpSessionDescription;
import android.net.sip.SessionDescription;
import android.net.sip.SipProfile;
import android.net.sip.SipSession;
import android.net.sip.SipSessionLayer;
import android.net.sip.SipSessionListener;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceClickListener;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import gov.nist.javax.sdp.fields.SDPKeywords;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.ParseException;
import javax.sdp.SdpException;
import javax.sip.SipException;

/**
 */
public class SipMain extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final String TAG = SipMain.class.getSimpleName();
    private static final int MENU_REGISTER = Menu.FIRST;
    private static final int MENU_CALL = Menu.FIRST + 1;
    private static final int MENU_HANGUP = Menu.FIRST + 2;

    private Preference mCallStatus;
    private EditTextPreference mPeerUri;
    private EditTextPreference mServerUri;
    private EditTextPreference mPassword;
    private EditTextPreference mDisplayName;
    private Preference mMyIp;

    private SipProfile mLocalProfile;
    private SipSessionLayer mSipSessionLayer;
    private SipSession mSipSession;
    private SipSession mSipCallSession;
    private AudioStream mAudio;
    private Ringtone mRingtone;
    private RingbackTonePlayer mRingbackTonePlayer;
    private DatagramSocket mMediaSocket;
    private boolean mHolding = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.dev_pref);

        mCallStatus = getPreferenceScreen().findPreference("call_status");
        mPeerUri = setupEditTextPreference("peer");
        mServerUri = setupEditTextPreference("server_address");
        mPassword = (EditTextPreference)
                getPreferenceScreen().findPreference("password");
        mDisplayName = setupEditTextPreference("display_name");
        mMyIp = getPreferenceScreen().findPreference("my_ip");

        mMyIp.setSummary(getLocalIp());
        mMyIp.setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        // for testing convenience: copy my IP to server address
                        if (TextUtils.isEmpty(mServerUri.getText())) {
                            String myIp = mMyIp.getSummary().toString();
                            String uri = "test@" + myIp + ":5060";
                            mServerUri.setText(uri);
                            mServerUri.setSummary(uri);
                        }
                        return true;
                    }
                });

        mCallStatus.setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        actOnCallStatus();
                        return true;
                    }
                });

        setCallStatus();
    }

    private EditTextPreference setupEditTextPreference(String key) {
        EditTextPreference preference = (EditTextPreference)
                getPreferenceScreen().findPreference(key);
        preference.setOnPreferenceChangeListener(this);
        preference.setSummary(preference.getText());
        return preference;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mSipSessionLayer != null) {
            mSipSessionLayer.close();
            mSipSessionLayer = null;
            mSipSession = null;
        }
        stopAudioCall();
    }

    public boolean onPreferenceChange(Preference pref, Object newValue) {
        String value = (String) newValue;
        pref.setSummary((value == null) ? "" : value.trim());
        return true;
    }

    private String getText(EditTextPreference preference) {
        CharSequence text = preference.getText();
        return ((text == null) ? "" : text.toString());
    }

    private SipProfile createLocalSipProfile() {
        try {
            if (mLocalProfile == null) {
                mLocalProfile = new SipProfile.Builder(getServerUri())
                        .setPassword(getText(mPassword))
                        .setDisplayName(getText(mDisplayName))
                        .build();
            }
            return mLocalProfile;
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private SipProfile createPeerSipProfile() {
        try {
            return new SipProfile.Builder(getPeerUri()).build();
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private void setCallStatus() {
        runOnUiThread(new Runnable() {
            public void run() {
                mCallStatus.setSummary(getCallStatus(getActiveSession()));
            }
        });
    }

    private SipSession getActiveSession() {
        return ((mSipCallSession == null) ? mSipSession
                                          : mSipCallSession);
    }
    private class RingbackTonePlayer extends Thread {
        // The tone state
        private static final int TONE_OFF = 0;
        private static final int TONE_ON = 1;
        private static final int TONE_STOPPED = 2;
        private static final int TONE_TIMEOUT_BUFFER = 20;

        // The tone volume relative to other sounds in the stream
        private static final int TONE_RELATIVE_VOLUME_HIPRI = 80;
        private static final int TONE_RELATIVE_VOLUME_LOPRI = 50;

        private int mState = TONE_OFF;

        @Override
        public void run() {
            int toneType = ToneGenerator.TONE_SUP_RINGTONE;
            int toneVolume = TONE_RELATIVE_VOLUME_HIPRI;
             // Call ring back tone is stopped by stopTone() method
            int toneLengthMillis = Integer.MAX_VALUE - TONE_TIMEOUT_BUFFER;
            ToneGenerator toneGenerator;
            try {
                toneGenerator = new ToneGenerator(
                        AudioManager.STREAM_MUSIC, toneVolume);
            } catch (RuntimeException e) {
                Log.w(TAG, "Exception caught while creating ToneGenerator: " + e);
                toneGenerator = null;
            }
            synchronized (this) {
                if (mState != TONE_STOPPED) {
                    mState = TONE_ON;
                    toneGenerator.startTone(toneType);
                    try {
                        wait(toneLengthMillis + TONE_TIMEOUT_BUFFER);
                    } catch  (InterruptedException e) {
                        Log.w(TAG, "RingbackTonePlayer stopped: " + e);
                    }
                    toneGenerator.stopTone();
                }
                toneGenerator.release();
                mState = TONE_OFF;
            }
        }

        public void stopTone() {
            synchronized (this) {
                if (mState == TONE_ON) {
                    notify();
                }
                mState = TONE_STOPPED;
            }
        }
    }

    private SipSessionListener createSipSessionListener() {
        return new SipSessionListener() {

            public void onRinging(
                    SipSession session, byte[] sessionDescription) {
                startRinging();
                try {
                    SdpSessionDescription sd =
                            new SdpSessionDescription(sessionDescription);
                    Log.v(TAG, "sip call ringing: " + session + ": " + sd);
                } catch (SdpException e) {
                    Log.e(TAG, "createSessionDescription()", e);
                }
                setCallStatus();
            }

            public void onRingingBack(SipSession session) {
                Log.v(TAG, "sip call ringing back: " + session);
                startRingbackPlayer();
                setCallStatus();
            }

            public void onCallEstablished(
                    SipSession session, byte[] sessionDescription) {
                try {
                    SdpSessionDescription sd =
                            new SdpSessionDescription(sessionDescription);
                    Log.v(TAG, "sip call established: " + session + ": " + sd);
                    stopRingbackPlayer();
                    startAudioCall(sd);
                    stopRinging();
                } catch (SdpException e) {
                    Log.e(TAG, "createSessionDescription()", e);
                }
                mSipCallSession = session;
                setCallStatus();
            }

            public void onCallEnded(SipSession session) {
                Log.v(TAG, "sip call ended: " + session);
                stopRingbackPlayer();
                stopAudioCall();
                mSipCallSession = null;
                mHolding = false;
                setCallStatus();
                stopRinging();
            }

            public void onCallBusy(SipSession session) {
                Log.v(TAG, "sip call busy: " + session);
                setCallStatus();
            }

            public void onCallChanged(
                    SipSession session, byte[] sessionDescription) {
                String message = new String(sessionDescription);
                Log.v(TAG, "sip call " + message + ": " + session);
                mHolding = !mHolding;
                setCallStatus();
            }

            public void onError(SipSession session, Throwable e) {
                Log.v(TAG, "sip session error: " + e);
                mHolding = false;
                setCallStatus();
                stopRingbackPlayer();
                stopRinging();
            }

            public void onRegistrationDone(SipSession session) {
                Log.v(TAG, "sip registration done: " + session);
                setCallStatus();
            }

            public void onRegistrationFailed(SipSession session) {
                Log.v(TAG, "sip registration failed: " + session);
                setCallStatus();
            }

            public void onRegistrationTimeout(SipSession session) {
                Log.v(TAG, "sip registration timed out: " + session);
                setCallStatus();
            }
        };
    }

    private SipSession createSipSession() {
        try {
            return mSipSessionLayer.createSipSession(createLocalSipProfile(),
                    createSipSessionListener());
        } catch (SipException e) {
            // TODO: toast
            Log.e(TAG, "createSipSession()", e);
            return null;
        }
    }

    private void setupSipStack() throws SipException {
        if (mSipSession == null) {
            mSipSessionLayer = new SipSessionLayer();
            mSipSessionLayer.open(getLocalIp());
            mSipSession = createSipSession();
        }
    }

    private void register() {
        try {
            setupSipStack();
            mSipSession.register();
            setCallStatus();
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "register()", e);
        }
    }

    private void makeCall() {
        try {
            mSipSession.makeCall(createPeerSipProfile(),
                    getSdpSampleBuilder().build());
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "makeCall()", e);
        }
    }

    private void endCall() {
        try {
            getActiveSession().endCall();
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "endCall()", e);
        }
    }

    private void holdOrEndCall() {
        SipSession session = getActiveSession();
        try {
            if (Math.random() > 0.4) {
                session.changeCall(getHoldSdp());
                mHolding = true;
            } else {
                session.endCall();
            }
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "holdOrEndCall()", e);
        }
    }

    private void answerOrEndCall() {
        SipSession session = getActiveSession();
        try {
            if (Math.random() > 0) {
                session.answerCall(getSdpSampleBuilder().build());
            } else {
                session.endCall();
            }
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "answerOrEndCall()", e);
        }
    }

    private void continueCall() {
        SipSession session = getActiveSession();
        try {
            session.changeCall(getContinueSdp());
            mHolding = false;
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "continueCall()", e);
        }
    }

    private SdpSessionDescription.Builder getSdpSampleBuilder() {
        String localIp = getLocalIp();
        SdpSessionDescription.Builder sdpBuilder;
        try {
            sdpBuilder = new SdpSessionDescription.Builder("SIP Call")
                    .setOrigin(mLocalProfile,  (long)Math.random() * 10000000L,
                            (long)Math.random() * 10000000L, SDPKeywords.IN,
                            SDPKeywords.IPV4, localIp)
                    .setConnectionInfo(SDPKeywords.IN, SDPKeywords.IPV4, localIp)
                    .addMedia("audio", getLocalMediaPort(), 1, "RTP/AVP", 8)
                    .addMediaAttribute("rtpmap", "8 PCMA/8000")
                    .addMediaAttribute("ptime", "20");
        } catch (SdpException e) {
            throw new RuntimeException(e);
        }
        return sdpBuilder;
    }

    private SessionDescription getHoldSdp() {
        try {
            SdpSessionDescription.Builder sdpBuilder = getSdpSampleBuilder();
            sdpBuilder.addMediaAttribute("sendonly", (String)null);
            return sdpBuilder.build();
        } catch (SdpException e) {
            throw new RuntimeException(e);
        }
    }

    private SessionDescription getContinueSdp() {
        return getSdpSampleBuilder().build();
    }

    private void setAllPreferencesEnabled(final boolean enabled) {
        runOnUiThread(new Runnable() {
            public void run() {
                for (Preference preference : allPreferences()) {
                    preference.setEnabled(enabled);
                }
            }
        });
    }

    private void stopRinging() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        v.cancel();
        if (mRingtone != null) mRingtone.stop();
    }

    private void startRingbackPlayer() {
        mRingbackTonePlayer = new RingbackTonePlayer();
        mRingbackTonePlayer.start();
    }

    private void stopRingbackPlayer() {
        mRingbackTonePlayer.stopTone();
    }

    private void startRinging() {
        long[] vibratePattern = {0, 1000, 1000};
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        v.vibrate(vibratePattern, 1);
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am.getStreamVolume(AudioManager.STREAM_RING) > 0) {
            String sRingtone = Settings.System.DEFAULT_RINGTONE_URI.toString();
            mRingtone = RingtoneManager.getRingtone(this, Uri.parse(sRingtone));
            mRingtone.play();
        }
    }

    private void setInCallMode() {
        AudioManager am =
                (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_IN_CALL);
    }

    private void setSpeakerMode() {
        AudioManager am =
                (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        am.setMode(AudioManager.MODE_NORMAL);
    }

    private void startAudioCall(SdpSessionDescription sd) {
        String peerMediaAddress = sd.getPeerMediaAddress();
        // TODO: handle multiple media fields
        int peerMediaPort = sd.getPeerMediaPort();
        Log.i(TAG, "start audiocall " + peerMediaAddress + ":" + peerMediaPort);

        setAllPreferencesEnabled(false);

        int localPort = getLocalMediaPort();
        int sampleRate = 8000;
        int frameSize = sampleRate / 50; // 160
        try {
            // TODO: get sample rate from sdp
            mAudio = new AudioStream(sampleRate, sampleRate, mMediaSocket,
                    peerMediaAddress, peerMediaPort);
            mAudio.start();
            setInCallMode();
        } catch (Exception e) {
            Log.e(TAG, "call()", e);
        }
        Log.v(TAG, " ~~~~~~~~~~~   start media: localPort=" + localPort
                + ", peer=" + peerMediaAddress + ":" + peerMediaPort);
    }

    private void stopAudioCall() {
        Log.i(TAG, "stop audiocall");
        setSpeakerMode();
        if (mAudio != null) {
            mAudio.stop();
            mMediaSocket = null;
        }
        setAllPreferencesEnabled(true);
    }

    private Preference[] allPreferences() {
        return new Preference[] {
            mCallStatus, mPeerUri, mServerUri, mPassword, mDisplayName, mMyIp
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_REGISTER, 0, R.string.menu_register);
        menu.add(0, MENU_CALL, 0, R.string.menu_call);
        menu.add(0, MENU_HANGUP, 0, R.string.menu_hangup);
        return true;
    }

    @Override
    public synchronized boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_REGISTER:
                register();
                return true;

            case MENU_CALL:
                makeCall();
                return true;

            case MENU_HANGUP:
                endCall();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String getPeerUri() {
        return getText(mPeerUri);
    }

    private String getServerUri() {
        return getText(mServerUri);
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
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("www.google.com"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            Log.w(TAG, "getLocalIp(): " + e);
            return "127.0.0.1";
        }
    }

    private void setValue(EditTextPreference pref, String value) {
        pref.setSummary((value == null) ? "" : value.trim());
    }

    private void setSummary(Preference pref, int fieldNameId, String v) {
        setSummary(pref, fieldNameId, v, true);
    }

    private void setSummary(Preference pref, int fieldNameId, String v,
            boolean required) {
        String formatString = required
                ? getString(R.string.field_not_set)
                : getString(R.string.field_not_set_optional);
        pref.setSummary(TextUtils.isEmpty(v)
                ? String.format(formatString, getString(fieldNameId))
                : v);
    }

    private String getCallStatus(SipSession s) {
        if (s == null) return "Uninitialized";
        switch (s.getState()) {
        case REGISTERING:
            return "Registering...";
        case READY_FOR_CALL:
            return "Ready for call";
        case INCOMING_CALL:
            return "Ringing...";
        case INCOMING_CALL_ANSWERING:
            return "Answering...";
        case OUTGOING_CALL:
            return "Calling...";
        case OUTGOING_CALL_RING_BACK:
            return "Ringing back...";
        case OUTGOING_CALL_CANCELING:
            return "Cancelling...";
        case IN_CALL:
            return (mHolding ? "On hold" : "Established");
        case IN_CALL_CHANGING:
            return "Changing session...";
        case IN_CALL_ANSWERING:
            return "Changing session answering...";
        default:
            return "Unknown";
        }
    }

    private void actOnCallStatus() {
        SipSession activeSession = getActiveSession();
        if (activeSession == null) {
            Log.v(TAG, "actOnCallStatus(), session is null");
            register();
            return;
        }
        Log.v(TAG, "actOnCallStatus(), status=" + activeSession.getState());
        switch (activeSession.getState()) {
        case READY_FOR_CALL:
            makeCall();
            break;
        case INCOMING_CALL:
            answerOrEndCall();
            break;
        case OUTGOING_CALL_RING_BACK:
        case OUTGOING_CALL:
        case IN_CALL_CHANGING:
            endCall();
            break;
        case IN_CALL:
            if (!mHolding) {
                holdOrEndCall();
            } else {
                continueCall();
            }
            break;
        case OUTGOING_CALL_CANCELING:
        case REGISTERING:
        case INCOMING_CALL_ANSWERING:
        default:
            // do nothing
            break;
        }
        setCallStatus();
    }
}
