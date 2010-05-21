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

package com.android.sip;

/**
 * Timer that can schedule events to occur even when the device is in sleep.
 * Only used internally in this package.
 */
interface WakeupTimer {
    static abstract class Factory {
        private static Factory sFactory;

        /**
         * Creates a timer object.
         */
        public abstract WakeupTimer createTimer();

        public static Factory getInstance() {
            if (sFactory == null) {
                throw new RuntimeException("No factory is available");
            }
            return sFactory;
        }

        static void setInstance(Factory factory) {
            sFactory = factory;
        }
    }

    /**
     * Stops the timer. No event can be scheduled after this method is called.
     */
    void stop();

    /**
     * Sets a timer event.
     *
     * @param delay the delay from now when the timer goes off; in milli-second
     * @param callback is called back when the timer goes off; the same
     *      callback can be specified in multiple timer events
     */
    void set(long delay, Runnable callback);

    /**
     * Cancels all the timer events with the specified callback.
     *
     * @param callback the callback
     */
    void cancel(Runnable callback);
}
