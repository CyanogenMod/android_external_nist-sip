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

import android.net.sip.ISipSessionListener;
import android.net.sip.SessionDescription;
import android.net.sip.SipProfile;

interface ISipSession {
    String getLocalIp();
    SipProfile getLocalProfile();
    SipProfile getPeerProfile();
    String getState();
    String getCallId();

    void setListener(in ISipSessionListener listener);

    void register();
    void unregister();
    void makeCall(in SipProfile callee,
            in SessionDescription sessionDescription);
    void answerCall(in SessionDescription sessionDescription);
    void endCall();
    void changeCall(in SessionDescription sessionDescription);
}
