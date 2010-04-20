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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

class RtpAudioSession implements RtpSession {
    private static final String TAG = RtpAudioSession.class.getSimpleName();
    private static final int AUDIO_SAMPLE_RATE = 8000;
    private static final int MAX_ALLOWABLE_LATENCY = 500; // ms

    private boolean mRunning = false;
    private RecordTask mRecordTask;
    private PlayTask mPlayTask;

    private DatagramSocket mSocket;
    private InetAddress mRemoteAddr;
    private int mRemotePort;
    private boolean mSendDtmf = false;
    private int mCodecId;

    private NoiseGenerator mNoiseGenerator = new NoiseGenerator();

    RtpAudioSession(int codecId) {
        mCodecId = codecId;
    }

    private void init(int remoteSampleRate, DatagramSocket socket) {
        mRemoteAddr = socket.getInetAddress();
        mRemotePort = socket.getPort();
        mSocket = socket;
        int localFrameSize = AUDIO_SAMPLE_RATE / 50; // 50 frames / sec
        mRecordTask = new RecordTask(AUDIO_SAMPLE_RATE, localFrameSize);
        mPlayTask = new PlayTask(remoteSampleRate, localFrameSize);
        Log.v(TAG, "create RtpSession: to connect to " + mRemoteAddr + ":"
                + mRemotePort + " using codec " + mCodecId);
    }

    public int getCodecId() {
        return mCodecId;
    }

    public int getSampleRate() {
        return AUDIO_SAMPLE_RATE;
    }

    public String getName() {
        switch (mCodecId) {
        case 8: return "PCMA";
        default: return "PCMU";
        }
    }

    public void start(int remoteSampleRate, DatagramSocket connectedSocket)
            throws IOException {
        init(remoteSampleRate, connectedSocket);
        if (mRunning) return;

        mRunning = true;
        Log.v(TAG, "start RtpSession: connect to " + mRemoteAddr + ":"
                + mRemotePort);
        if (mRemoteAddr != null) {
            mRecordTask.start(mRemoteAddr, mRemotePort);
        }
        mPlayTask.start();
    }

    public synchronized void stop() {
        mRunning = false;
        Log.v(TAG, "stop RtpSession: measured volume = "
                + mNoiseGenerator.mMeasuredVolume);
        // wait until player is stopped
        for (int i = 20; (i > 0) && !mPlayTask.isStopped(); i--) {
            Log.v(TAG, "    wait for player to stop...");
            try {
                wait(20);
            } catch (InterruptedException e) {
                // ignored
            }
        }
    }

    public void toggleMute() {
        if (mRecordTask != null) mRecordTask.toggleMute();
    }

    public boolean isMuted() {
        return ((mRecordTask != null) ? mRecordTask.isMuted() : false);
    }

    public void sendDtmf() {
        mSendDtmf = true;
    }

    private void startRecordTask(InetAddress remoteAddr, int remotePort) {
        mRemoteAddr = remoteAddr;
        mRemotePort = remotePort;
        if ((mRemoteAddr != null) && mRunning && !mSocket.isConnected()) {
            mRecordTask.start(remoteAddr, remotePort);
        }
    }

    private class PlayTask implements Runnable {
        private int mSampleRate;
        private int mFrameSize;
        private AudioPlayer mPlayer;

        PlayTask(int sampleRate, int frameSize) {
            mSampleRate = sampleRate;
            mFrameSize = frameSize;
        }

        void start() {
            new Thread(this).start();
        }

        boolean isStopped() {
            return (mPlayer == null)
                    || (mPlayer.getPlayState() == AudioTrack.PLAYSTATE_STOPPED);
        }

