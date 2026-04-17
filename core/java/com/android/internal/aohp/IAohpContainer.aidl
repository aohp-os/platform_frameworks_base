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

    /** Template name recorded at create time (e.g. "alpine"), or empty if unknown. */
    String templateInfo(String containerName);

    /**
     * Start a long-running service inside the container (detached, logs under env/services/).
     * @return child pid, or -1 on failure.
     */
    long startService(String containerName, String serviceId, String command);

    boolean stopService(String containerName, String serviceId);

    /** JSON array of service entries. */
    String listServices(String containerName);

    /** Last {@code tailBytes} of the service log file (decoded from daemon). */
    String serviceLog(String containerName, String serviceId, int tailBytes);

    /** JSON: cgroup memory/cpu/pids usage. */
    String getUsage(String containerName);

    /** JSON: template, bind-mount hints, cgroup summary. */
    String diagnose(String containerName);
}
