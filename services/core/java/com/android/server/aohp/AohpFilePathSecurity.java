/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Annotates file bridge JSON with coarse path risk hints.
 */
public final class AohpFilePathSecurity {

    private AohpFilePathSecurity() {}

    public static String filterFileListJson(String rawResultJson)
            throws JSONException {
        if (TextUtils.isEmpty(rawResultJson)) {
            return rawResultJson;
        }
        Object parsed = new org.json.JSONTokener(rawResultJson).nextValue();
        annotateValue(parsed);
        if (parsed instanceof JSONObject) {
            return ((JSONObject) parsed).toString();
        }
        if (parsed instanceof JSONArray) {
            return ((JSONArray) parsed).toString();
        }
        return rawResultJson;
    }

    private static void annotateValue(Object node) throws JSONException {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (o.has("devicePath")) {
                String p = o.optString("devicePath");
                String risk = classifyPath(p);
                o.put("aohpPathRisk", risk);
            }
            JSONArray names = o.names();
            if (names == null) {
                return;
            }
            for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i);
                annotateValue(o.get(k));
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                annotateValue(a.get(i));
            }
        }
    }

    /** Returns SAFE, ANDROID_PRIVATE, CREDENTIAL_HINT, DOWNLOAD_A_SENSITIVE_HINT, SECRET_NAME */
    static String classifyPath(String path) {
        if (TextUtils.isEmpty(path)) {
            return "UNKNOWN";
        }
        String p = path.toLowerCase();
        if (p.contains("/data/data/")
                || p.contains("/storage/emulated")
                    && (p.contains("/android/data/"))) {
            return "ANDROID_APP_SCOPED";
        }
        if (p.matches(".*/\\.[^/]+$") || p.startsWith(".")) {
            return "DOTFILE";
        }
        if (p.endsWith(".pem") || p.endsWith(".ppk") || p.endsWith(".key") || p.endsWith(".p12")
                || p.endsWith(".jks")) {
            return "CREDENTIAL_EXTENSION";
        }
        if (p.contains("secret") || p.contains("/.ssh")) {
            return "SECRET_HINT";
        }
        if (p.contains("/download/aohp/security-test-secret")) {
            return "HIGH_HINT";
        }
        return "SAFE";
    }

    /** Used by bridge before issuing file.share intents. */
    public static JSONObject checkFileSharePolicy(String devicePath,
            boolean requireConsentAboveSafe) throws JSONException {
        String risk = classifyPath(devicePath);
        JSONObject out = new JSONObject();
        out.put("path", devicePath);
        out.put("aohpPathRisk", risk);
        boolean high = !"SAFE".equals(risk) && !"UNKNOWN".equals(risk);
        if (!high || !requireConsentAboveSafe) {
            out.put("mode", "ALLOW");
            return out;
        }
        out.put("mode", "CONSENT_REQUIRED");
        out.put("reason", "path_risk_" + risk);
        return out;
    }
}
