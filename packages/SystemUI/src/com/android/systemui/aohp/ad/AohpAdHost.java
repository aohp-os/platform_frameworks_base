/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.aohp.ad;

import android.view.Display;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Lightweight state holder for the AOHP human-display ad host.
 *
 * <p>Window attachment is intentionally layered behind this class so the first integration can be
 * tested through AOHPAgentDriver without allowing ad content onto AOHP virtual displays.</p>
 */
public final class AohpAdHost {
    public static final String HOST_NAME = "SystemUI AohpAdHost";

    private JSONObject mState = new JSONObject();

    public synchronized JSONObject show(JSONObject request, JSONObject creative) throws JSONException {
        int displayId = request != null ? request.optInt("displayId", Display.DEFAULT_DISPLAY)
                : Display.DEFAULT_DISPLAY;
        if (displayId != Display.DEFAULT_DISPLAY) {
            return error("agent_display_blocked", "AohpAdHost only renders on human display");
        }
        mState = new JSONObject()
                .put("ok", true)
                .put("host", HOST_NAME)
                .put("available", true)
                .put("displayId", displayId)
                .put("opportunityId", request != null ? request.optString("opportunityId", "") : "")
                .put("format", request != null ? request.optString("format", "banner") : "banner")
                .put("creative", creative != null ? creative : new JSONObject())
                .put("visibleBounds", "0,0,720,160")
                .put("visible", true)
                .put("updatedAt", System.currentTimeMillis());
        return new JSONObject(mState.toString());
    }

    public synchronized JSONObject dismiss(String reason) throws JSONException {
        mState.put("visible", false);
        mState.put("dismissReason", reason != null ? reason : "dismissed");
        mState.put("updatedAt", System.currentTimeMillis());
        return new JSONObject(mState.toString());
    }

    public synchronized JSONObject getState() throws JSONException {
        return new JSONObject(mState.toString());
    }

    private static JSONObject error(String code, String message) throws JSONException {
        return new JSONObject()
                .put("ok", false)
                .put("error", true)
                .put("code", code)
                .put("message", message);
    }
}
