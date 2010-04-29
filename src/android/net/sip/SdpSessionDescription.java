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

import android.util.Log;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;
import javax.sdp.Connection;
import javax.sdp.MediaDescription;
import javax.sdp.SdpException;
import javax.sip.SipException;

public class SdpSessionDescription extends SessionDescription {
    private static final String TAG = "SDP";
    private static final String AUDIO = "audio";
    private static final String RTPMAP = "rtpmap";
    private static final String PTIME = "ptime";

    private SessionDescriptionImpl mSessionDescription;

    public static class AudioCodec {
        public final int payloadType;
        public final String name;
        public final int sampleRate;
        public final int sampleCount;

        public AudioCodec(int payloadType, String name, int sampleRate,
                int sampleCount) {
            this.payloadType = payloadType;
            this.name = name;
            this.sampleRate = sampleRate;
            this.sampleCount = sampleCount;
        }
    }

    public static class Builder {
        private SdpSessionDescription mSdp = new SdpSessionDescription();
        private SessionDescriptionImpl mSessionDescription;

        public Builder(String sessionName) throws SdpException {
            mSessionDescription = new SessionDescriptionImpl();
            mSdp.mSessionDescription = mSessionDescription;
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
                throwSdpException(e);
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
                throwSdpException(e);
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
                throwSdpException(e);
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
                throwSdpException(e);
            }
           return this;
        }

        public Builder addMediaAttribute(String type, String name, String value)
                throws SdpException {
            try {
                MediaDescription md = mSdp.getMediaDescription(type);
                if (md == null) {
                    throw new SdpException("Should add media first!");
                }
                AttributeField attribute = new AttributeField();
                attribute.setName(name);
                attribute.setValueAllowNull(value);
                mSessionDescription.addField(attribute);
            } catch (Exception e) {
                throwSdpException(e);
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
                throwSdpException(e);
            }
            return this;
        }

        private void throwSdpException(Exception e) throws SdpException {
            if (e instanceof SdpException) {
                throw (SdpException) e;
            } else {
                throw new SdpException(e.toString(), e);
            }
        }

        public SdpSessionDescription build() {
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
        verify();
    }

    public SdpSessionDescription(byte[] content) throws SdpException {
        this(new String(content));
    }

    private void verify() throws SdpException {
        // make sure the syntax is correct over the fields we're interested in
        Vector<MediaDescription> descriptions = (Vector<MediaDescription>)
                mSessionDescription.getMediaDescriptions(false);
        for (MediaDescription md : descriptions) {
            md.getMedia().getMediaPort();
            Connection connection = md.getConnection();
            if (connection != null) connection.getAddress();
            md.getMedia().getFormats();
        }
        Connection connection = mSessionDescription.getConnection();
        if (connection != null) connection.getAddress();
    }

    public String getPeerMediaAddress(String type) {
        try {
            MediaDescription md = getMediaDescription(type);
            Connection connection = md.getConnection();
            if (connection == null) {
                connection = mSessionDescription.getConnection();
            }
            return ((connection == null) ? null : connection.getAddress());
        } catch (SdpException e) {
            // should not occur
            return null;
        }
    }

    public int getPeerMediaPort(String type) {
        try {
            MediaDescription md = getMediaDescription(type);
            return md.getMedia().getMediaPort();
        } catch (SdpException e) {
            // should not occur
            return -1;
        }
    }

    private boolean containsAttribute(String type, String name) {
        if (name == null) return false;
        MediaDescription md = getMediaDescription(type);
        Vector<AttributeField> v = (Vector<AttributeField>)
                md.getAttributeFields();
        for (AttributeField field : v) {
            if (name.equals(field.getAttribute().getName())) return true;
        }
        return false;
    }

    public boolean isSendOnly(String type) {
        boolean answer = containsAttribute(type, "sendonly");
        Log.d(TAG, "   sendonly? " + answer);
        return answer;
    }

    public boolean isReceiveOnly(String type) {
        boolean answer = containsAttribute(type, "recvonly");
        Log.d(TAG, "   recvonly? " + answer);
        return answer;
    }

    private AudioCodec parseAudioCodec(String rtpmap, int ptime) {
        String[] ss = rtpmap.split(" ");
        int payloadType = Integer.parseInt(ss[0]);

        ss = ss[1].split("/");
        String name = ss[0];
        int sampleRate = Integer.parseInt(ss[1]);
        int channelCount = 1;
        if (ss.length > 2) channelCount = Integer.parseInt(ss[2]);
        int sampleCount = sampleRate / (1000 / ptime) * channelCount;
        return new AudioCodec(payloadType, name, sampleRate, sampleCount);
    }

    public List<AudioCodec> getAudioCodecs() {
        MediaDescription md = getMediaDescription(AUDIO);
        if (md == null) return new ArrayList<AudioCodec>();

        // FIXME: what happens if ptime is missing
        int ptime = 20;
        try {
            String value = md.getAttribute(PTIME);
            if (value != null) ptime = Integer.parseInt(value);
        } catch (Throwable t) {
            Log.w(TAG, "getCodecs(): ignored: " + t);
        }

        List<AudioCodec> codecs = new ArrayList<AudioCodec>();
        Vector<AttributeField> v = (Vector<AttributeField>)
                md.getAttributeFields();
        for (AttributeField field : v) {
            try {
                if (RTPMAP.equals(field.getName())) {
                    AudioCodec codec = parseAudioCodec(field.getValue(), ptime);
                    if (codec != null) codecs.add(codec);
                }
            } catch (Throwable t) {
                Log.w(TAG, "getCodecs(): ignored: " + t);
            }
        }
        return codecs;
    }

    public MediaDescription getMediaDescription(String typeWanted) {
        MediaDescription[] all = getMediaDescriptions();
        if ((all == null) || (all.length == 0)) return null;
        for (MediaDescription md : all) {
            String type = md.getMedia().getMedia();
            if (type.equalsIgnoreCase(typeWanted)) return md;
        }
        return null;
    }

    public MediaDescription[] getMediaDescriptions() {
        try {
            Vector<MediaDescription> descriptions = (Vector<MediaDescription>)
                    mSessionDescription.getMediaDescriptions(false);
            MediaDescription[] all = new MediaDescription[descriptions.size()];
            return descriptions.toArray(all);
        } catch (SdpException e) {
            Log.e(TAG, "getMediaDescriptions", e);
        }
        return null;
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