        public void run() {
            Decoder decoder = RtpFactory.createDecoder(mCodecId);
            int playBufferSize = decoder.getSampleCount(mFrameSize);
            short[] playBuffer = new short[playBufferSize];

            RtpReceiver receiver = new RtpReceiver(mFrameSize);
            byte[] buffer = receiver.getBuffer();
            int offset = receiver.getPayloadOffset();

            int minBufferSize = AudioTrack.getMinBufferSize(mSampleRate,
                    AudioFormat.CHANNEL_CONFIGURATION_MONO,
                    AudioFormat.ENCODING_PCM_16BIT) * 6;
            minBufferSize = Math.max(minBufferSize, playBufferSize);
            int bufferHighMark = minBufferSize / 2 * 8 / 10;
            Log.d(TAG, " play buffer = " + minBufferSize + ", high water mark="
                    + bufferHighMark);
            AudioTrack aplayer = new AudioTrack(AudioManager.STREAM_MUSIC,
                    mSampleRate, AudioFormat.CHANNEL_CONFIGURATION_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBufferSize,
                    AudioTrack.MODE_STREAM);
            AudioPlayer player = mPlayer =
                    new AudioPlayer(aplayer, minBufferSize, mFrameSize);

            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            player.play();
            int playState = player.getPlayState();
            boolean socketConnected = mSocket.isConnected();
            long receiveCount = 0;
            long cyclePeriod = mFrameSize * 1000L / mSampleRate;
            long cycleStart = 0;
            int seqNo = 0;
            int packetLossCount = 0;
            int bytesDropped = 0;

            player.flush();
            int writeHead = player.getPlaybackHeadPosition();

            // start measurement after first packet arrival
            try {
                receiver.receive();
                Log.d(TAG, "received first packet");
            } catch (IOException e) {
                Log.e(TAG, "receive error; stop player", e);
                player.stop();
                player.release();
                return;
            }
            cycleStart = System.currentTimeMillis();
            seqNo = receiver.getSequenceNumber();

            if (!socketConnected) {
                socketConnected = true;
                startRecordTask(receiver.getRemoteAddress(),
                        receiver.getRemotePort());
            }

            long startTime = System.currentTimeMillis();
            long virtualClock = startTime;
            float delta = 0f;

            while (mRunning) {
                try {
                    int count = receiver.receive();
                    int decodeCount = decoder.decode(
                            playBuffer, buffer, count, offset);
                    mNoiseGenerator.measureVolume(playBuffer, 0, decodeCount);
                    if (playState == AudioTrack.PLAYSTATE_STOPPED) {
                        player.play();
                        playState = player.getPlayState();
                    }

                    receiveCount ++;
                    int sn = receiver.getSequenceNumber();
                    int lossCount = sn - seqNo - 1;
                    if (lossCount > 0) {
                        packetLossCount += lossCount;
                        virtualClock += lossCount * cyclePeriod;
                    }
                    virtualClock += cyclePeriod;
                    long now = System.currentTimeMillis();
                    long late = now - virtualClock;
                    if ((late < 0) || (late > 1000)) {
                        if (late > 1000) {
                            Log.d(TAG, "  large delay detected: " + late
                                    + ", been muted?");
                        }
                        virtualClock = now;
                        late = 0;
                        delta = 0;
                    } else {
                        delta = delta * 0.96f + late * 0.04f;
                        if (delta > MAX_ALLOWABLE_LATENCY) {
                            delta = MAX_ALLOWABLE_LATENCY;
                        }
                        late -= (long) delta;
                    }

                    if (late  > 100) {
                        // drop
                        bytesDropped += decodeCount;
                        if (LogRateLimiter.allowLogging(now)) {
                            Log.d(TAG, " drop " + sn + ":" + decodeCount
                                    + ", late: " + late + ", d=" + delta);
                        }
                        cycleStart = now;
                        seqNo = sn;
                        continue;
                    }
                    int buffered = writeHead - player.getPlaybackHeadPosition();
                    if (buffered > bufferHighMark) {
                        player.flush();
                        buffered = 0;
                        writeHead = player.getPlaybackHeadPosition();
                        if (LogRateLimiter.allowLogging(now)) {
                            Log.d(TAG, " ~~~ flush: set writeHead to "
                                    + writeHead);
                        }
                    }

                    writeHead += player.write(playBuffer, 0, decodeCount);

                    cycleStart = now;
                    seqNo = sn;
                } catch (IOException e) {
                    Log.w(TAG, "network disconnected; playback stopped", e);
                    player.stop();
                    playState = player.getPlayState();
                }
            }
            Log.d(TAG, "     receiveCount = " + receiveCount);
            Log.d(TAG, "     # packets lost =" + packetLossCount);
            Log.d(TAG, "     bytes dropped =" + bytesDropped);
            Log.d(TAG, "stop sound playing...");
            player.stop();
            player.flush();
            player.release();
        }
    }

