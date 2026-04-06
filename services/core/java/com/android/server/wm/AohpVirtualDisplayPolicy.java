/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.wm;

import android.app.ActivityOptions;
import android.os.SystemProperties;
import android.view.Display;

/**
 * Virtual-display routing for AOHP when {@code ro.aohp.virtual_display_policy} is true.
 */
public final class AohpVirtualDisplayPolicy {
    private static final String PROP_ENABLED = "ro.aohp.virtual_display_policy";

    private static final Object sLock = new Object();
    private static int sRegisteredDisplayId = Display.INVALID_DISPLAY;
    private static int sOwnerUid = -1;
    private static String sFocusPackage = "";
    private static String sOwnerPackage = "";

    private AohpVirtualDisplayPolicy() {}

    public static boolean isEnabled() {
        if (SystemProperties.getBoolean(PROP_ENABLED, false)) {
            return true;
        }
        // Cuttlefish AOHP 等产品名含 aohp，但 vendor default.prop 未合并 ro.aohp.* 时仍为 false
        String fp = SystemProperties.get("ro.build.fingerprint", "");
        return fp != null && fp.contains("aohp");
    }

    public static void registerSession(int displayId, int ownerUid, String ownerPackage) {
        synchronized (sLock) {
            sRegisteredDisplayId = displayId;
            sOwnerUid = ownerUid;
            sOwnerPackage = ownerPackage != null ? ownerPackage : "";
            sFocusPackage = sOwnerPackage;
        }
    }

    public static void unregisterSession() {
        synchronized (sLock) {
            sRegisteredDisplayId = Display.INVALID_DISPLAY;
            sOwnerUid = -1;
            sOwnerPackage = "";
            sFocusPackage = "";
        }
    }

    public static void setFocusPackage(String packageName) {
        synchronized (sLock) {
            sFocusPackage = packageName != null ? packageName : "";
        }
    }

    public static int getRegisteredDisplayId() {
        synchronized (sLock) {
            return sRegisteredDisplayId;
        }
    }

    public static int getOwnerUid() {
        synchronized (sLock) {
            return sOwnerUid;
        }
    }

    public static String getOwnerPackage() {
        synchronized (sLock) {
            return sOwnerPackage;
        }
    }

    public static boolean allowPlacementOnDisplay(RootWindowContainer root, int displayId) {
        if (!isEnabled()) {
            return false;
        }
        final int vd;
        synchronized (sLock) {
            vd = sRegisteredDisplayId;
        }
        if (vd < 0 || displayId != vd) {
            return false;
        }
        final DisplayContent dc = root.getDisplayContent(displayId);
        return dc != null;
    }

    public static void applyActivityOptionsIfNeeded(
            ActivityOptions options, ActivityRecord sourceRecord, RootWindowContainer root) {
        if (!isEnabled() || options == null) {
            return;
        }
        final int virtualDisplayId;
        synchronized (sLock) {
            virtualDisplayId = sRegisteredDisplayId;
        }
        if (virtualDisplayId < 0) {
            return;
        }
        int sourceDisplayId = -1;
        if (sourceRecord != null) {
            sourceDisplayId = sourceRecord.getDisplayId();
        }
        if (sourceDisplayId != virtualDisplayId) {
            return;
        }
        if (root.getDisplayContent(virtualDisplayId) == null) {
            return;
        }
        options.setLaunchDisplayId(virtualDisplayId);
    }

    public static void applyLaunchParamsIfNeeded(
            ActivityRecord source,
            LaunchParamsController.LaunchParams result,
            RootWindowContainer root) {
        if (!isEnabled() || result == null || root == null) {
            return;
        }
        final int virtualDisplayId;
        synchronized (sLock) {
            virtualDisplayId = sRegisteredDisplayId;
        }
        if (virtualDisplayId < 0) {
            return;
        }
        int sourceDisplayId = -1;
        if (source != null) {
            sourceDisplayId = source.getDisplayId();
        }
        if (sourceDisplayId != virtualDisplayId) {
            return;
        }
        final DisplayContent displayContent = root.getDisplayContent(virtualDisplayId);
        if (displayContent == null) {
            return;
        }
        result.mPreferredTaskDisplayArea = displayContent.getDefaultTaskDisplayArea();
    }
}
