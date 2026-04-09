/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * @hide
 */
package com.android.internal.aohp;

/** @hide */
interface IAohpContainer {
    /** List all existing container names. */
    String[] listContainers();

    /**
     * Create a new container from the given template (e.g. "alpine").
     * @return empty string on success; otherwise a daemon error message (for UI / logcat).
     */
    String createContainer(String name, String template);

    /** Destroy a container and remove all its data. */
    boolean destroyContainer(String name);

    /** Reset a container to its initial template state. */
    boolean resetContainer(String name);

    /**
     * Execute a command synchronously inside a container.
     * @return JSON string: {"exitCode":int,"stdout":"...","stderr":"..."}
     */
    String execSync(String containerName, String command, int timeoutMs);

    /**
     * Open an interactive shell session inside a container.
     * @return A ParcelFileDescriptor for bidirectional communication.
     */
    ParcelFileDescriptor openShell(String containerName);
}
