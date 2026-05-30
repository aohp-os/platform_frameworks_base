/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.text.TextUtils;

/** Sandboxed command guardrails invoked from {@link AohpContainerService}. */
final class AohpSandboxSecurityGate {
    private AohpSandboxSecurityGate() {}

    static void checkExecCommand(String command) {
        if (TextUtils.isEmpty(command)) {
            return;
        }
        if (command.contains("aohp://vault/")) {
            throw new SecurityException("sandbox.exec must not embed vault plaintext tokens");
        }
    }
}
