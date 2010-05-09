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
import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipProfile;
import android.net.sip.SipManager;
import android.net.sip.SipSessionAdapter;
import android.os.Bundle;
import android.os.Parcelable;
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


public class SipSettings extends PreferenceActivity {
    static final String KEY_SIP_PROFILE = "sip_profile";

    private static final String PREF_ADD_SIP = "add_sip_account";
    private static final String PREF_SIP_LIST = "sip_account_list";
    private static final String PROFILE_OBJ_FILE = ".pobj";
    private static final String TAG = "SipSettings";
    private static final String REGISTERED = "REGISTERED";
    private static final String UNREGISTERED = "UNREGISTERED";

    private static final String INCOMING_CALL_ACTION =
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

    private class SipPreference extends Preference {
        SipProfile mProfile;
        SipPreference(Context c, SipProfile p) {
            super(c);
            setProfile(p);
        }

        void setProfile(SipProfile p) {
            mProfile = p;
            setTitle(p.getProfileName());
            setSummary(p.getUserName() + "@" + p.getServerAddress());
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.sip_setting);
        mProfilesDirectory = getFilesDir().getAbsolutePath() + "/profiles/";
        mSipListContainer = (PreferenceCategory) findPreference(PREF_SIP_LIST);

        PreferenceScreen mAddSip = (PreferenceScreen) findPreference(PREF_ADD_SIP);
        mAddSip.setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        startSipEditor(null);
                        return true;
                    }
                });

        // for long-press gesture on a profile preference
        registerForContextMenu(getListView());
        retrieveSipListFromStorage();

        SipManager.initialize(this);
    }

    private void retrieveSipListFromStorage() {

        mSipPreferenceMap = new LinkedHashMap<String, SipPreference>();
        mSipProfileList = Collections.synchronizedList(
                new ArrayList<SipProfile>());
        mSipListContainer.removeAll();

        File root = new File(mProfilesDirectory);
        String[] dirs = root.list();
        if (dirs == null) return;
        for (String dir : dirs) {
            File f = new File(new File(root, dir), PROFILE_OBJ_FILE);
            if (!f.exists()) continue;
            try {
                SipProfile p = deserialize(f);
                if (p == null) continue;
                if (!dir.equals(p.getProfileName())) continue;

                mSipProfileList.add(p);
            } catch (IOException e) {
                Log.e(TAG, "retrieveVpnListFromStorage()", e);
            }
        }
        Collections.sort(mSipProfileList, new Comparator<SipProfile>() {
            public int compare(SipProfile p1, SipProfile p2) {
                return p1.getProfileName().compareTo(p2.getProfileName());
            }

            public boolean equals(SipProfile p) {
                // not used
                return false;
            }
        });
        for (SipProfile p : mSipProfileList) {
            Preference pref = addPreferenceFor(p, true);
        }
    }

    private SipPreference addPreferenceFor(SipProfile p, boolean addToContainer)
            {
        SipPreference pref = new SipPreference(this, p);
        mSipPreferenceMap.put(p.getProfileName(), pref);
        if (addToContainer) mSipListContainer.addPreference(pref);

        pref.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference pref) {
                        startSipEditor(((SipPreference) pref).mProfile);
                        return true;
                    }
                });
        return pref;
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
            if (p != null) {
                try {
                    SipManager.openToReceiveCalls(p, INCOMING_CALL_ACTION);
                } catch (Exception e) {
                    Log.e(TAG, "register failed", e);
                }
            }
            return true;
        case CONTEXT_MENU_UNREGISTER_ID:
            if (p != null) {
                try {
                    SipManager.close(p);
                } catch (Exception e) {
                    Log.e(TAG, "unregister failed:" + e);
                }
            }
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
        SipPreference pref = mSipPreferenceMap.remove(p.getProfileName());
        mSipListContainer.removePreference(pref);
        deleteProfile(mProfilesDirectory + p.getProfileName());
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
        Log.v(TAG, "onActivityResult");
        if (resultCode != RESULT_OK) return;
        SipProfile profile = intent.getParcelableExtra(KEY_SIP_PROFILE);
        try {
            saveProfileToStorage(profile);
        } catch (IOException e) {
            Log.v(TAG, "Can not save the profile : " + e.getMessage());
        }
        Log.v(TAG, "New Profile Name is " + profile.getProfileName());
    }

    private SipProfile deserialize(File profileObjectFile) throws IOException {
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

    private void setSessionSummary(final ISipSession session, final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    SipPreference pref =
                            mSipPreferenceMap.get(session.getLocalProfile().getProfileName());
                    if (pref != null) {
                        pref.setSummary(message);
                        if (REGISTERED.equals(message)) {
                            Log.v(TAG, "========= REGISTERED!!!");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "setSessionSummary failed:" + e);
                }
            }
        });
    }

    private ISipSessionListener createSessionAdapter() {
        return new SipSessionAdapter() {
            @Override
            public void onRegistrationDone(ISipSession session, int duration) {
                Log.v(TAG, "=========1 REGISTERED!!!");
                setSessionSummary(session, (duration < 0) ? UNREGISTERED : REGISTERED);
            }

            @Override
            public void onRegistrationFailed(ISipSession session,
                    String className, String message) {
                setSessionSummary(session, "Registration error: " + message);
            }

            @Override
            public void onRegistrationTimeout(ISipSession session) {
                setSessionSummary(session, "Registration timed out");
            }
        };
    }
}
