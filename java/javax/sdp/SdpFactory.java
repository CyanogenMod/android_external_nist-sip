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

package javax.sdp;

import java.util.*;

public class SdpFactory {
    static final long POSIX_TIME_OFFSET = (70*365 + 70/4) * 86400L;
    public SdpFactory() {}

    public static Date getDateFromNtp(long time) {
        return new Date((time - POSIX_TIME_OFFSET) * 1000);
    }

    public static long getNtpTime(Date time) {
        if (time == null) return -1;
        return time.getTime() / 1000 + POSIX_TIME_OFFSET;
    }
}
