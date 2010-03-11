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

package com.android.sip.media;

public interface Encoder {
    int getSampleCount(int frameSize);

    /**
     * Encodes the sample data.
     *
     * @param src sample data
     * @param count valid data length in src
     * @param result the encoded result array
     * @param offset offset of data bytes in result
     */
    int encode(short[] src, int count, byte[] result, int offset);
}