    private class RecordTask implements Runnable {
        private int mSampleRate;
        private int mFrameSize;
        private boolean mMuted = false;

        RecordTask(int sampleRate, int frameSize) {
            mSampleRate = sampleRate;
            mFrameSize = frameSize;
        }

        void start(InetAddress addr, int port) {
            Log.d(TAG, "start RecordTask, connect to " + addr + ":" + port);
            mSocket.connect(addr, port);
            new Thread(this).start();
        }

        void toggleMute() {
            mMuted = !mMuted;
        }

        boolean isMuted() {
            return mMuted;
        }

        private void adjustMicGain(short[] buf, int len, int factor) {
            int i,j;
            for (i = 0; i < len; i++) {
                j = buf[i];
                if (j > 32768/factor) {
                    buf[i] = 32767;
                } else if (j < -(32768/factor)) {
                    buf[i] = -32767;
                } else {
                    buf[i] = (short)(factor*j);
                }
            }
        }

        public void run() {
            Encoder encoder = RtpFactory.createEncoder(mCodecId);
            int recordBufferSize = encoder.getSampleCount(mFrameSize);
            short[] recordBuffer = new short[recordBufferSize];
            RtpSender sender = new RtpSender(mFrameSize);
            byte[] buffer = sender.getBuffer();
            int offset = sender.getPayloadOffset();

            int bufferSize = AudioRecord.getMinBufferSize(mSampleRate,
                    AudioFormat.CHANNEL_CONFIGURATION_MONO,
                    AudioFormat.ENCODING_PCM_16BIT) * 3 / 2;

            AudioRecord recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC, mSampleRate,
                    AudioFormat.CHANNEL_CONFIGURATION_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            recorder.startRecording();
            Log.d(TAG, "start sound recording..." + recorder.getState());

            // skip the first read, kick off read pipeline
            recorder.read(recordBuffer, 0, recordBufferSize);

            long sendCount = 0;
            long startTime = System.currentTimeMillis();

            while (mRunning) {
                int count = recorder.read(recordBuffer, 0, recordBufferSize);
                if (mMuted) continue;

                // TODO: remove the mic gain if the issue is fixed on Passion.
                adjustMicGain(recordBuffer, count, 16);

                int encodeCount =
                        encoder.encode(recordBuffer, count, buffer, offset);
                try {
                    sender.send(encodeCount);
                    if (mSendDtmf) {
                        recorder.stop();
                        sender.sendDtmf();
                        mSendDtmf = false;
                        recorder.startRecording();
                    }
                } catch (IOException e) {
                    if (mRunning) Log.e(TAG, "send error, stop sending", e);
                    break;
                }

                sendCount ++;
            }
            long now = System.currentTimeMillis();
            Log.d(TAG, "     sendCount = " + sendCount);
            Log.d(TAG, "     avg send cycle ="
                    + ((double) (now - startTime) / sendCount));
            Log.d(TAG, "stop sound recording...");
            recorder.stop();
            mMuted = false;
        }
    }

    private class RtpReceiver {
        RtpPacket mPacket;
        DatagramPacket mDatagram;

        RtpReceiver(int size) {
            byte[] buffer = new byte[size + 12];
            mPacket = new RtpPacket(buffer);
            mPacket.setPayloadType(mCodecId);
            mDatagram = new DatagramPacket(buffer, buffer.length);
        }

        byte[] getBuffer() {
            return mPacket.getRawPacket();
        }

        int getPayloadOffset() {
            return 12;
        }

