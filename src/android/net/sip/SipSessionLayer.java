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

package android.net.sip;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import javax.sip.SipException;

/**
 * @hide
 * Creates and manages multiple {@link SipSession}.
 */
public class SipSessionLayer {
    private static final String TAG = SipSessionLayer.class.getSimpleName();

    private String mMyIp;
    private Map<String, SipSessionGroup> mGroupMap =
            new HashMap<String, SipSessionGroup>();

    public SipSessionLayer() throws SipException {
        try {
            mMyIp = getMyIp();
        } catch (IOException e) {
            throw new SipException("SipSessionLayer constructor", e);
        }
    }

    public String getLocalIp() {
        if (mMyIp != null) return mMyIp;
        try {
            return getMyIp();
        } catch (IOException e) {
            Log.w(TAG, "getLocalIp(): " + e);
            return "127.0.0.1";
        }
    }

    private String getMyIp() throws IOException {
            DatagramSocket s = new DatagramSocket();
            s.connect(InetAddress.getByName("192.168.1.1"), 80);
            return s.getLocalAddress().getHostAddress();
    }

    public synchronized void close() {
        for (String key : mGroupMap.keySet()) {
            mGroupMap.get(key).close();
        }
        mGroupMap.clear();
    }

    public synchronized SipSession createSipSession(SipProfile myself,
            SipSessionListener listener) throws SipException {
        String key = myself.getUri().toString();
        SipSessionGroup group = mGroupMap.get(key);
        if (group == null) {
            group = new SipSessionGroup(mMyIp, myself, listener);
            mGroupMap.put(key, group);
        }
        return group.getDefaultSession();
    }
}
