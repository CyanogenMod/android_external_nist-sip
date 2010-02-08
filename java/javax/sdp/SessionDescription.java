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

package javax.sdp;

import java.util.*;

public interface SessionDescription {
    Version getVersion();
    void setVersion(Version v) throws SdpException;
    Origin getOrigin();
    void setOrigin(Origin origin) throws SdpException;
    SessionName getSessionName();
    void setSessionName(SessionName sessionName) throws SdpException;
    Info getInfo();
    void setInfo(Info i) throws SdpException;
    URI getURI();
    void setURI(URI uri) throws SdpException;
    Vector getEmails(boolean create) throws SdpParseException;
    void setEmails(Vector emails) throws SdpException;
    Vector getPhones(boolean create) throws SdpException;
    void setPhones(Vector phones) throws SdpException;
    Vector getTimeDescriptions(boolean create) throws SdpException;
    void setTimeDescriptions(Vector times) throws SdpException;
    Vector getZoneAdjustments(boolean create) throws SdpException;
    void setZoneAdjustments(Vector zoneAdjustments) throws SdpException;
    Connection getConnection();
    void setConnection(Connection conn) throws SdpException;
    Vector getBandwidths(boolean create);
    void setBandwidths(Vector bandwidthList) throws SdpException;
    int getBandwidth(String name) throws SdpParseException;
    void setBandwidth(String name, int value) throws SdpException;
    void removeBandwidth(String name);
    Key getKey();
    void setKey(Key key) throws SdpException;
    String getAttribute(String name) throws SdpParseException;
    Vector getAttributes(boolean create);
    void removeAttribute(String name);
    void setAttribute(String name, String value) throws SdpException;
    void setAttributes(Vector attributes) throws SdpException;
    Vector getMediaDescriptions(boolean create) throws SdpException;
}