        // return received payload size
        int receive() throws IOException {
            DatagramPacket datagram = mDatagram;
            mSocket.receive(datagram);
            return datagram.getLength() - 12;
        }

        InetAddress getRemoteAddress() {
            return mDatagram.getAddress();
        }

        int getRemotePort() {
            return mDatagram.getPort();
        }

        int getSequenceNumber() {
            return mPacket.getSequenceNumber();
        }
    }

    private class RtpSender extends RtpReceiver {
        private int mSequence = 0;
        private long mTimeStamp = 0;

        RtpSender(int size) {
            super(size);
        }

        void sendDtmf() throws IOException {
            byte[] buffer = getBuffer();

            RtpPacket packet = mPacket;
            packet.setPayloadType(101);
            packet.setPayloadLength(4);

            mTimeStamp += 160;
            packet.setTimestamp(mTimeStamp);
            DatagramPacket datagram = mDatagram;
            datagram.setLength(packet.getPacketLength());
            int duration = 480;
            buffer[12] = 1;
            buffer[13] = 0;
            buffer[14] = (byte)(duration >> 8);
            buffer[15] = (byte)duration;
            for (int i = 0; i < 3; i++) {
                packet.setSequenceNumber(mSequence++);
                mSocket.send(datagram);
                try {
                    Thread.sleep(20);
                } catch (Exception e) {
                }
            }
            mTimeStamp += 480;
            packet.setTimestamp(mTimeStamp);
            buffer[12] = 1;
            buffer[13] = (byte)0x80;
            buffer[14] = (byte)(duration >> 8);
            buffer[15] = (byte)duration;
            for (int i = 0; i < 3; i++) {
                packet.setSequenceNumber(mSequence++);
                mSocket.send(datagram);
            }
        }

        void send(int count) throws IOException {
            mTimeStamp += count;
            RtpPacket packet = mPacket;
            packet.setSequenceNumber(mSequence++);
            packet.setPayloadType(mCodecId);
            packet.setTimestamp(mTimeStamp);
            packet.setPayloadLength(count);

            DatagramPacket datagram = mDatagram;
            datagram.setLength(packet.getPacketLength());
            mSocket.send(datagram);
        }
    }

    private class NoiseGenerator {
        private static final int AMP = 1000;
        private static final int TURN_DOWN_RATE = 80;
        private static final int NOISE_LENGTH = 160;

        private short[] mNoiseBuffer = new short[NOISE_LENGTH];
        private int mMeasuredVolume = 0;

        short[] makeNoise() {
            final int len = NOISE_LENGTH;
            short volume = (short) (mMeasuredVolume / TURN_DOWN_RATE / AMP);
            double volume2 = volume * 2.0;
            int m = 8;
            for (int i = 0; i < len; i+=m) {
                short v = (short) (Math.random() * volume2);
                v -= volume;
                for (int j = 0, k = i; (j < m) && (k < len); j++, k++) {
                    mNoiseBuffer[k] = v;
                }
            }
            return mNoiseBuffer;
        }

        void measureVolume(short[] audioData, int offset, int count) {
            for (int i = 0, j = offset; i < count; i++, j++) {
                mMeasuredVolume = (mMeasuredVolume * 9
                        + Math.abs((int) audioData[j]) * AMP) / 10;
            }
        }

        int getNoiseLength() {
            return mNoiseBuffer.length;
        }
    }

