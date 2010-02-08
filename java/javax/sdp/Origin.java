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

public interface Origin {
    String getUsername() throws SdpParseException;
    long getSessId();
    String getSessIdAsString();
    long getSessVersion();
    String getSessVersionAsString();
    String getNettype();
    String getAddrtype();
    void setSessId(long s);
    void setSessionId(String sessId);
    void setSessVersion(long s);
    void setSessVersion(String s);
    void setNettype(String n);
    void setAddrtype(String a);
    void setUsername(String user) throws SdpException;
    long getSessionId() throws SdpParseException;
    void setSessionId(long id) throws SdpException;
    long getSessionVersion() throws SdpParseException;
    void setSessionVersion(long version) throws SdpException;
    String getAddress() throws SdpParseException;
    String getAddressType() throws SdpParseException;
    String getNetworkType() throws SdpParseException;
    void setAddressType(String type) throws SdpException;
    void setNetworkType(String type) throws SdpException;
    String encode();
    Object clone();
}
