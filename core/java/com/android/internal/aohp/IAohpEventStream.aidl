/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * @hide
 */
package com.android.internal.aohp;

/** @hide */
interface IAohpEventStream {
    /** Register an event stream session. Returns JSON with sessionId and nextSeq. */
    String register(String clientId, String optionsJson);

    /** Drain events visible to the session. Returns JSON and advances the session cursor. */
    String drain(String sessionId, String optionsJson);

    /** Unregister a session and release its cursor. */
    boolean unregister(String sessionId);

    /** Diagnostic JSON for active sessions and buffer state. */
    String status();

    /** SystemUI-visible heads-up display marker. */
    void recordHeadsUp(String type, String key, String packageName);
}
