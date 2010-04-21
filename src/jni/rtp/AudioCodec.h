/*
 * Copyrightm (C) 2010 The Android Open Source Project
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

#ifndef __AUDIO_CODEC_H__
#define __AUDIO_CODEC_H__

class AudioCodec
{
public:
    virtual ~AudioCodec() {}
    // Returns true if sampleCount is acceptable.
    virtual bool set(int sampleCount) = 0;
    // Returns the length of payload in bytes.
    virtual int encode(void *payload, int16_t *samples) = 0;
    // Returns the number of decoded samples.
    virtual int decode(int16_t *samples, void *payload, int length) = 0;
};

class UlawCodec : public AudioCodec
{
public:
    virtual bool set(int sampleCount) {
        mSampleCount = sampleCount;
        return true;
    }
    virtual int encode(void *payload, int16_t *samples);
    virtual int decode(int16_t *samples, void *payload, int length);
private:
    int mSampleCount;
};

class AlawCodec : public AudioCodec
{
public:
    virtual bool set(int sampleCount) {
        mSampleCount = sampleCount;
        return true;
    }
    virtual int encode(void *payload, int16_t *samples);
    virtual int decode(int16_t *samples, void *payload, int length);
private:
    int mSampleCount;
};

#endif
