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

import com.android.settings.sip.ProfileUtil;
import com.android.settings.sip.R;
import com.android.settings.sip.SipSettings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.sip.SipProfile;
import android.net.sip.SipManager;
import android.net.sip.SipRegistrationListener;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AutoCompleteTextView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 */
public class SipCallSetup extends Activity implements OnClickListener {
    public static final String INCOMING_CALL_ACTION =
            SipSettings.INCOMING_CALL_ACTION;
    private static final String TAG = SipCallSetup.class.getSimpleName();
    private static final String CALLEE_SET_PATH = "/sdcard/teseellacpis";

    private TextView mMyIp;
    private TextView mCallStatus;
    private AutoCompleteTextView mCallee;
    private Spinner mCaller;
    private Button mCallButton;
    private Button mRegisterButton;
    private Button mSettingsButton;

    private Set<String> mCalleeSet;

    private SipManager mSipManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.call_setup);
        mCallStatus = (TextView) findViewById(R.id.status);
        mMyIp = (TextView) findViewById(R.id.localip);
        mCallee = (AutoCompleteTextView) findViewById(R.id.callee);
        mCaller = (Spinner) findViewById(R.id.caller);
        mCallButton = (Button) findViewById(R.id.call_btn);
        mRegisterButton = (Button) findViewById(R.id.register_btn);
        mSettingsButton = (Button) findViewById(R.id.settings_btn);

        ((TextView) findViewById(R.id.localip_title)).setText("Local IP:");
        ((TextView) findViewById(R.id.status_title)).setText("Status:");
        ((TextView) findViewById(R.id.caller_title)).setText("Who am I");
        ((TextView) findViewById(R.id.callee_title)).setText("Who to call");
        mCallButton.setText("Call");
        mRegisterButton.setText("Register");
        mSettingsButton.setText("Settings");

        mCallButton.setOnClickListener(this);
        mRegisterButton.setOnClickListener(this);
        mSettingsButton.setOnClickListener(this);

        setCallStatus("...");

        new Thread(new Runnable() {
            public void run() {
                setText(mMyIp, getLocalIp());
                mSipManager = SipManager.getInstance(SipCallSetup.this);
            }
        }).start();
        getCalleeSet();
        setupCallees();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupCallers();
        setRegistrationListener(createRegistrationListener());
    }

    @Override
    protected void onPause() {
        super.onPause();
        setRegistrationListener(null);
    }

    private void setupCallers() {
        List<ProfileWrapper> profiles = getSipProfiles();
        Log.v(TAG, "profiles read: " + profiles.size());
        ArrayAdapter adapter = new ArrayAdapter(
                this, android.R.layout.simple_spinner_item, profiles);
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        ProfileWrapper profileWrapper = (ProfileWrapper)
                mCaller.getSelectedItem();
        mCaller.setAdapter(adapter);

        // restore selection
        if (profileWrapper == null) return;
        SipProfile profile = profileWrapper.mProfile;
        for (ProfileWrapper w : profiles) {
            if (w.mProfile.getUriString().equals(profile.getUriString())) {
                mCaller.setSelection(profiles.indexOf(w));
                break;
            }
        }
    }

    private void setupCallees() {
        String[] callees = new String[mCalleeSet.size()];
        Log.v(TAG, "callees: " + callees.length);
        mCalleeSet.toArray(callees);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line, callees);
        mCallee.setAdapter(adapter);
    }

    private List<ProfileWrapper> getSipProfiles() {
        List<SipProfile> profiles = ProfileUtil.retrieveSipProfiles(
                getFilesDir().getAbsolutePath());
        List<ProfileWrapper> wrappers =
                new ArrayList<ProfileWrapper>(profiles.size());
        for (SipProfile p : profiles) {
            wrappers.add(new ProfileWrapper(p));
        }
        return wrappers;
    }

    private void setCallStatus(Throwable e) {
        setCallStatus(e.toString());
    }

    private void setCallStatus(String message) {
        setText(mCallStatus, message);
    }

    public synchronized void onClick(View v) {
        if (mCallButton == v) {
            makeCall();
        } else if (mRegisterButton == v) {
            register();
        } else if (mSettingsButton == v) {
            String action = "android.net.sip.NOTIFY";
            startActivity(new Intent(action));
        }
    }

    private void makeCall() {
        String callee = mCallee.getText().toString();
        ProfileWrapper caller = (ProfileWrapper) mCaller.getSelectedItem();
        Log.v(TAG, "calling " + callee + " from " + caller);
        if (TextUtils.isEmpty(callee)) {
            showToast("No one to call to.");
            return;
        }
        if (caller == null) {
            showToast("Press 'Settings' to set up a SIP profile");
            return;
        }
        if (!callee.contains("@")) {
            callee += "@" + caller.mProfile.getSipDomain();
            mCallee.setText(callee);
        }
        updateCalleeSet(callee);
        call(caller.mProfile, callee);
    }

    private void call(SipProfile caller, String callee) {
        Intent intent = new Intent(this, SipCallUi.class);
        intent.setAction("call");
        intent.putExtra("caller", (Parcelable) caller);
        intent.putExtra("callee", callee);
        startActivity(intent);
    }

    private void register() {
        ProfileWrapper myself = (ProfileWrapper) mCaller.getSelectedItem();
        if (myself == null) return;
        try {
            if (mSipManager.isOpened(myself.mProfile.getUriString())) {
                mSipManager.register(myself.mProfile, 3600,
                        createRegistrationListener());
            } else {
                mSipManager.openToReceiveCalls(myself.mProfile,
                        INCOMING_CALL_ACTION, createRegistrationListener());
            }
        } catch (Exception e) {
            Log.e(TAG, "register()", e);
            setCallStatus(e);
        }
    }

    private void setRegistrationListener(SipRegistrationListener listener) {
        ProfileWrapper myself = (ProfileWrapper) mCaller.getSelectedItem();
        if (myself == null) return;
        try {
            mSipManager.setRegistrationListener(myself.mProfile.getUriString(),
                    listener);
        } catch (Exception e) {
            Log.e(TAG, "setRegistrationListener()", e);
        }
    }

    private SipRegistrationListener createRegistrationListener() {
        return new SipRegistrationListener() {
            public void onRegistrationDone(String uri, long expiryTime) {
                setCallStatus("Registered");
                showToast("Registration done");
            }

            public void onRegistrationFailed(String uri, String className,
                    String message) {
                setCallStatus("registration error: " + message);
                showToast("Registration failed");
            }

            public void onRegistering(String uri) {
                setCallStatus("Registering...");
                showToast("Registering...");
            }
        };
    }

    private void updateCalleeSet(String callee) {
        if (mCalleeSet.contains(callee)) return;
        try {
            mCalleeSet.add(callee);
            setupCallees();
            saveCalleeSet();
        } catch (IOException e) {
            Log.v(TAG, "updateCalleeSet", e);
        }
    }

    private void getCalleeSet() {
        File f = new File(CALLEE_SET_PATH);
        if (f.exists()) {
            try {
                mCalleeSet = retrieveCalleeSet(f);
            } catch (IOException e) {
                Log.v(TAG, "getCalleeSet", e);
            }
        } else {
            mCalleeSet = new HashSet<String>();
        }
    }

    private void saveCalleeSet() throws IOException {
        File f = new File(CALLEE_SET_PATH);
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f));
        oos.writeObject(mCalleeSet);
        oos.close();
    }

    private Set<String> retrieveCalleeSet(File setFile) throws IOException {
        try {
            ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
                    setFile));
            Set<String> s = (Set<String>) ois.readObject();
            ois.close();
            return s;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "deserialize a profile", e);
            return null;
        }
    }


    private void setText(final TextView view, final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                view.setText(text);
            }
        });
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
                Toast.makeText(SipCallSetup.this, message, Toast.LENGTH_SHORT)
                        .show();
            }
        });
    }

    private static class ProfileWrapper {
        SipProfile mProfile;
        ProfileWrapper(SipProfile p) {
            mProfile = p;
        }

        public String toString() {
            SipProfile p = mProfile;
            String protocol = p.getProtocol();
            if (protocol.toUpperCase().equals("UDP")) {
                protocol = "";
            } else {
                protocol = "(" + protocol + ")";
            }
            return p.getUserName() + "@" + p.getSipDomain() + protocol;
        }
    }
}
