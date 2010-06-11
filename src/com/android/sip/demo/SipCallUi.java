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

package com.android.sip.demo;

import com.android.settings.sip.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.sip.SipProfile;
import android.net.sip.SipAudioCall;
import android.net.sip.SipManager;
import android.net.sip.SipSessionState;
import android.os.Bundle;
import android.provider.CallLog;
import android.provider.CallLog.Calls;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Date;
import javax.sip.SipException;

/**
 */
public class SipCallUi extends Activity implements OnClickListener,
        SipAudioCall.Listener {
    private static final String TAG = SipCallUi.class.getSimpleName();

    private TextView mPeerBox;
    private TextView mMyIp;
    private TextView mCallStatus;
    private Button mEndButton;
    private Button mMuteButton;
    private Button mHoldButton;
    private Button mDtmfButton;
    private Button mModeButton;

    private SipManager mSipManager;
    private SipAudioCall mAudioCall;

    private MyDialog mDialog;
    private Throwable mError;
    private boolean mSpeakerMode;

    private long mCallTime = 0;
    private String mCallee;
    private String mCaller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.call_ui);
        mPeerBox = (TextView) findViewById(R.id.caller);
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
        mDtmfButton.setText("DTMF 1");
        mPeerBox.setText("...");

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
                if (SipManager.isIncomingCallIntent(intent)) {
                    receiveCall(intent);
                } else {
                    makeCall(intent);
                }
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableProximitySensor();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeAudioCall();
        disableProximitySensor();
    }

    private void receiveCall(Intent intent) {
        Log.v(TAG, "receiveCall(): any call comes in? " + intent);
        if (SipManager.isIncomingCallIntent(intent)) {
            createSipAudioCall(intent);
            setIntent(null);
        }
    }

    private void makeCall(Intent intent) {
        if ("call".equals(intent.getAction())) {
            SipProfile caller = (SipProfile)
                    intent.getParcelableExtra("caller");
            mCallee = intent.getStringExtra("callee");
            Log.v(TAG, "call from " + caller + " to " + mCallee);
            try {
                makeAudioCall(caller, mCallee);
                setText(mPeerBox, "Dialing " + mCallee + "...");
                setAllButtonsEnabled(true, true, false);
            } catch (Exception e) {
                Log.e(TAG, "makeCall()", e);
                setCallStatus(e);
            }
        }
    }

    private void createSipAudioCall(Intent intent) {
        // TODO: what happens if another call is going
        try {
            Log.v(TAG, "create SipAudioCall");
            mAudioCall = mSipManager.takeAudioCall(this, intent, this);
            if (mAudioCall == null) {
                throw new SipException("no session to handle audio call");
            }
        } catch (SipException e) {
            setCallStatus(e);
        }
    }

    private void makeAudioCall(SipProfile caller, String calleeUri)
            throws Exception {
        if (mAudioCall != null) {
            Log.v(TAG, "a call is ongoing; no new call is made");
            return;
        }
        SipProfile callee = new SipProfile.Builder(calleeUri).build();
        mAudioCall = mSipManager.makeAudioCall(this, caller, callee, this);
    }

    private void setCallStatus(Throwable e) {
        mError = e;
        setCallStatus();
    }

    private void setCallStatus() {
        setText(mCallStatus, getCallStatus());
        if (mError != null) {
            setText(mPeerBox, mError.getMessage());
        }
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

    public synchronized void onChanged(SipAudioCall call) {
        Log.v(TAG, "onChanged(): " + call + " <--> " + mAudioCall);
        if (mAudioCall != call) return;
        setCallStatus();
    }

    public synchronized void onCalling(SipAudioCall call) {
        if (mAudioCall != call) return;
        setCallStatus();
        showToast("Dialing...");
    }

    public synchronized void onRinging(SipAudioCall call, SipProfile caller) {
        Log.v(TAG, "onRinging(): " + call + " <--> " + mAudioCall);
        if (mAudioCall != null) return;
        mCaller = caller.getUserName() + '@' + caller.getSipDomain();
        showCallNotificationDialog(caller);
        setCallStatus();
    }

    public void onRingingBack(SipAudioCall call) {
    }

    public void onReadyToCall(SipAudioCall call) {
    }

    public synchronized void onCallEstablished(SipAudioCall call) {
        Log.v(TAG, "onCallEstablished(): " + call + " <--> " + mAudioCall);
        mCallTime = new Date().getTime();
        if (mAudioCall != call) return;
        setCallStatus();
        setText(mPeerBox, getDisplayName(call.getPeerProfile()));
        showToast("Call established");
        setAllButtonsEnabled(true, true, true);
    }

    public void onCallHeld(SipAudioCall call) {
    }

    public void onCallBusy(SipAudioCall call) {
    }

    public synchronized void onCallEnded(SipAudioCall call) {
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
        addCallLog();
        setAllButtonsEnabled(false, false, false);
        setText(mPeerBox, "...");
        showToast("Call ended");
        finish();
    }

    public synchronized void onError(SipAudioCall call, String errorMessage) {
        Log.v(TAG, "onError(): " + call + " <--> " + mAudioCall);
        if (mAudioCall != call) return;
        mError = new SipException(errorMessage);
        setCallStatus();
        mAudioCall = null;
        setAllButtonsEnabled(false, false, false);
        showToast("Call ended");
    }

    private void closeAudioCall() {
        if (mAudioCall != null) mAudioCall.close();
    }

    private void endCall() {
        try {
            mAudioCall.endCall();
            mSpeakerMode = false;
            addCallLog();
        } catch (SipException e) {
            Log.e(TAG, "endCall()", e);
            setCallStatus(e);
        }
    }

    private void addCallLog() {
        if (mCallee != null) addOutgoingCallLog(mCallee);
        if (mCaller != null) addIncomingCallLog(mCaller);
        mCaller = mCallee = null;
        mCallTime = 0;
    }

    private void addOutgoingCallLog(String callee) {
        addCallRecord(Calls.OUTGOING_TYPE, callee);
    }

    private void addIncomingCallLog(String caller) {
        addCallRecord((mCallTime != 0) ? Calls.INCOMING_TYPE:
                Calls.MISSED_TYPE, caller);
    }

    private void addCallRecord(int callType, String address) {
        long insertDate = new Date().getTime();
        ContentValues value = new ContentValues();
        value.put(Calls.NUMBER, address.substring(0, address.indexOf('@')));
        value.put(Calls.DATE, insertDate);
        value.put(Calls.DURATION,
                (mCallTime != 0) ? (insertDate - mCallTime)/1000 : 0);
        value.put(Calls.TYPE, callType);
        value.put(Calls.NEW, 0);
        try {
            getContentResolver().acquireProvider(
                    CallLog.AUTHORITY).insert(Calls.CONTENT_URI, value);
        } catch (android.os.RemoteException e) {
            Log.e(TAG, "cannot add calllog", e);
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
            } else {
                setInCallMode();
            }
        } else if (mDtmfButton == v) {
            sendDtmf();
        } else if (mMuteButton == v) {
            mAudioCall.toggleMute();
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
        if (mError != null) return "Error!";
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

    private void setAllButtonsEnabled(final boolean endButton,
            final boolean modeButton, final boolean others) {
        runOnUiThread(new Runnable() {
            public void run() {
                for (Button button : otherButtons()) {
                    button.setEnabled(others);
                }
                mEndButton.setEnabled(endButton);
                mModeButton.setEnabled(modeButton);
            }
        });
    }

    private Button[] otherButtons() {
        return new Button[] {
            mMuteButton, mHoldButton, mDtmfButton
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

    private String getDisplayName(SipProfile profile) {
        String name = profile.getDisplayName();
        if (TextUtils.isEmpty(name)) {
            name = profile.getUserName() + "@" + profile.getSipDomain();
        }
        return name;
    }

    private class CallNotificationDialog implements MyDialog {
        private SipProfile mCaller;

        CallNotificationDialog(SipProfile caller) {
            mCaller = caller;
        }

        public int getId() {
            return 0;
        }

        public Dialog createDialog(int id) {
            if (id != getId()) return null;
            Log.d(TAG, "create call notification dialog");
            return new AlertDialog.Builder(SipCallUi.this)
                    .setTitle(getDisplayName(mCaller))
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton("Answer",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int w) {
                                    answerCall();
                                    mPeerBox.setText(getDisplayName(mCaller));
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
            dialog.setTitle(getDisplayName(mCaller));
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

    private void showToast(final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(SipCallUi.this, message, Toast.LENGTH_SHORT)
                        .show();
            }
        });
    }

    private void enableProximitySensor() {
        SensorManager sensorManager = (SensorManager)
                getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

        sensorManager.registerListener(mProximityListener, sensor,
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void disableProximitySensor() {
        ((SensorManager) getSystemService(Context.SENSOR_SERVICE))
                .unregisterListener(mProximityListener);
    }

    private synchronized void onProximityChanged(float[] values) {
        if ((mAudioCall == null) || !mAudioCall.isInCall()) return;
        StringBuilder b = new StringBuilder();
        for (float f : values) {
            b.append(", " + f);
        }
        Log.v("Proximity", "onSensorChanged: " + b);
        boolean far = (values[0] > 1f);
        setAllButtonsEnabled(far, far, far);
    }

    SensorEventListener mProximityListener = new SensorEventListener() {
        public void onSensorChanged(SensorEvent event) {
            onProximityChanged(event.values);
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    };
}
