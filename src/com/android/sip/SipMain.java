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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.sip.SipProfile;
import android.net.sip.SipSessionState;
import android.os.Bundle;
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
    private static final int MENU_REGISTER = Menu.FIRST;
    private static final int MENU_CALL = Menu.FIRST + 1;
    private static final int MENU_HANGUP = Menu.FIRST + 2;
    private static final int MENU_SEND_DTMF_1 = Menu.FIRST + 3;
    private static final int MENU_SPEAKER_MODE = Menu.FIRST + 4;

    private Preference mCallStatus;
    private EditTextPreference mPeerUri;
    private EditTextPreference mServerUri;
    private EditTextPreference mPassword;
    private EditTextPreference mDisplayName;
    private Preference mMyIp;

    private SipProfile mLocalProfile;
    private SipAudioCall mAudioCall;

    private MyDialog mDialog;
    private boolean mHolding;
    private Throwable mError;
    private boolean mChanged;
    private boolean mSpeakerMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.dev_pref);

        mCallStatus = getPreferenceScreen().findPreference("call_status");
        mPeerUri = setupEditTextPreference("peer");
        mServerUri = setupEditTextPreference("server_address");
        mPassword = (EditTextPreference)
                getPreferenceScreen().findPreference("password");
        mPassword.setOnPreferenceChangeListener(this);
        mDisplayName = setupEditTextPreference("display_name");
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

        new Thread(new Runnable() {
            public void run() {
                final String localIp = getLocalIp();
                runOnUiThread(new Runnable() {
                    public void run() {
                        mMyIp.setSummary(localIp);
                    }
                });
            }
        }).start();
    }

    private void createSipAudioCall() throws SipException {
        if ((mAudioCall == null) || mChanged) {
            if (mAudioCall != null) mAudioCall.close();
            mAudioCall = new SipAudioCall(this, createLocalSipProfile(),
                    createListener());
            mChanged = false;
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
        if (mAudioCall != null) mAudioCall.close();
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

    private SipProfile createLocalSipProfile() throws SipException {
        try {
            if ((mLocalProfile == null) || mChanged) {
                String serverUri = getText(mServerUri);
                if (TextUtils.isEmpty(serverUri)) {
                    throw new SipException("Server address missing");
                }
                mLocalProfile = new SipProfile.Builder(serverUri)
                        .setPassword(getText(mPassword))
                        .setDisplayName(getText(mDisplayName))
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
        return new SipAudioCall.Listener() {
            public void onCalling(SipAudioCall call) {
                setCallStatus();
            }

            public void onReadyForCall(SipAudioCall call) {
                setCallStatus();
            }

            public void onRinging(SipAudioCall call, SipProfile caller) {
                showCallNotificationDialog(caller);
                setCallStatus();
            }

            public void onRingingBack(SipAudioCall call) {
                setCallStatus();
            }

            public void onCallEstablished(SipAudioCall call) {
                setAllPreferencesEnabled(false);
                setCallStatus();
            }

            public void onCallEnded(SipAudioCall call) {
                setCallStatus();
                setAllPreferencesEnabled(true);
            }

            public void onCallBusy(SipAudioCall call) {
                setCallStatus();
            }

            public void onCallHeld(SipAudioCall call) {
                setCallStatus();
            }

            public void onError(SipAudioCall call, Throwable e) {
                mError = e;
                setCallStatus();
            }
        };
    }

    private void register() {
        try {
            createSipAudioCall();
            mAudioCall.register();
        } catch (SipException e) {
            Log.e(TAG, "makeCall()", e);
            setCallStatus(e);
        }
    }

    private void makeCall() {
        try {
            createSipAudioCall();
            mAudioCall.makeCall(createPeerSipProfile());
        } catch (SipException e) {
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
        try {
            if (Math.random() > 0.4) {
                mAudioCall.holdCall();
            } else {
                mAudioCall.endCall();
            }
        } catch (SipException e) {
            Log.e(TAG, "holdOrEndCall()", e);
            setCallStatus(e);
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

    private void continueCall() {
        try {
            mAudioCall.continueCall();
        } catch (SipException e) {
            Log.e(TAG, "continueCall()", e);
            setCallStatus(e);
        }
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
            mCallStatus, mPeerUri, mServerUri, mPassword, mDisplayName, mMyIp
        };
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        SipSessionState state = ((mAudioCall == null) || mChanged)
                ? SipSessionState.READY_FOR_CALL
                : mAudioCall.getState();

        Log.v(TAG, "actOnCallStatus(), status=" + state);
        menu.clear();
        switch (state) {
        case READY_FOR_CALL:
            menu.add(0, MENU_REGISTER, 0, R.string.menu_register);
            menu.add(0, MENU_CALL, 0, R.string.menu_call);
            break;
        case IN_CALL:
            menu.add(0, MENU_SPEAKER_MODE, 0, (mSpeakerMode ?
                    R.string.menu_incall_mode : R.string.menu_speaker_mode));
            menu.add(0, MENU_SEND_DTMF_1, 0, R.string.menu_send_dtmf);
            /* pass through */
        default:
            menu.add(0, MENU_HANGUP, 0, R.string.menu_hangup);
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
                    mAudioCall.setSpeakerMode();
                } else {
                    mAudioCall.setInCallMode();
                }
                return true;

            case MENU_SEND_DTMF_1:
                mAudioCall.sendDtmf();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String getPeerUri() {
        return getText(mPeerUri);
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

    private String getCallStatus() {
        if (mError != null) return mError.getMessage();
        if (mAudioCall == null) return "Ready to call (not registered)";
        switch (mAudioCall.getState()) {
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
        if ((mAudioCall == null) || mChanged) {
            register();
        } else {
            switch (mAudioCall.getState()) {
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
}
