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

public interface MediaDescription {
    String encode();
    Vector getAttributeFields();
    void setAttributeFields(Vector a);
    Media getMedia();
    void setMedia(Media media) throws SdpException;
    Info getInfo();
    void setInfo(Info i) throws SdpException;
    Connection getConnection();
    void setConnection(Connection conn) throws SdpException;
    Vector getBandwidths(boolean create);
    void setBandwidths(Vector bandwidths) throws SdpException;
    int getBandwidth(String name) throws SdpParseException;
    void setBandwidth(String name, int value) throws SdpException;
    void removeBandwidth(String name);
    Key getKey();
    void setKey(Key key) throws SdpException;
    Vector getAttributes(boolean create);
    void setAttributes(Vector attributes) throws SdpException;
    String getAttribute(String name) throws SdpParseException;
    void setAttribute(String name, String value) throws SdpException;
    void setDuplexity(String duplexity);
    void removeAttribute(String name);
    Vector getMimeTypes() throws SdpException;
    Vector getMimeParameters() throws SdpException;
    void setPreconditionFields(Vector precondition) throws SdpException;
    Vector getPreconditionFields();
}
