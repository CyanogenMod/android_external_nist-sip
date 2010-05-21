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

package com.android.sip.media;

import java.util.Random;

/**
 * RtpPacket implements a RTP packet.
 */
public class RtpPacket {
    private static Random sRandom = new Random();

    // |0       0       1       2       |
    // |0.......8.......6.......4.......|
    // |V PXC   MT      Seqnum (16)     |
    // |................................|
    // |Timestamp (32)                  |
    // |                                |
    // |................................|
    // | SSRC (32)                      |
    // |                                |
    // |................................|
    // | CSRC list (16 items x 32 bits) |
    // |                                |
    // |................................|
    // V: version, 2 bits
    // P: padding, 1 bit
    // X: extension, 1 bit
    // C: CSRC count, 4 bits
    // M: marker, 1 bit
    // T: payload type: 7 bits

    private byte[] packet; // RTP header + payload
    private int packetLength;

    public RtpPacket(byte[] buffer) {
        packet = buffer;
        setVersion(2);
        setPayloadType(0x0F);
        setSequenceNumber(sRandom.nextInt());
        setSscr(sRandom.nextLong());
    }

    /** Returns the RTP packet in raw bytes. */
    public byte[] getRawPacket() {
        return packet;
    }

    public int getPacketLength() {
        return packetLength;
    }

    public int getHeaderLength() {
        return (12 + 4 * getCscrCount());
    }

    public int getPayloadLength() {
        return (packetLength - getHeaderLength());
    }

    public void setPayloadLength(int length) {
        packetLength = getHeaderLength() + length;
    }

    public int getVersion() {
        return ((packet[0] >> 6) & 0x03);
    }

    public void setVersion(int v) {
        if (v > 3) throw new RuntimeException("illegal version: " + v);
        packet[0] = (byte) ((packet[0] & 0x3F) | ((v & 0x03) << 6));
    }

    int getCscrCount() {
        return (packet[0] & 0x0F);
    }

    public int getPayloadType() {
        return (packet[1] & 0x7F);
    }

    public void setPayloadType(int pt) {
        packet[1] = (byte) ((packet[1] & 0x80) | (pt & 0x7F));
    }

    public int getSequenceNumber() {
        return (int) get(2, 2);
    }

    public void setSequenceNumber(int sn) {
        set((long) sn, 2, 2);
    }

    public long getTimestamp() {
        return get(4, 4);
    }

    public void setTimestamp(long timestamp) {
        set(timestamp, 4, 4);
    }

    void setSscr(long ssrc) {
        set(ssrc, 8, 4);
    }

    private long get(int begin, int length) {
        long n = 0;
        for (int i = begin, end = i + length; i < end; i++) {
            n = (n << 8) | ((long) packet[i] & 0xFF);
        }
        return n;
    }

    private void set(long n, int begin, int length) {
        for (int i = begin + length - 1; i >= begin; i--) {
            packet[i] = (byte) (n & 0x0FFL);
            n >>= 8;
        }
    }
}
