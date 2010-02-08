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

public interface Media {
    String getMedia();
    int getPort();
    int getNports();
    Vector getFormats();
    void setMedia(String m);
    void setPort(int p);
    void setNports(int n);
    void setProto(String p);
    void setFormats(Vector formats);
    void setMediaType(String mediaType) throws SdpException;
    int getMediaPort() throws SdpParseException;
    void setMediaPort(int port) throws SdpException;
    int getPortCount() throws SdpParseException;
    void setPortCount(int portCount) throws SdpException;
    String getProtocol() throws SdpParseException;
    void setProtocol(String protocol) throws SdpException;
    void setMediaFormats(Vector mediaFormats) throws SdpException;
    String encode();
    Object clone();
}
