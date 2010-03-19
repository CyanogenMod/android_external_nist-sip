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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
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
import java.text.ParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final int MENU_SEND_DTMF_1 = Menu.FIRST + 3;

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
    private SdpSessionDescription mOfferSd;

    private AudioStream mAudio;
    private Ringtone mRingtone;
    private RingbackTonePlayer mRingbackTonePlayer;
    private DatagramSocket mMediaSocket;
    private boolean mHolding = false;
    private MyDialog mDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.dev_pref);

        try {
            setupSipStack();
        } catch (SipException e) {
            Log.e(TAG, "register()", e);
            return;
        }

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
        stopRingbackPlayer();
        stopRinging();
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

    private void showCallNotificationDialog(SipProfile caller) {
        mDialog = new CallNotificationDialog(caller);
        runOnUiThread(new Runnable() {
            public void run() {
                showDialog(mDialog.getId());
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
                    Log.d(TAG, "notify to stop RingbackTonePlayer");
                    notify();
                }
                mState = TONE_STOPPED;
            }
        }
    }

    private SipSessionListener createSipSessionListener() {
        return new SipSessionListener() {
            public void onRinging(SipSession session, SipProfile caller,
                    byte[] sessionDescription) {
                startRinging();
                showCallNotificationDialog(caller);
                try {
                    SdpSessionDescription sd = mOfferSd =
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

    private void setupSipStack() throws SipException {
        if (mSipSessionLayer == null) {
            mSipSessionLayer = new SipSessionLayer();
        }
    }

    private void register() {
        try {
            if (mSipSession == null) {
                mSipSession = mSipSessionLayer.createSipSession(
                        createLocalSipProfile(), createSipSessionListener());
            }
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

    private void answerCall() {
        // TODO: choose an acceptable media from mOfferSd to answer
        try {
            getActiveSession().answerCall(getSdpSampleBuilder().build());
        } catch (SipException e) {
            // TODO: UI feedback
            Log.e(TAG, "answerCall()", e);
        }
    }
    private void answerOrEndCall() {
        if (Math.random() > 0) {
            answerCall();
        } else {
            endCall();
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
                    .addMedia("audio", getLocalMediaPort(), 1, "RTP/AVP", 0, 8, 101)
                    .addMediaAttribute("rtpmap", "0 PCMU/8000")
                    .addMediaAttribute("rtpmap", "8 PCMA/8000")
                    .addMediaAttribute("rtpmap", "101 telephone-event/8000")
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

    private void startRingbackPlayer() {
        mRingbackTonePlayer = new RingbackTonePlayer();
        mRingbackTonePlayer.start();
    }

    private void stopRingbackPlayer() {
        if (mRingbackTonePlayer != null) {
            mRingbackTonePlayer.stopTone();
            mRingbackTonePlayer = null;
        }
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

    private void stopRinging() {
        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        v.cancel();
        if (mRingtone != null) mRingtone.stop();
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

    private int getCodecId(SdpSessionDescription sd) {
        // FIXME: hardcoded ID's
        Set<Integer> acceptableFormats = new HashSet(Arrays.asList(0, 8));
        for (int id : sd.getMediaFormats()) {
            if (acceptableFormats.contains(id)) return id;
        }
        Log.w(TAG, "no common codec is found, use 0");
        return 0;
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
                    peerMediaAddress, peerMediaPort, getCodecId(sd));
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
        if (mAudio != null) {
            mAudio.stop();
            mMediaSocket = null;
        }
        setSpeakerMode();
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
        menu.add(0, MENU_SEND_DTMF_1, 0, R.string.menu_send_dtmf);
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

            case MENU_SEND_DTMF_1:
                SipSession activeSession = getActiveSession();
                switch (activeSession.getState()) {
                    case IN_CALL:
                        mAudio.sendDTMF();
                }
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
        return mSipSessionLayer.getLocalIp();
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

    @Override
    protected Dialog onCreateDialog (int id) {
        return ((mDialog == null) ? null : mDialog.createDialog(id));
    }

    @Override
    protected void onPrepareDialog (int id, Dialog dialog) {
        if (mDialog != null) mDialog.prepareDialog(id, dialog);
    }

    private class CallNotificationDialog implements MyDialog {
        private SipProfile mCaller;

        CallNotificationDialog(SipProfile caller) {
            mCaller = caller;
        }

        public int getId() {
            return 0;
        }

        private String getCallerName() {
            String name = mCaller.getDisplayName();
            if (TextUtils.isEmpty(name)) name = mCaller.getUri().toString();
            return name;
        }

        public Dialog createDialog(int id) {
            if (id != getId()) return null;
            Log.d(TAG, "create call notification dialog");
            return new AlertDialog.Builder(SipMain.this)
                    .setTitle(getCallerName())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("Answer",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int w) {
                                    answerCall();
                                    stopRinging();
                                }
                            })
                    .setNegativeButton("Hang up",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int w) {
                                    endCall();
                                    stopRinging();
                                }
                            })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                public void onCancel(DialogInterface dialog) {
                                    endCall();
                                    stopRinging();
                                }
                            })
                    .create();
        }

        public void prepareDialog(int id, Dialog dialog) {
            if (id != getId()) return;
            dialog.setTitle(getCallerName());
        }
    }

    private interface MyDialog {
        int getId();
        Dialog createDialog(int id);
        void prepareDialog(int id, Dialog dialog);
    }
}
