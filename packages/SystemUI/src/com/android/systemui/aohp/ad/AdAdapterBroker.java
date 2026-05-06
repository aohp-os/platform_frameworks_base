/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package com.android.systemui.aohp.ad;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Broker boundary for AOHP ad adapters.
 *
 * <p>This class models the safe contract used by SystemUI tests. Real network SDKs should live in
 * a separate service / isolated process and report through this JSON-shaped boundary.</p>
 */
public final class AdAdapterBroker {
    public JSONObject load(JSONObject request) throws JSONException {
        String scenario = request != null ? request.optString("scenario", "fake_banner") : "fake_banner";
        JSONObject out = new JSONObject()
                .put("ok", true)
                .put("scenario", scenario)
                .put("testMode", true);
        if ("no_fill".equals(scenario)) {
            return out.put("result", "NO_FILL");
        }
        if ("timeout".equals(scenario)) {
            return out.put("result", "TIMEOUT");
        }
        if ("crash".equals(scenario)) {
            return out.put("result", "CRASH_SIMULATED");
        }
        if ("render_error".equals(scenario)) {
            return out.put("result", "RENDER_ERROR");
        }
        return out.put("result", "LOADED")
                .put("creativeToken", "fake-token")
                .put("creativeMarker", "AOHP_FAKE_AD_CREATIVE_MARKER");
    }

    public JSONObject report(JSONObject event) throws JSONException {
        return new JSONObject()
                .put("ok", true)
                .put("reported", true)
                .put("event", event != null ? event : new JSONObject());
    }
}
