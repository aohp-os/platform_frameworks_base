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

import java.util.ArrayList;

/**
 * Skill-declared sensitivity from {@code SKILL.md} frontmatter JSON: sources/sinks/actions.
 */
public final class AohpSkillPolicyHelper {

    private AohpSkillPolicyHelper() {}

    public static JSONObject checkSkillOutputPolicy(String skillName, String skillJson,
            String rawOutputJson,
            AohpVaultService vault,
            AohpTaintTrackerService taint,
            AohpSecurityAuditLog audit)
            throws JSONException {
        if (TextUtils.isEmpty(skillName) || TextUtils.isEmpty(skillJson)) {
            return cloneJson(rawOutputJson);
        }
        JSONObject policy = new JSONObject(skillJson.trim().isEmpty() ? "{}" : skillJson);
        JSONArray sources = policy.optJSONArray("sources");
        if (sources == null || sources.length() == 0) {
            return cloneJson(rawOutputJson);
        }
        JSONObject out = cloneJson(rawOutputJson);
        for (int i = 0; i < sources.length(); i++) {
            JSONObject rule = sources.optJSONObject(i);
            if (rule == null) continue;
            String field = rule.optString("field", "");
            String desc = rule.optString("description", "skill_source");
            if (TextUtils.isEmpty(field)) continue;
            replaceField(out, splitPath(field),
                    vault, taint, skillName,
                    audit, desc,
                    rule.optBoolean("sensitive", false));
        }
        return out;
    }

    public static JSONObject checkSkillInputPolicy(String skillName, String skillJson,
            String paramName, String value)
            throws JSONException {
        JSONObject res = new JSONObject();
        JSONObject policy = new JSONObject(skillJson.trim().isEmpty() ? "{}" : skillJson);
        JSONArray sinks = policy.optJSONArray("sinks");
        boolean needsVaultConsent = false;
        if (value != null && value.contains("aohp://vault/")) {
            needsVaultConsent = true;
        }
        if (sinks != null) {
            for (int i = 0; i < sinks.length(); i++) {
                JSONObject s = sinks.optJSONObject(i);
                if (s == null) continue;
                String f = s.optString("field", "");
                if (!paramName.equals(f)) continue;
                if (needsVaultConsent || s.optBoolean("requireConsent", false)) {
                    res.put("mode", "CONSENT_REQUIRED");
                    res.put("reason", "skill_sink_" + paramName);
                    return res;
                }
            }
        }
        JSONArray acts = policy.optJSONArray("actions");
        if (acts != null && needsVaultConsent) {
            res.put("mode", "CONSENT_REQUIRED");
            res.put("reason", "vault_token_skill_input");
            return res;
        }
        res.put("mode", "ALLOW");
        return res;
    }

    private static JSONObject cloneJson(String raw) throws JSONException {
        if (TextUtils.isEmpty(raw)) return new JSONObject();
        Object tok = new org.json.JSONTokener(raw).nextValue();
        if (tok instanceof JSONObject) {
            return (JSONObject) tok;
        }
        JSONObject w = new JSONObject();
        w.put("value", tok);
        return w;
    }

    private static String[] splitPath(String field) {
        String[] pts = field.split("\\.");
        ArrayList<String> out = new ArrayList<>();
        for (String p : pts) {
            if (!TextUtils.isEmpty(p)) out.add(p);
        }
        return out.toArray(new String[0]);
    }

    /** Modifies JSONObject in-place along dot path segments; last segment replaced if String. */
    private static void replaceField(JSONObject root, String[] path,
            AohpVaultService vault,
            AohpTaintTrackerService taint,
            String skillName,
            AohpSecurityAuditLog audit,
            String description,
            boolean forceSensitive)
            throws JSONException {
        if (path.length == 0) return;
        Object cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            String key = path[i];
            if (!(cur instanceof JSONObject)) return;
            JSONObject o = (JSONObject) cur;
            if (!o.has(key)) return;
            cur = o.get(key);
        }
        if (!(cur instanceof JSONObject)) return;
        JSONObject parent = (JSONObject) cur;
        String leaf = path[path.length - 1];
        if (!parent.has(leaf)) return;
        Object val = parent.get(leaf);
        if (!(val instanceof String)) return;
        String s = (String) val;
        if (!forceSensitive && AohpSecurityHeuristics.matchCategory(s) == null) return;
        String cat =
                forceSensitive ? "SKILL_POLICY" : AohpSecurityHeuristics.matchCategory(s);
        String token =
                vault.storeInternal(cat != null ? cat : "SKILL_SENSITIVE", s, skillName);
        if (token == null) return;
        String tid = taint.registerTaintForVault(token, skillName,
                cat != null ? cat : "SKILL", true);
        parent.put(leaf, "[" + token + "] (" + description + ")");
        audit.add("sanitize_skill skill=" + skillName + " field=" + String.join(".", path));
        JSONObject meta = parent.optJSONObject("aohpSkillMeta");
        if (meta == null) {
            meta = new JSONObject();
            parent.put("aohpSkillMeta", meta);
        }
        meta.put(leaf + "_vaultToken", token);
        if (!TextUtils.isEmpty(tid)) {
            meta.put(leaf + "_taintId", tid);
        }
    }
}
