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

/**
 * G.711 codec. This class provides a-law conversion.
 */
public class G711Codec implements Encoder, Decoder {
    // s0000000wxyz...s000wxyz
    // s0000001wxyz...s001wxyz
    // s000001wxyza...s010wxyz
    // s00001wxyzab...s011wxyz
    // s0001wxyzabc...s100wxyz
    // s001wxyzabcd...s101wxyz
    // s01wxyzabcde...s110wxyz
    // s1wxyzabcdef...s111wxyz

    private static int b8to16(int b8) {
        int shift = ((b8 & 0x70) >> 4) - 1;
        int b12 = b8 & 0xF;
        if (shift >= 0) b12 = (b12 + 0x10) << shift;
        return b12 << 4;
    }

    private static int b12to8(int b12) {
        int shift = 0;
        for (int tmp = b12 / 32; tmp > 0; tmp/=2) shift++;
        int b8 = ((b12 >> shift) & 0xF);
        if (b12 >= 16) b8 += ((shift + 1) << 4);

        // invert even bits
        return (b8 ^ 0x55);
    }

    private static byte[] table12to8 = new byte[4096];
    private static short[] table8to16 = new short[256];

    static {
        for (int i = 0; i < 2048; i++) {
            int v = b12to8(i);
            table12to8[i] = (byte) v;
            table12to8[4095 - i] = (byte) (v + 128);
        }

        for (int i = 0; i < 128; i++) {
            int v = b8to16(i);
            table8to16[i ^ 0x55] = (short) v;
            table8to16[(i + 128) ^ 0x55] = (short) ((65536 - v) & 0xFFFF);
        }
    }

    public int decode(short[] b16, byte[] b8, int count, int offset) {
        for (int i = 0, j = offset; i < count; i++, j++) {
            b16[i] = table8to16[b8[j] & 0xff];
        }
        return count;
    }
    
    public int encode(short[] b16, int count, byte[] b8, int offset) {
        for (int i = 0, j = offset; i < count; i++, j++) {
            b8[j] = table12to8[(b16[i] & 0xffff) >> 4];
        }
        return count;
    }

    public int getSampleCount(int frameSize) {
        return frameSize;
    }
}
