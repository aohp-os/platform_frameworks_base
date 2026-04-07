/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * @hide
 */
package com.android.internal.aohp;

/** @hide */
interface IAohpVirtualDisplay {
    void registerSession(int displayId, int ownerUid, String ownerPackage);
    void unregisterSession();
    void setFocusPackage(String packageName);
    boolean startLauncherOnDisplay(int displayId, String packageName);
    boolean injectTap(int displayId, int x, int y);
    boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs);
    boolean injectText(int displayId, String text);
    boolean injectKeyEvent(int displayId, int keyCode);
    void applyMultiDisplayDeveloperSettings();
    /** @param extraDisplayIds optional ids to merge (e.g. app-known MediaProjection VD); may be null */
    String getDisplayRuntimeSnapshotJson(in int[] extraDisplayIds);
}
