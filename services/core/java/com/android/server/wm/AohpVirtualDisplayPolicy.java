/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.wm;

import android.app.ActivityOptions;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.view.Display;

/**
 * Virtual-display routing for AOHP when {@code ro.aohp.virtual_display_policy} is true.
 */
public final class AohpVirtualDisplayPolicy {
    private static final String PROP_ENABLED = "ro.aohp.virtual_display_policy";

    private static final Object sLock = new Object();
    /** Displays owned by the current AOHP session (multi-VD capable). */
    private static final ArraySet<Integer> sRegisteredDisplayIds = new ArraySet<>();
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
            sOwnerUid = ownerUid;
            sOwnerPackage = ownerPackage != null ? ownerPackage : "";
            sFocusPackage = sOwnerPackage;
            sRegisteredDisplayIds.add(displayId);
        }
    }

    /** Remove one display from the session (e.g. after destroyVirtualDisplay). */
    public static void unregisterDisplay(int displayId) {
        synchronized (sLock) {
            sRegisteredDisplayIds.remove(displayId);
        }
    }

    public static void unregisterSession() {
        synchronized (sLock) {
            sRegisteredDisplayIds.clear();
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

    /** @deprecated Prefer {@link #isDisplayRegisteredForOwner(int, int)} */
    @Deprecated
    public static int getRegisteredDisplayId() {
        synchronized (sLock) {
            for (int id : sRegisteredDisplayIds) {
                return id;
            }
            return Display.INVALID_DISPLAY;
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

    /** True if caller uid matches session owner and display is registered for AOHP inject. */
    public static boolean isDisplayRegisteredForOwner(int displayId, int ownerUid) {
        synchronized (sLock) {
            return ownerUid == sOwnerUid && sRegisteredDisplayIds.contains(displayId);
        }
    }

    public static boolean allowPlacementOnDisplay(RootWindowContainer root, int displayId) {
        if (!isEnabled()) {
            return false;
        }
        synchronized (sLock) {
            if (!sRegisteredDisplayIds.contains(displayId)) {
                return false;
            }
        }
        final DisplayContent dc = root.getDisplayContent(displayId);
        return dc != null;
    }

    public static void applyActivityOptionsIfNeeded(
            ActivityOptions options, ActivityRecord sourceRecord, RootWindowContainer root) {
        if (!isEnabled() || options == null) {
            return;
        }
        int sourceDisplayId = -1;
        if (sourceRecord != null) {
            sourceDisplayId = sourceRecord.getDisplayId();
        }
        synchronized (sLock) {
            if (!sRegisteredDisplayIds.contains(sourceDisplayId)) {
                return;
            }
        }
        if (root.getDisplayContent(sourceDisplayId) == null) {
            return;
        }
        options.setLaunchDisplayId(sourceDisplayId);
    }

    public static void applyLaunchParamsIfNeeded(
            ActivityRecord source,
            LaunchParamsController.LaunchParams result,
            RootWindowContainer root) {
        if (!isEnabled() || result == null || root == null) {
            return;
        }
        int sourceDisplayId = -1;
        if (source != null) {
            sourceDisplayId = source.getDisplayId();
        }
        synchronized (sLock) {
            if (!sRegisteredDisplayIds.contains(sourceDisplayId)) {
                return;
            }
        }
        final DisplayContent displayContent = root.getDisplayContent(sourceDisplayId);
        if (displayContent == null) {
            return;
        }
        result.mPreferredTaskDisplayArea = displayContent.getDefaultTaskDisplayArea();
    }
}
