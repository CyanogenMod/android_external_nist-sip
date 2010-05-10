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

import gov.nist.javax.sip.clientauthutils.UserCredentials;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.io.Serializable;
import java.text.ParseException;
import javax.sip.InvalidArgumentException;
import javax.sip.ListeningPoint;
import javax.sip.PeerUnavailableException;
import javax.sip.SipFactory;
import javax.sip.address.Address;
import javax.sip.address.AddressFactory;
import javax.sip.address.SipURI;
import javax.sip.address.URI;

/**
 */
public class SipProfile implements UserCredentials, Parcelable, Serializable {
    private static final long serialVersionUID = 1L;
    private static final int DEFAULT_PORT = 5060;
    private Address mAddress;
    private String mProxyAddress;
    private String mPassword;
    private String mDomain;
    private String mProtocol = ListeningPoint.UDP;
    private String mProfileName;

    public static final Parcelable.Creator<SipProfile> CREATOR =
            new Parcelable.Creator<SipProfile>() {
                public SipProfile createFromParcel(Parcel in) {
                    return new SipProfile(in);
                }

                public SipProfile[] newArray(int size) {
                    return new SipProfile[size];
                }
            };

    public static class Builder {
        private AddressFactory mAddressFactory;
        private SipProfile mProfile = new SipProfile();
        private SipURI mUri;
        private String mDisplayName;
        private String mProxyAddress;

        {
            try {
                mAddressFactory =
                        SipFactory.getInstance().createAddressFactory();
            } catch (PeerUnavailableException e) {
                throw new RuntimeException(e);
            }
        }

        public Builder(String uriString) throws ParseException {
            URI uri = mAddressFactory.createURI(fix(uriString));
            if (uri instanceof SipURI) {
                mUri = (SipURI) uri;
            } else {
                throw new ParseException(uriString + " is not a SIP URI", 0);
            }
            mProfile.mDomain = mUri.getHost();
        }

        public Builder(String username, String serverAddress)
                throws ParseException {
            mUri = mAddressFactory.createSipURI(username, serverAddress);
            mProfile.mDomain = serverAddress;
        }

        private String fix(String uriString) {
            return (uriString.trim().toLowerCase().startsWith("sip:")
                    ? uriString
                    : "sip:" + uriString);
        }

        public Builder setProfileName(String name) {
            mProfile.mProfileName = name;
            return this;
        }

        public Builder setPassword(String password) {
            mUri.setUserPassword(password);
            return this;
        }

        public Builder setPort(int port) throws InvalidArgumentException {
            mUri.setPort(port);
            return this;
        }

        public Builder setDomain(String domain) {
            mProfile.mDomain = domain;
            return this;
        }

        public Builder setProtocol(String protocol) {
            // TODO: verify
            mProfile.mProtocol = protocol;
            return this;
        }

        public Builder setOutboundProxy(String outboundProxy) {
            mProxyAddress = outboundProxy;
            return this;
        }

        public Builder setDisplayName(String displayName) throws ParseException {
            mDisplayName = displayName;
            return this;
        }

        public SipProfile build() {
            // remove password from URI
            mProfile.mPassword = mUri.getUserPassword();
            mUri.setUserPassword(null);
            try {
                mProfile.mAddress = mAddressFactory.createAddress(
                        mDisplayName, mUri);
                if (!TextUtils.isEmpty(mProxyAddress)) {
                    SipURI uri = (SipURI)
                            mAddressFactory.createURI(fix(mProxyAddress));
                    mProfile.mProxyAddress = uri.getHost();
                }
            } catch (ParseException e) {
                // must not occur
                throw new RuntimeException(e);
            }
            return mProfile;
        }
    }

    private SipProfile() {
    }

    private SipProfile(Parcel in) {
        mAddress = (Address) in.readSerializable();
        mProxyAddress = in.readString();
        mPassword = in.readString();
        mDomain = in.readString();
        mProtocol = in.readString();
        mProfileName = in.readString();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeSerializable(mAddress);
        out.writeString(mProxyAddress);
        out.writeString(mPassword);
        out.writeString(mDomain);
        out.writeString(mProtocol);
        out.writeString(mProfileName);
    }

    public int describeContents() {
        return 0;
    }

    public SipURI getUri() {
        return (SipURI) mAddress.getURI();
    }

    public String getUriString() {
        return mAddress.getURI().toString();
    }

    public Address getSipAddress() {
        return mAddress;
    }

    public String getDisplayName() {
        return mAddress.getDisplayName();
    }

    /* UserCredentials APIs */
    public String getUserName() {
        return getUri().getUser();
    }

    public String getPassword() {
        return mPassword;
    }

    public String getSipDomain() {
        return mDomain;
    }

    public String getServerAddress() {
        return getUri().getHost();
    }

    public int getPort() {
        int port = getUri().getPort();
        return (port == -1) ? DEFAULT_PORT : port;
    }

    public String getProtocol() {
        return mProtocol;
    }

    public String getOutboundProxy() {
        if (TextUtils.isEmpty(mProxyAddress)) return mProxyAddress;
        return mProxyAddress + ":" + getPort()
                + "/" + mProtocol;
    }

    public String getProxyAddress() {
        return mProxyAddress;
    }

    public String getProfileName() {
        return mProfileName;
    }
}
