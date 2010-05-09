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

package com.android.sip.rtp;


/**
 * The AudioStream class represents a RTP stream carrying audio payloads.
 */
public class AudioStream {
    private int mNative;
    private final RtpSocket mRtpSocket;

    private AudioCodec mCodec;
    private int mCodecType = -1;
    private int mDtmfType = -1;

    static {
        System.loadLibrary("siprtp");
    }

    /**
     * Creates an AudioStream on a given {@link RtpSocket}.
     *
     * @param rtpSocket The socket to send and receive RTP packets.
     * @throws IllegalStateException if the socket is not associated or is
     *     already used by another stream.
     */
    public AudioStream(RtpSocket rtpSocket) {
        rtpSocket.occupy();
        mRtpSocket = rtpSocket;
    }

    /**
     * Closes the stream. Once closed, the stream stops sending and receiving,
     * and relinquishes the ownership of the {@link RtpSocket}.
     */
    public void close() {
        stopSending();
        stopReceiving();
        mRtpSocket.vacate();
    }

    /**
     * Returns the bound {@link RtpSocket}.
     */
    public RtpSocket getRtpSocket() {
        return mRtpSocket;
    }

    /**
     * Sets the {@link AudioCodec} and the RTP payload type. According to RFC
     * 3551, the type must be in the range of 0 and 127, where 96 and above are
     * used by dynamic types. For codecs with static mappings (non-negative
     * {@link AudioCodec#defaultType}), assigning a different non-dynamic type
     * is disallowed. This method can be called at any time but only takes
     * effect after the next call to {@link #prepare()}.
     *
     * @param codec The audio codec to be used.
     * @param type The RTP payload type.
     * @throws IllegalArgumentException if the type is out of range or
     *     disallowed for this codec.
     * @throws IllegalStateException if the type is already used by DTMF.
     */
    public synchronized void setCodec(AudioCodec codec, int type) {
        if (type < 0 || type > 127) {
            throw new IllegalArgumentException("The type is out of range");
        }
        if (type != codec.defaultType && type < 96) {
            throw new IllegalArgumentException(
                    "Assigning a different non-dynamic type is not allowed");
        }
        if (type == mDtmfType) {
            throw new IllegalStateException("Codec and DTMF cannot use the same type");
        }
        mCodec = codec;
        mCodecType = type;
    }

    /**
     * Sets the RTP payload type for dual-tone multi-frequency (DTMF) digits.
     * The primary usage is to send digits to the remote gateway to perform
     * certain tasks, such as second-stage dialing. According to RFC 2833, the
     * RTP payload type for DTMF is assigned dynamically, so it must be in the
     * range of 96 and 127. One can use {@code -1} to disable DTMF and free up
     * the previous assigned mapping. This method can be called at any time but
     * only takes effect after the next call to {@link #prepare()}.
     *
     * @param type The RTP payload type or {@code -1} to disable DTMF.
     * @throws IllegalArgumentException if the type is out of range.
     * @throws IllegalStateException if the type is already used by the codec.
     * @see #sendDtmf(int)
     */
    public synchronized void setDtmf(int type) {
        if (type != -1) {
            if (type < 96 || type > 127) {
                throw new IllegalArgumentException("The type is out of range");
            }
            if (type == mCodecType) {
                throw new IllegalStateException("Codec and DTMF cannot use the same type");
            }
        }
        mDtmfType = type;
    }

    /**
     * Allocates native resources for the current configuration.
     *
     * @throws IllegalStateException if the codec is not set or the stream is
     *     already prepared.
     */
    public synchronized void prepare() {
        if (mCodec == null) {
            throw new IllegalStateException("Codec is not set");
        }
        prepare(mRtpSocket, mCodec.name, mCodec.sampleRate,
                mCodec.sampleCount, mCodecType, mDtmfType);
    }
    private native void prepare(RtpSocket rtpSocket, String codecName,
            int sampleRate, int sampleCount, int codecType, int dtmfType);

    /**
     * Returns {@code true} if the stream is already prepared.
     */
    public native synchronized boolean isPrepared();

    /**
     * Starts recording and sending encoded audio to the remote host.
     *
     * @throws IllegalStateException if the stream is not prepared.
     */
    public native synchronized void startSending();

    /**
     * Starts receiving and playing decoded audio from the remote host.
     *
     * @throws IllegalStateException if the stream is not prepared.
     */
    public native synchronized void startReceiving();

    /**
     * Sends a DTMF digit to the remote host. One must set the RTP payload type
     * for DTMF before calling this method. Currently only events {@code 0}
     * through {@code 15} are supported.
     *
     * @throws IllegalArgumentException if the event is out of range.
     * @throws IllegalStateException if the stream is not sending or DTMF is
     *     disabled.
     * @see #setDtmf(int)
     */
    public native synchronized void sendDtmf(int event);

    /**
     * Stops sending.
     */
    public native synchronized void stopSending();

    /**
     * Stops receiving.
     */
    public native synchronized void stopReceiving();

    /**
     * Releases native resources. It also stops sending and receiving. After
     * calling this method, one can call {@link #prepare()} again with or
     * without changing the configuration.
     */
    public native synchronized void release();

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }
}
