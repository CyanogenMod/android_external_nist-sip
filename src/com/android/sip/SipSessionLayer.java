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

import android.net.sip.ISipSession;
import android.net.sip.ISipSessionListener;
import android.net.sip.SipProfile;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import javax.sip.SipException;

/**
 * Creates and manages multiple {@link ISipSession}'s.
 */
class SipSessionLayer {
    private static final String TAG = SipSessionLayer.class.getSimpleName();

    private String mMyIp;

    // local URI --> group
    private Map<String, SipSessionGroup> mGroupMap =
            new HashMap<String, SipSessionGroup>();

    public SipSessionLayer() throws SipException {
        try {
            mMyIp = getMyIp();
        } catch (IOException e) {
            throw new SipException("SipSessionLayer constructor", e);
        }
    }

    private String getMyIp() throws IOException {
        DatagramSocket s = new DatagramSocket();
        s.connect(InetAddress.getByName("192.168.1.1"), 80);
        return s.getLocalAddress().getHostAddress();
    }

    public synchronized void onNetworkDisconnected() {
        for (String key : mGroupMap.keySet()) {
            mGroupMap.get(key).onNetworkDisconnected();
        }
        mGroupMap.clear();
    }

    public ISipSession createSession(SipProfile localProfile,
            ISipSessionListener listener) throws SipException {
        return createGroup(localProfile).createSession(listener);
    }

    private synchronized SipSessionGroup getGroup(SipProfile myself) {
        String key = myself.getUri().toString();
        return mGroupMap.get(key);
    }

    private synchronized SipSessionGroup removeGroup(SipProfile myself) {
        String key = myself.getUri().toString();
        return mGroupMap.remove(key);
    }

    private synchronized SipSessionGroup createGroup(SipProfile myself)
            throws SipException {
        String key = myself.getUri().toString();
        SipSessionGroup group = mGroupMap.get(key);
        if (group == null) {
            group = new SipSessionGroup(mMyIp, myself);
            mGroupMap.put(key, group);
        }
        return group;
    }

    public void openToReceiveCalls(SipProfile myself,
            ISipSessionListener listener) throws SipException {
        createGroup(myself).openToReceiveCalls(listener);
    }

    public void close(SipProfile myself) {
        SipSessionGroup group = removeGroup(myself);
        if (group != null) group.close();
    }
}