    // Use another thread to play back to avoid playback blocks network
    // receiving thread
    private class AudioPlayer implements Runnable,
            AudioTrack.OnPlaybackPositionUpdateListener {
        private short[] mBuffer;
        private int mStartMarker;
        private int mEndMarker;
        private AudioTrack mTrack;
        private int mFrameSize;
        private int mOffset;
        private boolean mIsPlaying = false;
        private boolean mNotificationStarted = false;

        AudioPlayer(AudioTrack track, int bufferSize, int frameSize) {
            mTrack = track;
            mBuffer = new short[bufferSize];
            mFrameSize = frameSize;
        }

        synchronized int write(short[] buffer, int offset, int count) {
            int bufferSize = mBuffer.length;
            while (getBufferedDataSize() + count > bufferSize) {
                try {
                    wait();
                } catch (Exception e) {
                    //
                }
            }

            int end = mEndMarker % bufferSize;
            if (end + count > bufferSize) {
                int partialSize = bufferSize - end;
                System.arraycopy(buffer, offset, mBuffer, end, partialSize);
                System.arraycopy(buffer, offset + partialSize, mBuffer, 0,
                        count - partialSize);
            } else {
                System.arraycopy(buffer, 0, mBuffer, end, count);
            }
            mEndMarker += count;

            return count;
        }

        synchronized void flush() {
            mEndMarker = mStartMarker;
            notify();
        }

        int getBufferedDataSize() {
            return mEndMarker - mStartMarker;
        }

        synchronized void play() {
            if (!mIsPlaying) {
                mTrack.setPositionNotificationPeriod(mFrameSize);
                mTrack.setPlaybackPositionUpdateListener(this);
                mIsPlaying = true;
                mTrack.play();
                mOffset = mTrack.getPlaybackHeadPosition();

                // start initial noise feed, to kick off periodic notification
                new Thread(this).start();
            }
        }

        synchronized void stop() {
            mIsPlaying = false;
            mTrack.stop();
            mTrack.flush();
            mTrack.setPlaybackPositionUpdateListener(null);
        }

        synchronized void release() {
            mTrack.release();
        }

        public synchronized void run() {
            Log.d(TAG, "start initial noise feed");
            int count = 0;
            long waitTime = mNoiseGenerator.getNoiseLength() / 8; // ms
            while (!mNotificationStarted && mIsPlaying) {
                feedNoise();
                count++;
                try {
                    this.wait(waitTime);
                } catch (InterruptedException e) {
                    Log.e(TAG, "initial noise feed error: " + e);
                    break;
                }
            }
            Log.d(TAG, "stop initial noise feed: " + count);
        }

        int getPlaybackHeadPosition() {
            return mStartMarker;
        }

        int getPlayState() {
            return mTrack.getPlayState();
        }

        int getState() {
            return mTrack.getState();
        }

        // callback
        public void onMarkerReached(AudioTrack track) {
        }

        // callback
        public synchronized void onPeriodicNotification(AudioTrack track) {
            if (!mNotificationStarted) {
                mNotificationStarted = true;
                Log.d(TAG, " ~~~   notification callback started");
            } else if (!mIsPlaying) {
                Log.d(TAG, " ~x~   notification callback quit");
                return;
            }
            try {
                writeToTrack();
            } catch (IllegalStateException e) {
                Log.e(TAG, "writeToTrack()", e);
            }
        }

        private void feedNoise() {
            short[] noiseBuffer = mNoiseGenerator.makeNoise();
            mOffset += mTrack.write(noiseBuffer, 0, noiseBuffer.length);
        }

        private synchronized void writeToTrack() {
            if (mStartMarker == mEndMarker) {
                int head = mTrack.getPlaybackHeadPosition() - mOffset;
                if ((mStartMarker - head) <= 320) feedNoise();
                return;
            }

            int count = mFrameSize;
            if (count < getBufferedDataSize()) count = getBufferedDataSize();

            int bufferSize = mBuffer.length;
            int start = mStartMarker % bufferSize;
            if ((start + count) <= bufferSize) {
                mStartMarker += mTrack.write(mBuffer, start, count);
            } else {
                int partialSize = bufferSize - start;
                mStartMarker += mTrack.write(mBuffer, start, partialSize);
                mStartMarker += mTrack.write(mBuffer, 0, count - partialSize);
            }
            notify();
        }
    }

    private static class LogRateLimiter {
        private static final long MIN_TIME = 1000;
        private static long mLastTime;

        private static boolean allowLogging(long now) {
            if ((now - mLastTime) < MIN_TIME) {
                return false;
            } else {
                mLastTime = now;
                return true;
            }
        }
    }
}
