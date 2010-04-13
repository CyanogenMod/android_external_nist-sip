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

import android.os.Parcel;
import android.os.Parcelable;

import javax.sip.SipException;

public abstract class SessionDescription implements Parcelable {
    public static final Parcelable.Creator<SessionDescription> CREATOR =
            new Parcelable.Creator<SessionDescription>() {
                public SessionDescription createFromParcel(Parcel in) {
                    return new SessionDescriptionImpl(in);
                }

                public SessionDescription[] newArray(int size) {
                    return new SessionDescriptionImpl[size];
                }
            };

    public abstract String getType();
    public abstract byte[] getContent();

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(getType());
        out.writeByteArray(getContent());
    }

    public int describeContents() {
        return 0;
    }

    private static class SessionDescriptionImpl extends SessionDescription {
        private String mType;
        private byte[] mContent;

        SessionDescriptionImpl(Parcel in) {
            mType = in.readString();
            mContent = in.createByteArray();
        }

        public String getType() {
            return mType;
        }

        public byte[] getContent() {
            return mContent;
        }
    }
}
