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
import java.net.SocketException;
import java.net.UnknownHostException;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

public class AudioStream {
    private static final String TAG = AudioStream.class.getSimpleName();

    private boolean mRunning = false;
    private RecordTask mRecordTask;
    private PlayTask mPlayTask;

    private DatagramSocket mSocket;
    private InetAddress mRemoteAddr;
    private int mRemotePort;
    private int mLocalPort;

    public AudioStream(int localSampleRate, int remoteSampleRate,
            int localPort) {
        mLocalPort = localPort;
        int localFrameSize = localSampleRate / 50; // 50 frames / sec
        mRecordTask = new RecordTask(localSampleRate, localFrameSize);
        mPlayTask = new PlayTask(remoteSampleRate, localFrameSize);
    }

    public AudioStream(int localSampleRate, int remoteSampleRate, int localPort,
            String remoteIp, int remotePort) throws UnknownHostException {
        this(localSampleRate, remoteSampleRate, localPort);
        mRemoteAddr = TextUtils.isEmpty(remoteIp)
                ? null
                : InetAddress.getByName(remoteIp);
        mRemotePort = remotePort;
    }
    
    public void start() throws SocketException {
        if (mRunning) return;

        mSocket = new DatagramSocket(mLocalPort);

        mRunning = true;
        Log.v(TAG, "start AudioStream: remoteAddr=" + mRemoteAddr);
        if (mRemoteAddr != null) {
            mRecordTask.start(mRemoteAddr, mRemotePort);
        }
        mPlayTask.start();
    }

