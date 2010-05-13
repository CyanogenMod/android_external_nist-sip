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

package com.android.settings.sip;


import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.sip.SipProfile;
import android.net.sip.SipManager;
import android.net.sip.SipRegistrationListener;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView.AdapterContextMenuInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sip.SipException;

/**
 * The PreferenceActivity class for managing sip profile preferences.
 */
public class SipSettings extends PreferenceActivity {
    static final String KEY_SIP_PROFILE = "sip_profile";
    static final String PROFILE_OBJ_FILE = ".pobj";
    static final String PROFILES_DIR = "/profiles/";

    private static final String PREF_AUTO_REG = "auto_registration";
    private static final String PREF_ADD_SIP = "add_sip_account";
    private static final String PREF_SIP_LIST = "sip_account_list";
    private static final String TAG = "SipSettings";
    private static final String REGISTERED = "REGISTERED";
    private static final String UNREGISTERED = "NOT REGISTERED";

    public static final String INCOMING_CALL_ACTION =
            "com.android.sip.demo.SipMain";

    private static final int REQUEST_ADD_OR_EDIT_SIP_PROFILE = 1;

    private static final int CONTEXT_MENU_REGISTER_ID = ContextMenu.FIRST;
    private static final int CONTEXT_MENU_UNREGISTER_ID = ContextMenu.FIRST + 1;
    private static final int CONTEXT_MENU_EDIT_ID = ContextMenu.FIRST + 2;
    private static final int CONTEXT_MENU_DELETE_ID = ContextMenu.FIRST + 3;
    private static final int EXPIRY_TIME = 600;

    private String mProfilesDirectory;

    private SipProfile mProfile;

    private PreferenceCategory mSipListContainer;
    private Map<String, SipPreference> mSipPreferenceMap;
    private List<SipProfile> mSipProfileList;
    private SharedPreferences.Editor   mSettingsEditor;

    private class SipPreference extends Preference {
        SipProfile mProfile;
        SipPreference(Context c, SipProfile p) {
            super(c);
            setProfile(p);
        }

