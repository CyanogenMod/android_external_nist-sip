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

package com.android.sip.demo;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.sip.ISipService;
import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipProfile;
import android.net.sip.SipAudioCall;
import android.net.sip.SipManager;
import android.net.sip.SipSessionAdapter;
import android.net.sip.SipSessionState;
import android.os.Bundle;
import android.os.RemoteException;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.Preference.OnPreferenceClickListener;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.ParseException;
import javax.sip.SipException;

/**
 */
public class SipMain extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final String TAG = SipMain.class.getSimpleName();
    private static final String INCOMING_CALL_ACTION = SipMain.class.getName();
    private static final int EXPIRY_TIME = 3600;
    private static final int MENU_REGISTER = Menu.FIRST;
    private static final int MENU_CALL = Menu.FIRST + 1;
    private static final int MENU_HANGUP = Menu.FIRST + 2;
    private static final int MENU_SEND_DTMF_1 = Menu.FIRST + 3;
    private static final int MENU_SPEAKER_MODE = Menu.FIRST + 4;
    private static final int MENU_MUTE = Menu.FIRST + 5;
    private static final int MENU_HOLD = Menu.FIRST + 6;

    private Preference mCallStatus;
    private EditTextPreference mPeerUri;
    private EditTextPreference mServerUri;
    private EditTextPreference mPassword;
    private EditTextPreference mDisplayName;
    private EditTextPreference mOutboundProxy;
    private Preference mMyIp;

    private SipProfile mLocalProfile;
    private SipAudioCall mAudioCall;
    private ISipSession mSipSession;

    private MyDialog mDialog;
    private Throwable mError;
    private boolean mChanged;
    private boolean mSpeakerMode;

    private ISipService mSipService;

    private BroadcastReceiver mIncomingCallReceiver =
            new IncomingCallReceiver();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(com.android.settings.sip.R.xml.dev_pref);

        mCallStatus = getPreferenceScreen().findPreference("call_status");
        mPeerUri = setupEditTextPreference("peer");
        mServerUri = setupEditTextPreference("server_address");
        mPassword = (EditTextPreference)
                getPreferenceScreen().findPreference("password");
        mPassword.setOnPreferenceChangeListener(this);
        mDisplayName = setupEditTextPreference("display_name");
        mOutboundProxy = setupEditTextPreference("proxy_address");
        mMyIp = getPreferenceScreen().findPreference("my_ip");
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

        final Intent intent = getIntent();
        new Thread(new Runnable() {
            public void run() {
                final String localIp = getLocalIp();
                runOnUiThread(new Runnable() {
                    public void run() {
                        mMyIp.setSummary(localIp);
                    }
                });

                // SipService must be obtained from non-main thread
                mSipService = SipManager.getSipService(SipMain.this);
                Log.v(TAG, "got SipService: " + mSipService);

                openToReceiveCalls();
                receiveCall();
            }
        }).start();

        registerIncomingCallReceiver(mIncomingCallReceiver);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.v(TAG, " onNewIntent(): " + intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        Log.v(TAG, " onResume()");
        super.onResume();
        receiveCall();
    }

    private synchronized void receiveCall() {
        // TODO: resolve offerSd to differnt apps
        Intent intent = getIntent();
        Log.v(TAG, "receiveCall(): any call comes in? " + intent);
        if ((mSipService != null) && SipManager.isIncomingCallIntent(intent)) {
            createSipAudioCall(intent);
            setIntent(null);
        }
    }

    private synchronized void openToReceiveCalls() {
        try {
            mSipService.openToReceiveCalls(createLocalProfile(),
                    INCOMING_CALL_ACTION);
        } catch (Exception e) {
            setCallStatus(e);
        }
    }

    private void createSipAudioCall(Intent intent) {
        // TODO: what happens if another call is going
        try {
            mAudioCall = SipManager.takeAudioCall(this, intent,
                    createListener());
            if (mAudioCall == null) {
                throw new SipException("no session to handle audio call");
            }
        } catch (SipException e) {
            setCallStatus(e);
        }
    }

    private void makeAudioCall() throws Exception {
        if ((mAudioCall == null) || mChanged) {
            if (mChanged) register();
            closeAudioCall();
            mAudioCall = SipManager.makeAudioCall(this, createLocalProfile(),
                    createPeerSipProfile(), createListener());
            Log.v(TAG, "info changed; recreate AudioCall isntance");
        }
    }

    private EditTextPreference setupEditTextPreference(String key) {
        EditTextPreference pref = (EditTextPreference)
                getPreferenceScreen().findPreference(key);
        pref.setOnPreferenceChangeListener(this);
        pref.setSummary(pref.getText());
        return pref;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mIncomingCallReceiver);
        closeAudioCall();
    }

    public boolean onPreferenceChange(Preference pref, Object newValue) {
        String value = (String) newValue;
        if (value == null) value = "";
        if (pref != mPassword) pref.setSummary(value);
        if ((pref != mPeerUri)
                && !value.equals(((EditTextPreference) pref).getText())) {
            mChanged = true;
        }
        return true;
    }

    private String getText(EditTextPreference preference) {
        CharSequence text = preference.getText();
        return ((text == null) ? "" : text.toString());
    }

    private SipProfile createLocalProfile() throws SipException {
        try {
            if ((mLocalProfile == null) || mChanged) {
                String serverUri = getText(mServerUri);
                if (TextUtils.isEmpty(serverUri)) {
                    throw new SipException("Server address missing");
                }
                mLocalProfile = new SipProfile.Builder(serverUri)
                        .setPassword(getText(mPassword))
                        .setDisplayName(getText(mDisplayName))
                        .setOutboundProxy(getText(mOutboundProxy))
                        .build();
            }
            return mLocalProfile;
        } catch (ParseException e) {
            throw new SipException("createLoalSipProfile", e);
        }
    }

    private SipProfile createPeerSipProfile() {
        try {
            return new SipProfile.Builder(getPeerUri()).build();
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    private void setCallStatus(Throwable e) {
        mError = e;
        setCallStatus();
    }

    private void setCallStatus() {
        runOnUiThread(new Runnable() {
            public void run() {
                mCallStatus.setSummary(getCallStatus());
                mError = null;
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

    private SipAudioCall.Listener createListener() {
        return new SipAudioCall.Adapter() {
            public void onChanged(SipAudioCall call) {
                setCallStatus();
            }

            public void onCalling(SipAudioCall call) {
                mSipSession = call.getSipSession();
                setCallStatus();
            }

            public void onRinging(SipAudioCall call, SipProfile caller) {
                mSipSession = call.getSipSession();
                showCallNotificationDialog(caller);
                setCallStatus();
            }

            public void onCallEstablished(SipAudioCall call) {
                setAllPreferencesEnabled(false);
                setCallStatus();
            }

            public void onCallEnded(SipAudioCall call) {
                if (mDialog != null) {

                    runOnUiThread(new Runnable() {
                        public void run() {
                            dismissDialog(mDialog.getId());
                        }
                    });
                }
                setCallStatus();
                mSipSession = null;
                setAllPreferencesEnabled(true);
            }

            public void onError(SipAudioCall call, String errorMessage) {
                mError = new SipException(errorMessage);
                setCallStatus();
                mSipSession = null;
                setAllPreferencesEnabled(true);
            }
        };
    }

    private void closeAudioCall() {
        if (mAudioCall != null) mAudioCall.close();
    }

    private ISipSessionListener createRegistrationListener() {
        return new SipSessionAdapter() {
            @Override
            public void onRegistrationDone(ISipSession session, int duration) {
                setCallStatus();
            }

            @Override
            public void onRegistrationFailed(ISipSession session,
                    String className, String message) {
                mError = new SipException("registration error: " + message);
                setCallStatus();
            }

            @Override
            public void onRegistrationTimeout(ISipSession session) {
                mError = new SipException("registration timed out");
                setCallStatus();
            }
        };
    }

    private void register() {
        try {
            if (mLocalProfile == null) createLocalProfile();
            if (mChanged) {
                ISipSession session = mSipService.createSession(mLocalProfile,
                    new SipSessionAdapter());
                session.unregister();
            }
            ISipSession session = mSipService.createSession(
                    createLocalProfile(), createRegistrationListener());
            session.register(EXPIRY_TIME);
            mSipSession = session;
            mChanged = false;
            setCallStatus();
        } catch (Exception e) {
            Log.e(TAG, "register()", e);
            setCallStatus(e);
        }
    }

    private void makeCall() {
        try {
            makeAudioCall();
        } catch (Exception e) {
            Log.e(TAG, "makeCall()", e);
            setCallStatus(e);
        }
    }

    private void endCall() {
        try {
            mAudioCall.endCall();
            mSpeakerMode = false;
        } catch (SipException e) {
            Log.e(TAG, "endCall()", e);
            setCallStatus(e);
        }
    }

    private void holdOrEndCall() {
        if (Math.random() > 0.4) {
            holdCall();
        } else {
            endCall();
        }
    }

    private void answerCall() {
        try {
            mAudioCall.answerCall();
        } catch (SipException e) {
            Log.e(TAG, "answerCall()", e);
            setCallStatus(e);
        }
    }

    private void answerOrEndCall() {
        if (Math.random() > 0) {
            answerCall();
        } else {
            endCall();
        }
    }

    private void holdCall() {
        try {
            mAudioCall.holdCall();
        } catch (SipException e) {
            Log.e(TAG, "holdCall()", e);
            setCallStatus(e);
        }
    }

    private void continueCall() {
        try {
            mAudioCall.continueCall();
        } catch (SipException e) {
            Log.e(TAG, "continueCall()", e);
            setCallStatus(e);
        }
    }

    private boolean isOnHold() {
        if (mAudioCall == null) return false;
        return mAudioCall.isOnHold();
    }

    private void toggleOnHold() {
        if (isOnHold()) {
            continueCall();
        } else {
            holdCall();
        }
    }

    private void toggleMute() {
        mAudioCall.toggleMute();
    }

    private void sendDtmf() {
        mAudioCall.sendDtmf(1);
    }

    private void setSpeakerMode() {
        mAudioCall.setSpeakerMode();
    }

    private void setInCallMode() {
        mAudioCall.setInCallMode();
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

    private Preference[] allPreferences() {
        return new Preference[] {
            mCallStatus, mPeerUri, mServerUri, mPassword, mDisplayName, mOutboundProxy, mMyIp
        };
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        SipSessionState state = ((mAudioCall == null) || mChanged)
                ? SipSessionState.READY_TO_CALL
                : getCallState();
        boolean muted = (mAudioCall == null) ? false : mAudioCall.isMuted();
        boolean onHold = isOnHold();

        Log.v(TAG, "onPrepareOptionsMenu(), status=" + state);
        menu.clear();
        switch (state) {
        case READY_TO_CALL:
            menu.add(0, MENU_REGISTER, 0, "Register");
            menu.add(0, MENU_CALL, 0, "Call");
            break;
        case IN_CALL:
            menu.add(0, MENU_SPEAKER_MODE, 0, (mSpeakerMode ?
                    "Speaker OFF" : "Speaker ON"));
            menu.add(0, MENU_SEND_DTMF_1, 0, "Send DTMF_1");
            menu.add(0, MENU_MUTE, 0,
                    (muted ? "Unmute" : "Mute"));
            menu.add(0, MENU_HOLD, 0,
                    (onHold ? "Unhold" : "Hold"));
            /* pass through */
        default:
            menu.add(0, MENU_HANGUP, 0, "Hang up");
        }
        return true;
    }

    @Override
    public synchronized boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_REGISTER:
                register();
                setCallStatus();
                return true;

            case MENU_CALL:
                makeCall();
                return true;

            case MENU_HANGUP:
                endCall();
                return true;

            case MENU_SPEAKER_MODE:
                mSpeakerMode = !mSpeakerMode;
                if (mSpeakerMode == true) {
                    setSpeakerMode();
                } else {
                    setInCallMode();
                }
                return true;

            case MENU_SEND_DTMF_1:
                sendDtmf();
                return true;

            case MENU_MUTE:
                toggleMute();
                return true;

            case MENU_HOLD:
                toggleOnHold();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String getPeerUri() {
        return getText(mPeerUri);
    }

    private SipSessionState getCallState() {
        if (mSipSession == null) return SipSessionState.READY_TO_CALL;
        try {
            String state = mSipSession.getState();
            return Enum.valueOf(SipSessionState.class, state);
        } catch (RemoteException e) {
            return SipSessionState.REMOTE_ERROR;
        }
    }

    private String getCallStatus() {
        if (mError != null) return mError.getMessage();
        if (mSipSession == null) return "Ready to call";
        switch (getCallState()) {
        case REGISTERING:
            return "Registering...";
        case READY_TO_CALL:
            return "Ready to call";
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
            return (isOnHold() ? "On hold" : "Established");
        default:
            return "Unknown";
        }
    }

    private void actOnCallStatus() {
        if ((mAudioCall == null) || mChanged) {
            register();
        } else {
            switch (getCallState()) {
            case READY_TO_CALL:
                makeCall();
                break;
            case INCOMING_CALL:
                answerOrEndCall();
                break;
            case OUTGOING_CALL_RING_BACK:
            case OUTGOING_CALL:
                endCall();
                break;
            case IN_CALL:
                if (isOnHold()) {
                    continueCall();
                } else {
                    holdOrEndCall();
                }
                break;
            case OUTGOING_CALL_CANCELING:
            case REGISTERING:
            case INCOMING_CALL_ANSWERING:
            default:
                // do nothing
                break;
            }
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
                                }
                            })
                    .setNegativeButton("Hang up",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int w) {
                                    endCall();
                                }
                            })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                                public void onCancel(DialogInterface dialog) {
                                    endCall();
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

    private String getLocalIp() {
        try {
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("192.168.1.1"), 80);
            return s.getLocalAddress().getHostAddress();
        } catch (IOException e) {
            Log.w(TAG, "getLocalIp(): " + e);
            return "127.0.0.1";
        }
    }

    private void registerIncomingCallReceiver(BroadcastReceiver r) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(INCOMING_CALL_ACTION);
        registerReceiver(r, filter);
    }

    private class IncomingCallReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.v(TAG, "onReceive(): incoming call: " + intent);
            setIntent(intent);
            receiveCall();
        }
    }
}
