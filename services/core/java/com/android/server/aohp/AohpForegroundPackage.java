/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.content.ComponentName;
import android.text.TextUtils;

import com.android.server.wm.ActivityTaskManagerService;
import com.android.server.wm.AohpVirtualDisplayPolicy;

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
                String a = activityPackage(d, "focusedActivity");
                if (!a.isEmpty()) {
                    return a;
                }
                return activityPackage(d, "topRunningActivity");
            }
        } catch (Exception ignored) {
        }
        String focus = AohpVirtualDisplayPolicy.getFocusPackage();
        return !TextUtils.isEmpty(focus) ? focus : "";
    }

    /**
     * {@link ActivityTaskManagerService#buildAohpDisplayRuntimeSnapshotJson} writes
     * focused/top activity as a flattened component string (e.g.
     * {@code com.android.contacts/.activities.PeopleActivity}), not a JSON object.
     */
    private static String activityPackage(JSONObject display, String field) {
        if (display == null || TextUtils.isEmpty(field)) {
            return "";
        }
        JSONObject component = display.optJSONObject(field);
        if (component != null) {
            String pkg = component.optString("packageName", "");
            if (!TextUtils.isEmpty(pkg)) {
                return pkg;
            }
        }
        return packageFromComponentString(display.optString(field, ""));
    }

    private static String packageFromComponentString(String flattened) {
        if (TextUtils.isEmpty(flattened)) {
            return "";
        }
        ComponentName cn = ComponentName.unflattenFromString(flattened);
        if (cn != null) {
            return cn.getPackageName();
        }
        int slash = flattened.indexOf('/');
        if (slash > 0) {
            return flattened.substring(0, slash);
        }
        return flattened;
    }
}