    public void stop() {
        mRunning = false;
        mSocket.close();
        // TODO: wait until both threads are stopped
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

        PlayTask(int sampleRate, int frameSize) {
            mSampleRate = sampleRate;
            mFrameSize = frameSize;
        }

        void start() {
            new Thread(this).start();
        }

        public void run() {
            Decoder decoder = new G711Codec();
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
            AudioPlayer player =
                    new AudioPlayer(aplayer, minBufferSize, mFrameSize);

            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            player.play();
            int playState = player.getPlayState();
            boolean socketConnected = mSocket.isConnected();
            long receiveCount = 0;
            long cyclePeriod = mFrameSize * 1000L / mSampleRate;
            long accumulatedExcessDelay = 0;
            long accumulatedBytes = 0;
            long bytesPerMillis = mSampleRate / 1000L;
            long cycleStart = 0, thisCycle;
            long time;
            int seqNo = 0;
            int packetLossCount = 0;
            int bytesDropped = 0;

            player.flush();
            int writeHead = player.getPlaybackHeadPosition();

            // skip the first byte
            receiver.receive();
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
                int count = receiver.receive();
                if (count > 0) {
                    try {
                        int decodeCount = decoder.decode(
                                playBuffer, buffer, count, offset);
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
                        if (late < 0) {
                            Log.d(TAG, "  move vc back: " + late);
                            virtualClock = now;
                        }

                        delta = delta * 0.96f + late * 0.04f;
                        late -= (long) delta;

                        if (late  > 100) {
                            // drop
                            bytesDropped += decodeCount;
                            Log.d(TAG, " drop " + decodeCount + ", late: "
                                    + late + ", d=" + delta);
                            cycleStart = now;
                            seqNo = sn;
                            continue;
                        }
                        int playHead = player.getPlaybackHeadPosition();
                        int buffered = writeHead - playHead;
                        if (buffered > bufferHighMark) {
                            player.flush();
                            buffered = 0;
                            writeHead = player.getPlaybackHeadPosition();
                            Log.d(TAG, " ~~~ flush: " + writeHead);
                        }

                        time = System.currentTimeMillis();
                        writeHead +=
                                player.write(playBuffer, 0, decodeCount);
                        thisCycle = time - cycleStart;

                        accumulatedExcessDelay = late;
                        accumulatedBytes = late * bytesPerMillis;

                        cycleStart = time;
                        seqNo = sn;
                    } catch (IOException e) {
                        Log.w(TAG, " ~~~ xxx ~~~    decode error: " + e);
                    }
                } else {
                    Log.w(TAG, "network disconnected; playback stopped");
                    player.stop();
                    playState = player.getPlayState();
                }
            }
            Log.d(TAG, "     receiveCount = " + receiveCount);
            Log.d(TAG, "     acc excess delay =" + accumulatedExcessDelay);
            Log.d(TAG, "     acc bytes =" + accumulatedBytes);
            Log.d(TAG, "     # packets lost =" + packetLossCount);
            Log.d(TAG, "     bytes dropped =" + bytesDropped);
            Log.d(TAG, "stop sound playing...");
            player.stop();
            player.release();
        }
    }

    private class RecordTask implements Runnable {
        private int mSampleRate;
        private int mFrameSize;

        RecordTask(int sampleRate, int frameSize) {
            mSampleRate = sampleRate;
            mFrameSize = frameSize;
        }

        void start(InetAddress addr, int port) {
            Log.d(TAG, "start RecordTask, connect to " + addr + ":" + port);
            mSocket.connect(addr, port);
            new Thread(this).start();
        }

        public void run() {
            Encoder encoder = new G711Codec();
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

                int encodeCount =
                        encoder.encode(recordBuffer, count, buffer, offset);
                sender.send(encodeCount);
                sendCount ++;
            }
            long now = System.currentTimeMillis();
            Log.d(TAG, "     sendCount = " + sendCount);
            Log.d(TAG, "     avg send cycle ="
                    + ((double) (now - startTime) / sendCount));
            Log.d(TAG, "stop sound recording...");
            recorder.stop();
        }
    }

    private class RtpReceiver {
        RtpPacket mPacket;
        DatagramPacket mDatagram;

        RtpReceiver(int size) {
            byte[] buffer = new byte[size + 12];
            mPacket = new RtpPacket(buffer);
            mPacket.setPayloadType(8);
            mDatagram = new DatagramPacket(buffer, buffer.length);
        }

        byte[] getBuffer() {
            return mPacket.getRawPacket();
        }

        int getPayloadOffset() {
            return 12;
        }

        // return received payload size
        int receive() {
            DatagramPacket datagram = mDatagram;

            try {
                mSocket.receive(datagram);
            } catch (IOException e) {
                return 0;
            }

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

        void send(int count) {
            mTimeStamp += count;
            RtpPacket packet = mPacket;
            packet.setSequenceNumber(mSequence++);
            packet.setTimestamp(mTimeStamp);
            packet.setPayloadLength(count);

            DatagramPacket datagram = mDatagram;
            datagram.setLength(packet.getPacketLength());
            try {
                mSocket.send(datagram);
            } catch (IOException e) {
                Log.w("RtpSender", "running..." + e);
            }
        }
    }

    // Use another thread to play back to avoid playback blocks network
    // receiving thread
    private class AudioPlayer implements
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

            if (!mNotificationStarted) {
                writeToTrack();
            }

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
            }
        }

        synchronized void stop() {
            mTrack.stop();
            mTrack.setPlaybackPositionUpdateListener(null);
            mIsPlaying = false;
            Log.d(TAG, "mIsPlaying set false");
        }

        synchronized void release() {
            mTrack.release();
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

        private synchronized void writeToTrack() {
            int bufferSize = mBuffer.length;
            int start = mStartMarker % bufferSize;
            int end = mEndMarker % bufferSize;
            if (end == start) {
                int head = mTrack.getPlaybackHeadPosition() - mOffset;
                if (mStartMarker == head) {
                    // feed noise; keep mTrack busy
                    // TODO: create real noise
                    mTrack.write(mBuffer, 0, mFrameSize);
                    mOffset += mFrameSize;
                }
                return;
            }

            int count = mFrameSize;
            if (count < getBufferedDataSize()) count = getBufferedDataSize();

            if ((start + count) <= bufferSize) {
                mTrack.write(mBuffer, start, count);
            } else {
                int partialSize = bufferSize - start;
                mTrack.write(mBuffer, start, partialSize);
                mTrack.write(mBuffer, 0, count - partialSize);
            }
            mStartMarker += count;
            notify();
        }
    }
}
