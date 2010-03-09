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

import gov.nist.javax.sdp.parser.SDPAnnounceParser;

import java.text.ParseException;
import javax.sdp.SdpParseException;
import javax.sip.SipException;

public class SdpSessionDescription implements SessionDescription {
    private String mContent;
    private javax.sdp.SessionDescription mSessionDescription;

    public SdpSessionDescription(String sdpString) throws SdpParseException {
        mContent = sdpString;
        try {
            mSessionDescription = new SDPAnnounceParser(sdpString).parse();
        } catch (ParseException e) {
            throw new SdpParseException(e.toString(), e);
        }
    }

    public SdpSessionDescription(byte[] content) throws SdpParseException {
        this(new String(content));
    }

    public String getType() {
        return "sdp";
    }

    public byte[] getContent() {
        return mContent.getBytes();
    }
}
