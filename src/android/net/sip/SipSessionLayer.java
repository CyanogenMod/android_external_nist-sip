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
import java.util.Properties;

import javax.sip.ListeningPoint;
import javax.sip.SipException;
import javax.sip.SipFactory;
import javax.sip.SipProvider;
import javax.sip.SipStack;

/**
 * @hide
 * Creates and manages multiple {@link SipSession}.
 */
public class SipSessionLayer {
    private static final String TAG = SipSessionLayer.class.getSimpleName();
    private static final String STACK_NAME = "A SIP STACK";

    private SipStack mSipStack;
    private String mMyIp;
    private Map<String, SipSessionGroup> mGroupMap =
            new HashMap<String, SipSessionGroup>();

    public SipSessionLayer() throws SipException {
        if (mSipStack != null) {
            throw new SipException("Call close() before open it again");
        }
        try {
            mMyIp = getMyIp();
        } catch (IOException e) {
            throw new SipException("SipSessionLayer constructor", e);
        }
        SipFactory sipFactory = SipFactory.getInstance();
        Properties properties = new Properties();
        properties.setProperty("javax.sip.STACK_NAME", STACK_NAME);
        SipStack stack = mSipStack = sipFactory.createSipStack(properties);
        stack.start();
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
            s.connect(InetAddress.getByName("www.google.com"), 80);
            return s.getLocalAddress().getHostAddress();
    }

    private static int allocateLocalPort() throws SipException {
        try {
            DatagramSocket s = new DatagramSocket();
            int localPort = s.getLocalPort();
            s.close();
            return localPort;
        } catch (IOException e) {
            throw new SipException("allocateLocalPort()", e);
        }
    }

    public synchronized void close() {
        if (mSipStack != null) {
            mSipStack.stop();
            mSipStack = null;
        }
        // remove all the groups
    }

    private synchronized SipSessionGroup createGroup(SipProfile myself,
            SipSessionListener listener) throws SipException {
        SipSessionGroup group = mGroupMap.get(myself.getUri().toString());
        if (group != null) return group;

        SipStack stack = mSipStack;
        SipProvider provider = stack.createSipProvider(
                stack.createListeningPoint(mMyIp, allocateLocalPort(),
                        ListeningPoint.UDP));
        return new SipSessionGroup(stack, provider, myself, listener);
    }


    public synchronized SipSession createSipSession(SipProfile myself,
            SipSessionListener listener) throws SipException {
        return createGroup(myself, listener).getDefaultSession();
    }
}
