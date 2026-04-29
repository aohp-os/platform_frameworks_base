/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * @hide
 */
package com.android.internal.aohp;

/** @hide */
interface IAohpFileBridge {
    /** JSON file metadata for one path. */
    String stat(String path);

    /** JSON directory listing. optionsJson controls maxDepth, maxFiles, mime filters, etc. */
    String list(String path, String optionsJson);

    /** JSON recent-file search across root aliases or explicit /sdcard paths. */
    String recent(String optionsJson);

    /** Capture a bounded snapshot and return a snapshotId plus file summaries. */
    String snapshot(String optionsJson);

    /** Diff two in-memory snapshots created by snapshot(). */
    String diff(String beforeSnapshotId, String afterSnapshotId, String optionsJson);
}
