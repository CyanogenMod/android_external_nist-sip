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

import gov.nist.javax.sdp.SessionDescriptionImpl;
import gov.nist.javax.sdp.fields.AttributeField;
import gov.nist.javax.sdp.fields.ConnectionField;
import gov.nist.javax.sdp.fields.MediaField;
import gov.nist.javax.sdp.fields.OriginField;
import gov.nist.javax.sdp.fields.ProtoVersionField;
import gov.nist.javax.sdp.fields.SessionNameField;
import gov.nist.javax.sdp.fields.TimeField;
import gov.nist.javax.sdp.parser.SDPAnnounceParser;

import java.text.ParseException;
import java.util.Collections;
import java.util.Vector;
import javax.sdp.Connection;
import javax.sdp.MediaDescription;
import javax.sdp.SdpException;
import javax.sip.SipException;

public class SdpSessionDescription implements SessionDescription {
    private SessionDescriptionImpl mSessionDescription;
    private String mPeerMediaAddress;
    private int mPeerMediaPort;

    public static class Builder {
        private SdpSessionDescription mSdp = new SdpSessionDescription();
        private SessionDescriptionImpl mSessionDescription;

        public Builder(String sessionName) throws SdpException {
            mSessionDescription = new SessionDescriptionImpl();
            try {
                ProtoVersionField proto = new ProtoVersionField();
                proto.setVersion(0);
                mSessionDescription.addField(proto);

                TimeField time = new TimeField();
                time.setZero();
                mSessionDescription.addField(time);

                SessionNameField session = new SessionNameField();
                session.setValue(sessionName);
                mSessionDescription.addField(session);
            } catch (Exception e) {
                throw new SdpException(e.toString(), e);
            }
        }

        public Builder setConnectionInfo(String netType, String addrType,
                String addr) throws SdpException {
            try {
                ConnectionField connection = new ConnectionField();
                connection.setNetworkType(netType);
                connection.setAddressType(addrType);
                connection.setAddress(addr);
                mSessionDescription.addField(connection);
            } catch (Exception e) {
                throw new SdpException(e.toString(), e);
            }
            return this;
        }

        public Builder setOrigin(SipProfile user, long sessionId,
                long sessionVersion, String networkType, String addressType,
                String address) throws SdpException {
            try {
                OriginField origin = new OriginField();
                origin.setUsername(user.getUserName());
                origin.setSessionId(sessionId);
                origin.setSessionVersion(sessionVersion);
                origin.setAddressType(addressType);
                origin.setNetworkType(networkType);
                origin.setAddress(address);
                mSessionDescription.addField(origin);
            } catch (Exception e) {
                throw new SdpException(e.toString(), e);
            }
            return this;
        }

        public Builder addMedia(String media, int port, int numPorts,
                String transport, Integer... types) throws SdpException {
            MediaField field = new MediaField();
            Vector<Integer> typeVector = new Vector<Integer>();
            Collections.addAll(typeVector, types);
            try {
                field.setMediaType(media);
                field.setMediaPort(port);
                field.setPortCount(numPorts);
                field.setProtocol(transport);
                field.setMediaFormats(typeVector);
                mSessionDescription.addField(field);
            } catch (Exception e) {
                throw new SdpException(e.toString(), e);
            }
           return this;
        }

        public Builder addMediaAttribute(String name, String value)
                throws SdpException {
            try {
                if (mSessionDescription.getMediaDescriptions(false) == null) {
                    throw new SdpException("Should add media first!");
                }
                AttributeField attribute = new AttributeField();
                attribute.setName(name);
                attribute.setValueAllowNull(value);
                mSessionDescription.addField(attribute);
            } catch (Exception e) {
                throw new SdpException(e.toString(), e);
            }
            return this;
        }

        public Builder addSessionAttribute(String name, String value)
                throws SdpException {
            try {
                AttributeField attribute = new AttributeField();
                attribute.setName(name);
                attribute.setValueAllowNull(value);
                mSessionDescription.addField(attribute);
            } catch (Exception e) {
                throw new SdpException(e.toString(), e);
            }
            return this;
        }

        public SdpSessionDescription build() {
            mSdp.mSessionDescription = mSessionDescription;
            return mSdp;
        }
    }

    private SdpSessionDescription() {
    }

    public SdpSessionDescription(String sdpString) throws SdpException {
        try {
            mSessionDescription = new SDPAnnounceParser(sdpString).parse();
        } catch (ParseException e) {
            throw new SdpException(e.toString(), e);
        }
        init();
    }

    public SdpSessionDescription(byte[] content) throws SdpException {
        this(new String(content));
    }

    public String getPeerMediaAddress() {
        return mPeerMediaAddress;
    }

    public int getPeerMediaPort() {
        return mPeerMediaPort;
    }

    private void init() throws SdpException {
        Vector vector = mSessionDescription.getMediaDescriptions(false);
        // FIXME
        MediaDescription md = (MediaDescription) vector.firstElement();
        mPeerMediaPort = md.getMedia().getMediaPort();

        Connection connection = md.getConnection();
        if (connection == null) {
            connection = mSessionDescription.getConnection();
        }
        mPeerMediaAddress = connection.getAddress();
    }

    public String getType() {
        return "sdp";
    }

    public byte[] getContent() {
          return mSessionDescription.toString().getBytes();
    }

    public String toString() {
        return mSessionDescription.toString();
    }
}
