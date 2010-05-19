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

import com.android.settings.sip.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.sip.SipProfile;
import android.net.sip.SipAudioCall;
import android.net.sip.SipManager;
import android.net.sip.SipSessionState;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import javax.sip.SipException;

/**
 */
public class SipCallUi extends Activity implements OnClickListener {
    private static final String TAG = SipCallUi.class.getSimpleName();

    private TextView mCallerBox;
    private TextView mMyIp;
    private TextView mCallStatus;
    private Button mEndButton;
    private Button mMuteButton;
    private Button mHoldButton;
    private Button mDtmfButton;
    private Button mModeButton;

    private SipManager mSipManager;

    private SipProfile mLocalProfile;
    private SipAudioCall mAudioCall;

    private MyDialog mDialog;
    private Throwable mError;
    private boolean mChanged;
    private boolean mSpeakerMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.call_ui);
        mCallerBox = (TextView) findViewById(R.id.caller);
        mCallStatus = (TextView) findViewById(R.id.call_status);
        mMyIp = (TextView) findViewById(R.id.local_ip);
        mEndButton = (Button) findViewById(R.id.hang_up_btn);
        mMuteButton = (Button) findViewById(R.id.mute_btn);
        mHoldButton = (Button) findViewById(R.id.hold_btn);
        mDtmfButton = (Button) findViewById(R.id.dtmf_btn);
        mModeButton = (Button) findViewById(R.id.mode_btn);

        ((TextView) findViewById(R.id.local_ip_title)).setText("Local IP");
        ((TextView) findViewById(R.id.call_status_title)).setText("Call status");
        mEndButton.setText("End call");
        mMuteButton.setText("Mute");
        mHoldButton.setText("Hold");
        mDtmfButton.setText("DTMF 1");
        mModeButton.setText("Speaker mode");
        mCallerBox.setText("...");

        mEndButton.setOnClickListener(this);
        mMuteButton.setOnClickListener(this);
        mHoldButton.setOnClickListener(this);
        mDtmfButton.setOnClickListener(this);
        mModeButton.setOnClickListener(this);

        setCallStatus();

        final Intent intent = getIntent();
        setIntent(null);
        new Thread(new Runnable() {
            public void run() {
                setText(mMyIp, getLocalIp());

                mSipManager = SipManager.getInstance(SipCallUi.this);
                receiveCall(intent, "thread");
            }
        }).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.v(TAG, " onNewIntent(): " + intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        receiveCall(getIntent(), "onResume()");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeAudioCall();
    }

    private synchronized void receiveCall(Intent intent, String msg) {
        Log.v(TAG, msg + ": receiveCall(): any call comes in? " + intent);
        if (SipManager.isIncomingCallIntent(intent)) {
            createSipAudioCall(intent);
            setIntent(null);
        }
    }

    private void createSipAudioCall(Intent intent) {
        // TODO: what happens if another call is going
        try {
            Log.v(TAG, "create SipAudioCall");
            mAudioCall = mSipManager.takeAudioCall(this, intent,
                    createListener());
            if (mAudioCall == null) {
                throw new SipException("no session to handle audio call");
            }
        } catch (SipException e) {
            setCallStatus(e);
        }
    }

    private void makeAudioCall() throws Exception {
        // TODO
        SipProfile localProfile = null;
        SipProfile peerProfile = null;
        if ((mAudioCall == null) || mChanged) {
            closeAudioCall();
            mAudioCall = mSipManager.makeAudioCall(this, localProfile,
                    peerProfile, createListener());
            Log.v(TAG, "info changed; recreate AudioCall isntance");
        }
    }

    private void setCallStatus(Throwable e) {
        mError = e;
        setCallStatus();
    }

    private void setCallStatus() {
        setText(mCallStatus, getCallStatus());
        mError = null;
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
                Log.v(TAG, "onChanged(): " + call + " <--> " + mAudioCall);
                if (mAudioCall != call) return;
                setCallStatus();
            }

            public void onCalling(SipAudioCall call) {
                if (mAudioCall != call) return;
                setCallStatus();
            }

            public void onRinging(SipAudioCall call, SipProfile caller) {
                Log.v(TAG, "onRinging(): " + call + " <--> " + mAudioCall);
                if (mAudioCall != null) return;
                showCallNotificationDialog(caller);
                setCallStatus();
            }

            public void onCallEstablished(SipAudioCall call) {
                Log.v(TAG, "onCallEstablished(): " + call + " <--> " + mAudioCall);
                if (mAudioCall != call) return;
                setCallStatus();
                setText(mHoldButton, (isOnHold() ? "Unhold": "Hold"));
            }

            public void onCallEnded(SipAudioCall call) {
                Log.v(TAG, "onCallEnded(): " + call + " <--> " + mAudioCall);
                if (mAudioCall != call) return;
                if (mDialog != null) {
                    runOnUiThread(new Runnable() {
                        public void run() {
                            dismissDialog(mDialog.getId());
                        }
                    });
                }
                setCallStatus();
                mAudioCall = null;
                setAllButtonsEnabled(false);
                setText(mCallerBox, "...");
            }

            public void onError(SipAudioCall call, String errorMessage) {
                Log.v(TAG, "onError(): " + call + " <--> " + mAudioCall);
                if (mAudioCall != call) return;
                mError = new SipException(errorMessage);
                setCallStatus();
                mAudioCall = null;
                setAllButtonsEnabled(false);
                setText(mCallerBox, "...");
            }
        };
    }

    private void closeAudioCall() {
        if (mAudioCall != null) mAudioCall.close();
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

    private void answerCall() {
        try {
            mAudioCall.answerCall();
        } catch (SipException e) {
            Log.e(TAG, "answerCall()", e);
            setCallStatus(e);
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

    private void sendDtmf() {
        mAudioCall.sendDtmf(1);
    }

    private void setSpeakerMode() {
        mAudioCall.setSpeakerMode();
    }

    private void setInCallMode() {
        mAudioCall.setInCallMode();
    }

    public synchronized void onClick(View v) {
        if (mEndButton == v) {
            endCall();
        } else if (mModeButton == v) {
            mSpeakerMode = !mSpeakerMode;
            if (mSpeakerMode) {
                setSpeakerMode();
                mModeButton.setText("Speaker OFF");
            } else {
                setInCallMode();
                mModeButton.setText("Speaker ON");
            }
        } else if (mDtmfButton == v) {
            sendDtmf();
        } else if (mMuteButton == v) {
            mAudioCall.toggleMute();
            mMuteButton.setText(mAudioCall.isMuted() ? "Unmute" : "Mute");
        } else if (mHoldButton == v) {
            if (isOnHold()) {
                continueCall();
            } else {
                holdCall();
            }
        }
    }

    private SipSessionState getCallState() {
        if (mAudioCall == null) return SipSessionState.READY_TO_CALL;
        return mAudioCall.getState();
    }

    private String getCallStatus() {
        if (mError != null) return mError.getMessage();
        if (mAudioCall == null) return "Ready to call";
        switch (getCallState()) {
        case READY_TO_CALL:
            return "Call ended";
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
            return (isOnHold() ? "On hold" : "In call");
        default:
            return "Unknown";
        }
    }

    private void setText(final TextView view, final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                view.setText(text);
            }
        });
    }

    private void setAllButtonsEnabled(final boolean enabled) {
        runOnUiThread(new Runnable() {
            public void run() {
                for (Button button : allButtons()) {
                    button.setEnabled(enabled);
                }
            }
        });
    }

    private Button[] allButtons() {
        return new Button[] {
            mMuteButton, mEndButton, mHoldButton, mModeButton, mDtmfButton
        };
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
            if (TextUtils.isEmpty(name)) {
                name = mCaller.getUserName() + "@" + mCaller.getSipDomain();
            }
            return name;
        }

        public Dialog createDialog(int id) {
            if (id != getId()) return null;
            Log.d(TAG, "create call notification dialog");
            return new AlertDialog.Builder(SipCallUi.this)
                    .setTitle(getCallerName())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("Answer",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int w) {
                                    answerCall();
                                    mCallerBox.setText(getCallerName());
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
