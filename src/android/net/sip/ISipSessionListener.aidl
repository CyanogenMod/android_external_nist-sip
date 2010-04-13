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

import android.net.sip.ISipSession;
import android.net.sip.SipProfile;

interface ISipSessionListener {
    void onCalling(in ISipSession session);
    void onRinging(in ISipSession session, in SipProfile caller,
            in byte[] sessionDescription);
    void onRingingBack(in ISipSession session);
    void onCallEstablished(in ISipSession session,
            in byte[] sessionDescription);
    void onCallEnded(in ISipSession session);
    void onCallBusy(in ISipSession session);
    void onCallChanged(in ISipSession session, in byte[] sessionDescription);
    void onError(in ISipSession session, String errorClass,
            String errorMessage);

    void onRegistrationDone(in ISipSession session);
    void onRegistrationFailed(in ISipSession session, String errorClass,
            String errorMessage);
    void onRegistrationTimeout(in ISipSession session);
}
