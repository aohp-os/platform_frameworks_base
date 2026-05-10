/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import com.android.server.wm.ActivityTaskManagerService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Reads {@link ActivityTaskManagerService#buildAohpDisplayRuntimeSnapshotJson} to derive a
 * reasonable foreground package for a logical display.
 */
final class AohpForegroundPackage {
    private AohpForegroundPackage() {}

    static String forDisplay(ActivityTaskManagerService atm, int displayId) {
        if (atm == null) {
            return "";
        }
        try {
            String snap = atm.buildAohpDisplayRuntimeSnapshotJson(null);
            JSONObject root = new JSONObject(snap);
            JSONArray displays = root.optJSONArray("displays");
            if (displays == null) {
                return "";
            }
            for (int i = 0; i < displays.length(); i++) {
                JSONObject d = displays.optJSONObject(i);
                if (d == null || d.optInt("displayId", -1) != displayId) {
                    continue;
                }
                String a = componentPackage(d.optJSONObject("focusedActivity"));
                if (!a.isEmpty()) {
                    return a;
                }
                a = componentPackage(d.optJSONObject("topRunningActivity"));
                return a != null ? a : "";
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String componentPackage(JSONObject c) {
        if (c == null) {
            return "";
        }
        return c.optString("packageName", "");
    }
}