        void setProfile(SipProfile p) {
            mProfile = p;
            setTitle(p.getProfileName());
            try {
                setSummary(SipManager.isRegistered(p.getUriString())
                        ? REGISTERED : UNREGISTERED);
            } catch (SipException e) {
                Log.e(TAG, "Error!setProfileSummary:", e);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        addPreferencesFromResource(R.xml.sip_setting);
        mProfilesDirectory = getFilesDir().getAbsolutePath() + PROFILES_DIR;
        mSipListContainer = (PreferenceCategory) findPreference(PREF_SIP_LIST);

        registerForAddSipListener();

        // for long-press gesture on a profile preference
        registerForContextMenu(getListView());

        registerForAutoRegistrationListener();

        updateProfilesStatus();
    }

    private void registerForAddSipListener() {
        PreferenceScreen mAddSip =
                (PreferenceScreen) findPreference(PREF_ADD_SIP);
        mAddSip.setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        startSipEditor(null);
                        return true;
                    }
                });
    }

    private void registerForAutoRegistrationListener() {
        mSettingsEditor = getSharedPreferences(
                SipAutoRegistration.SIP_SHARED_PREFERENCES,
                Context.MODE_WORLD_READABLE).edit();
        ((CheckBoxPreference) findPreference(PREF_AUTO_REG))
                .setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        boolean enabled =
                                ((CheckBoxPreference) preference).isChecked();
                        mSettingsEditor.putBoolean(
                                SipAutoRegistration.AUTOREG_FLAG, enabled);
                        mSettingsEditor.commit();
                        if (enabled) registerAllProfiles();
                        return true;
                    }
                });
    }

    private void updateProfilesStatus() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    SipManager.initialize(SipSettings.this);
                    retrieveSipListFromStorage();
                } catch (Exception e) {
                    Log.e(TAG, "isRegistered", e);
                }
            }
        }).start();
    }

    static List<SipProfile> retrieveSipListFromDirectory(
            String directory) {
        List<SipProfile> sipProfileList = Collections.synchronizedList(
                new ArrayList<SipProfile>());

        File root = new File(directory);
        String[] dirs = root.list();
        if (dirs == null) return sipProfileList;
        for (String dir : dirs) {
            File f = new File(
                    new File(root, dir), SipSettings.PROFILE_OBJ_FILE);
            if (!f.exists()) continue;
            try {
                SipProfile p = SipSettings.deserialize(f);
                if (p == null) continue;
                if (!dir.equals(p.getProfileName())) continue;

                sipProfileList.add(p);
            } catch (IOException e) {
                Log.e(TAG, "retrieveProfileListFromStorage()", e);
            }
        }
        Collections.sort(sipProfileList, new Comparator<SipProfile>() {
            public int compare(SipProfile p1, SipProfile p2) {
                return p1.getProfileName().compareTo(p2.getProfileName());
            }

            public boolean equals(SipProfile p) {
                // not used
                return false;
            }
        });
        return sipProfileList;
    }

    private void retrieveSipListFromStorage() {

        mSipPreferenceMap = new LinkedHashMap<String, SipPreference>();
        mSipProfileList = retrieveSipListFromDirectory(mProfilesDirectory);
        mSipListContainer.removeAll();

        for (SipProfile p : mSipProfileList) {
            addPreferenceFor(p, true);
        }
    }

    private void registerAllProfiles() {
        try {
            for (SipProfile p : mSipProfileList) {
                if (!SipManager.isRegistered(p.getUriString())) {
                    registerProfile(p);
                }
            }
        } catch (SipException e) {
            Log.e(TAG, "Error!registerAllProfiles():", e);
        }
    }

    private void addPreferenceFor(SipProfile p, boolean addToContainer)
            {
        String status;
        try {
            Log.v(TAG, "addPreferenceFor profile uri" + p.getUri());
            status = SipManager.isRegistered(p.getUriString())
                    ? REGISTERED : UNREGISTERED;
        } catch (Exception e) {
            Log.e(TAG, "Cannot get status of profile" + p.getProfileName(), e);
            return;
        }
        SipPreference pref = new SipPreference(this, p);
        mSipPreferenceMap.put(p.getUriString(), pref);
        if (addToContainer) mSipListContainer.addPreference(pref);

        pref.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference pref) {
                        startSipEditor(((SipPreference) pref).mProfile);
                        return true;
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterForContextMenu(getListView());
    }

    private SipProfile getProfile(int position) {
        return ((position >= 0) ? mSipProfileList.get(position) : null);
    }

    private int getProfilePositionFrom(AdapterContextMenuInfo menuInfo) {
        return menuInfo.position - mSipListContainer.getOrder() - 1;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        SipProfile p = getProfile(getProfilePositionFrom(
                    (AdapterContextMenuInfo) menuInfo));
        if (p != null) {
            menu.setHeaderTitle(p.getProfileName());

            menu.add(0, CONTEXT_MENU_REGISTER_ID, 0, R.string.sip_menu_register);
            menu.add(0, CONTEXT_MENU_UNREGISTER_ID, 0, R.string.sip_menu_unregister);
            menu.add(0, CONTEXT_MENU_EDIT_ID, 0, R.string.sip_menu_edit);
            menu.add(0, CONTEXT_MENU_DELETE_ID, 0, R.string.sip_menu_delete);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        SipProfile p = getProfile(getProfilePositionFrom(
                (AdapterContextMenuInfo) item.getMenuInfo()));

        switch(item.getItemId()) {
        case CONTEXT_MENU_REGISTER_ID:
            registerProfile(p);
            return true;
        case CONTEXT_MENU_UNREGISTER_ID:
            unRegisterProfile(p);
            return true;

        case CONTEXT_MENU_EDIT_ID:
            startSipEditor(p);
            return true;

        case CONTEXT_MENU_DELETE_ID:
            deleteProfile(p);
            return true;
        }

        return super.onContextItemSelected(item);
    }

    private void registerProfile(SipProfile profile) {
        if (profile != null) {
            try {
                SipManager.openToReceiveCalls(profile, INCOMING_CALL_ACTION,
                        createRegistrationListener());
            } catch (Exception e) {
                Log.e(TAG, "register failed", e);
            }
        }
    }

    private void unRegisterProfile(SipProfile profile) {
        if (profile != null) {
            try {
                SipManager.close(profile.getUriString());
                setProfileSummary(profile, UNREGISTERED);
            } catch (Exception e) {
                Log.e(TAG, "unregister failed:" + e);
            }
        }
    }

    // TODO: Use the Util class in settings.vpn instead
    private void deleteProfile(String name) {
        deleteProfile(new File(name));
    }

    private void deleteProfile(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) deleteProfile(child);
        }
        file.delete();
    }

    private void deleteProfile(SipProfile p) {
        mSipProfileList.remove(p);
        SipPreference pref = mSipPreferenceMap.remove(p.getUriString());
        mSipListContainer.removePreference(pref);
        deleteProfile(mProfilesDirectory + p.getProfileName());
        unRegisterProfile(p);
    }

    private void saveProfileToStorage(SipProfile p) throws IOException {
        if (mProfile != null) deleteProfile(mProfile);
        File f = new File(mProfilesDirectory + p.getProfileName());
        if (!f.exists()) f.mkdirs();
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(
                new File(f, PROFILE_OBJ_FILE)));
        oos.writeObject(p);
        oos.close();
        mSipProfileList.add(p);
        addPreferenceFor(p, true);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent intent) {
        if (resultCode != RESULT_OK) return;
        SipProfile profile = intent.getParcelableExtra(KEY_SIP_PROFILE);
        try {
            saveProfileToStorage(profile);
        } catch (IOException e) {
            Log.v(TAG, "Can not save the profile : " + e.getMessage());
        }
        Log.v(TAG, "New Profile Name is " + profile.getProfileName());
    }

    static SipProfile deserialize(File profileObjectFile) throws IOException {
        try {
            ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
                    profileObjectFile));
            SipProfile p = (SipProfile) ois.readObject();
            ois.close();
            return p;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "deserialize a profile", e);
            return null;
        }
    }

    private void startSipEditor(final SipProfile profile) {
        mProfile = profile;
        Intent intent = new Intent(this, SipEditor.class);
        intent.putExtra(KEY_SIP_PROFILE, (Parcelable) profile);
        startActivityForResult(intent, REQUEST_ADD_OR_EDIT_SIP_PROFILE);
    }

    private void setProfileSummary(SipProfile profile, String message) {
        setProfileSummary(profile.getUriString(), message);
    }

    private void setProfileSummary(final String profileUri,
            final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    SipPreference pref = mSipPreferenceMap.get(profileUri);
                    if (pref != null) {
                        pref.setSummary(message);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "setSessionSummary failed:" + e);
                }
            }
        });
    }

    private SipRegistrationListener createRegistrationListener() {
        return new SipRegistrationListener() {
            public void onRegistrationDone(String profileUri, long expiryTime) {
                setProfileSummary(profileUri,
                        (expiryTime <= 0) ? UNREGISTERED : REGISTERED);
            }

            public void onRegistrationFailed(String profileUri,
                    String className, String message) {
                setProfileSummary(profileUri, "Registration error: " + message);
            }

            public void onRegistering(String profileUri) {
                setProfileSummary(profileUri, "Registering...");
            }
        };
    }
}
