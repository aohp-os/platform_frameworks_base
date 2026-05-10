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

import java.util.regex.Pattern;

/** Heuristic detectors for vaulting UI text when declarative registry misses. */
final class AohpSecurityHeuristics {
    private static final Pattern DIGITS_11 =
            Pattern.compile("(?<!\\d)1\\d{10}(?!\\d)");
    private static final Pattern CREDIT_CARD_LIKE =
            Pattern.compile("\\b\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}\\b");

    private AohpSecurityHeuristics() {}

    /** Returns a coarse category label or null when no heuristic matches. */
    static String matchCategory(CharSequence text) {
        if (text == null || text.length() == 0) {
            return null;
        }
        if (DIGITS_11.matcher(text).find()) {
            return "PHONE_HEURISTIC";
        }
        if (CREDIT_CARD_LIKE.matcher(text).find()) {
            return "CREDIT_CARD_HEURISTIC";
        }
        return null;
    }
}

/**
 * Applies declarative sensitivity + coarse heuristics to accessibility UI tree JSON.
 */
public final class AohpUiTreeSanitizer {

    private AohpUiTreeSanitizer() {}

    public static String filter(String rawTreeJson, String foregroundPackage, int displayId,
            AohpVaultService vault,
            AohpTaintTrackerService taint,
            AohpSensitivityRegistryService registry,
            AohpSecurityAuditLog audit) throws JSONException {
        if (TextUtils.isEmpty(rawTreeJson)) {
            return rawTreeJson;
        }
        if (TextUtils.isEmpty(foregroundPackage)) {
            return rawTreeJson;
        }
        registry.ensurePackageLoaded(foregroundPackage);

        JSONObject root = new JSONObject(rawTreeJson);
        JSONArray nodes = root.optJSONArray("nodes");
        if (nodes == null || nodes.length() == 0) {
            root.put("aohpFiltered", false);
            return root.toString();
        }
        boolean any = false;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String resourceId = node.optString("resourceId", "");
            AohpSensitivityRegistryService.FieldSpec spec =
                    registry.getField(foregroundPackage, resourceId);
            if (sanitizeField(node, "text", foregroundPackage, displayId, spec, vault, taint,
                    audit)) {
                any = true;
            }
            if (sanitizeField(node, "contentDescription", foregroundPackage, displayId, spec,
                    vault,
                    taint, audit)) {
                any = true;
            }
        }
        root.put("aohpFiltered", any);
        root.put("aohpForegroundPackage", foregroundPackage);
        root.put("aohpDisplayId", displayId);
        return root.toString();
    }

    private static boolean sanitizeField(JSONObject node, String field, String foregroundPackage,
            int displayId,
            AohpSensitivityRegistryService.FieldSpec spec,
            AohpVaultService vault,
            AohpTaintTrackerService taint,
            AohpSecurityAuditLog audit) throws JSONException {
        if (!node.has(field)) {
            return false;
        }
        String original = node.optString(field);
        if (TextUtils.isEmpty(original)) {
            return false;
        }
        boolean declSensitive = spec != null && spec.sensitive;
        String heurCat = declSensitive ? null : AohpSecurityHeuristics.matchCategory(original);
        boolean sensitive = declSensitive || heurCat != null;
        if (!sensitive) {
            return false;
        }
        String cat = declSensitive ? (spec.description.isEmpty()
                ? "DECLARED_FIELD" : spec.description.substring(0, Math.min(
                spec.description.length(), 32))) : heurCat;
        String token = vault.storeInternal(cat, original, foregroundPackage);
        if (token == null) {
            return false;
        }
        String taintId = taint.registerTaintForVault(token, foregroundPackage, cat, true);
        audit.add("sanitize_ui_tree field=" + field + " pkg=" + foregroundPackage + " display="
                + displayId + " token=" + token);
        node.put(field,
                "[" + token + "] (" + (spec != null ? spec.description : cat) + ")");
        node.put("aohpVaultToken_" + field, token);
        if (!TextUtils.isEmpty(taintId)) {
            node.put("aohpTaintId_" + field, taintId);
        }
        return true;
    }
}
