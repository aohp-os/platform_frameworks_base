/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.internal.aohp.IAohpSecurityBridge;
import com.android.internal.app.AohpConsentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central AOHP policy bridge: vault/taint/registry orchestration entry used by Binder and peers.
 */
public final class AohpSecurityBridgeService extends IAohpSecurityBridge.Stub {
    public static final String SERVICE_NAME = "aohp_security_bridge";
    private static final String ALLOW = "ALLOW";

    private static volatile AohpSecurityBridgeService sInstance;

    /**
     * Trusted in-process event stream hook: no permission check (system_server only).
     */
    public static String sanitizeEventJsonTrusted(String eventDataJson) {
        AohpSecurityBridgeService b = sInstance;
        if (b == null) {
            return eventDataJson;
        }
        return b.sanitizeEventJsonInner(eventDataJson);
    }

    private final Context mContext;
    private final AohpVaultService mVault;
    private final AohpTaintTrackerService mTaint;
    private final AohpSensitivityRegistryService mRegistry;
    private final AohpSecurityAuditLog mAudit;
    /** consentId -> PENDING | APPROVED | DENIED */
    private final ConcurrentHashMap<String, String> mConsent = new ConcurrentHashMap<>();
    /** Maps consentId issued by policy to fg pkg / resourceId / kind until user completes UI. */
    private final ConcurrentHashMap<String, ConsentPending> mConsentPendingMeta =
            new ConcurrentHashMap<>();
    /** Temporary approval after user taps Allow (same pkg/rid/kind). Value: expiry epoch ms. */
    private final ConcurrentHashMap<String, Long> mApprovalUntilMs = new ConcurrentHashMap<>();

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    private static final long APPROVAL_TTL_MS = 10 * 60 * 1000L;

    private static final class ConsentPending {
        final String foregroundPackage;
        final String resourceId;
        final String kind;

        ConsentPending(String pkg, String rid, String kind) {
            this.foregroundPackage = pkg != null ? pkg : "";
            this.resourceId = rid != null ? rid : "";
            this.kind = kind;
        }
    }

    public AohpSecurityBridgeService(Context context, AohpVaultService vault,
            AohpTaintTrackerService taint,
            AohpSensitivityRegistryService registry,
            AohpSecurityAuditLog audit) {
        mContext = context;
        mVault = vault;
        mTaint = taint;
        mRegistry = registry;
        mAudit = audit;
        sInstance = this;
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    private static JSONObject policy(String mode, String reason, String consentId)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("mode", mode);
        if (reason != null) {
            o.put("reason", reason);
        }
        if (consentId != null) {
            o.put("consentId", consentId);
        }
        return o;
    }

    private static boolean isVaultToken(String s) {
        return s != null && s.contains("aohp://vault/");
    }

    private String newConsentLocked() {
        String id = "aohp://consent/" + UUID.randomUUID();
        mConsent.put(id, "PENDING");
        return id;
    }

    private static String approvalKey(String pkg, String rid, String kind) {
        return (pkg != null ? pkg : "") + '\u0001' + (rid != null ? rid : "") + '\u0001' + kind;
    }

    private boolean hasActiveApproval(String pkg, String rid, String kind) {
        Long until = mApprovalUntilMs.get(approvalKey(pkg, rid, kind));
        return until != null && until > System.currentTimeMillis();
    }

    private void recordApproval(String pkg, String rid, String kind) {
        mApprovalUntilMs.put(
                approvalKey(pkg, rid, kind), System.currentTimeMillis() + APPROVAL_TTL_MS);
    }

    private void scheduleConsentUi(
            String consentId, String reason, String foregroundPkg, String resourceId) {
        final Intent intent =
                AohpConsentActivity.createIntent(consentId, reason, foregroundPkg, resourceId);
        mUiHandler.post(
                () -> {
                    try {
                        mContext.startActivityAsUser(intent, UserHandle.CURRENT);
                    } catch (Exception e) {
                        mAudit.add(
                                "consent_ui_launch_failed id="
                                        + consentId
                                        + " err="
                                        + e.getMessage());
                    }
                });
    }

    private static String maskTokenForAudit(String token) {
        if (token == null) {
            return "null";
        }
        if (token.length() <= 16) {
            return "***";
        }
        return token.substring(0, 10) + "…len=" + token.length();
    }

    /**
     * Invoked from {@link AohpVirtualDisplayService} without Binder permission checks.
     */
    public static String filterUiTreeTrusted(String rawTreeJson, String foregroundPackage,
            int displayId) {
        AohpSecurityBridgeService b = sInstance;
        if (b == null || TextUtils.isEmpty(foregroundPackage)) {
            try {
                JSONObject err = new JSONObject();
                err.put("aohpFiltered", false);
                err.put("aohpFilterError", "bridge_or_pkg_unavailable");
                err.put("aohpForegroundPackage", foregroundPackage);
                err.put("aohpDisplayId", displayId);
                err.put("nodes", new JSONArray());
                return err.toString();
            } catch (Exception e) {
                return rawTreeJson;
            }
        }
        return b.filterUiTreeInner(rawTreeJson, foregroundPackage, displayId);
    }

    private String filterUiTreeInner(String rawTreeJson, String foregroundPackage, int displayId) {
        try {
            return AohpUiTreeSanitizer.filter(rawTreeJson, foregroundPackage,
                    displayId, mVault, mTaint, mRegistry,
                    mAudit);
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("aohpFiltered", true);
                err.put("aohpFilterError", "exception");
                err.put("aohpForegroundPackage", foregroundPackage);
                err.put("aohpDisplayId", displayId);
                err.put("nodes", new JSONArray());
                return err.toString();
            } catch (Exception ignore) {
                return "{\"aohpFiltered\":true,\"aohpFilterError\":\"fatal\",\"nodes\":[]}";
            }
        }
    }

    @Override
    public String filterUiTreeJson(String rawTreeJson, String foregroundPackage, int displayId) {
        enforce();
        final long ident = Binder.clearCallingIdentity();
        try {
            return filterUiTreeInner(rawTreeJson, foregroundPackage, displayId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Invoked from {@link AohpVirtualDisplayService} for injectText gates.
     */
    public static String checkInputPolicyTrusted(String foregroundPackage, String targetResourceId,
            String textOrToken) {
        AohpSecurityBridgeService b = sInstance;
        if (b == null) {
            try {
                return policy("DENY", "security_bridge_unavailable", null).toString();
            } catch (Exception e) {
                return "{}";
            }
        }
        return b.checkInputPolicyInner(foregroundPackage, targetResourceId, textOrToken);
    }

    /**
     * Used by {@link AohpVirtualDisplayService} for tap-on-node gates (privileged path).
     */
    public static String checkTapPolicyTrusted(String foregroundPackage, String targetResourceId) {
        AohpSecurityBridgeService b = sInstance;
        if (b == null) {
            try {
                return policy("DENY", "security_bridge_unavailable", null).toString();
            } catch (Exception e) {
                return "{}";
            }
        }
        return b.checkTapPolicyInner(foregroundPackage, targetResourceId);
    }

    private String checkInputPolicyInner(String foregroundPackage, String targetResourceId,
            String textOrToken) {
        try {
            boolean vault = isVaultToken(textOrToken);
            if (TextUtils.isEmpty(foregroundPackage)) {
                return policy("DENY", "unknown_foreground_pkg", null).toString();
            }
            mRegistry.ensurePackageLoaded(foregroundPackage);
            if (!mRegistry.hasLoadedFieldDeclarations(foregroundPackage)) {
                return policy("DENY", "sensitivity_manifest_unavailable", null).toString();
            }
            // Block raw vault tokens injected without an explicit sink id (injectText heuristic).
            if (vault && TextUtils.isEmpty(targetResourceId)) {
                return policy("DENY", "vault_requires_target_resource", null).toString();
            }
            if (vault) {
                AohpSensitivityRegistryService.FieldSpec fs =
                        mRegistry.getField(foregroundPackage, targetResourceId);
                if (fs == null || !fs.sink) {
                    return policy("DENY", "target_not_declared_sensitive_sink", null).toString();
                }
                if (fs.sensitive) {
                    if (hasActiveApproval(foregroundPackage, targetResourceId, "INPUT_VAULT")) {
                        return policy(ALLOW, null, null).toString();
                    }
                    String cid = newConsentLocked();
                    mConsentPendingMeta.put(
                            cid,
                            new ConsentPending(foregroundPackage, targetResourceId, "INPUT_VAULT"));
                    scheduleConsentUi(cid, "sensitive_sink_vault_token", foregroundPackage,
                            targetResourceId);
                    mAudit.add("consent_needed_input_sensitive pkg=" + foregroundPackage + " rid="
                            + targetResourceId + " " + cid);
                    return policy("CONSENT_REQUIRED", "sink_sensitive", cid).toString();
                }
                return policy(ALLOW, null, null).toString();
            }
            // Plain text into sensitive sinks still goes through confirmation.
            AohpSensitivityRegistryService.FieldSpec fs =
                    mRegistry.getField(foregroundPackage, targetResourceId);
            if (fs != null && fs.sensitive && fs.sink && !TextUtils.isEmpty(textOrToken)) {
                if (hasActiveApproval(foregroundPackage, targetResourceId, "INPUT_PLAINTEXT")) {
                    return policy(ALLOW, null, null).toString();
                }
                String cid = newConsentLocked();
                mConsentPendingMeta.put(
                        cid,
                        new ConsentPending(foregroundPackage, targetResourceId, "INPUT_PLAINTEXT"));
                scheduleConsentUi(cid, "plaintext_to_sensitive_sink", foregroundPackage,
                        targetResourceId);
                mAudit.add("consent_plaintext_sensitive_sink pkg=" + foregroundPackage + " rid="
                        + targetResourceId + " " + cid);
                return policy("CONSENT_REQUIRED", "plaintext_to_sensitive_sink", cid).toString();
            }
            return policy(ALLOW, null, null).toString();
        } catch (Exception e) {
            try {
                return policy("DENY", "policy_exception", null).toString();
            } catch (Exception ignore) {
                return "{}";
            }
        }
    }

    @Override
    public String checkInputPolicy(String foregroundPackage, String targetResourceId,
            String textOrToken) {
        enforce();
        return checkInputPolicyInner(foregroundPackage, targetResourceId, textOrToken);
    }

    @Override
    public String checkTapPolicy(String foregroundPackage, String targetResourceId) {
        enforce();
        return checkTapPolicyInner(foregroundPackage, targetResourceId);
    }

    private String checkTapPolicyInner(String foregroundPackage, String targetResourceId) {
        try {
            if (TextUtils.isEmpty(targetResourceId)) {
                return policy(ALLOW, null, null).toString();
            }
            if (TextUtils.isEmpty(foregroundPackage)) {
                return policy("DENY", "unknown_foreground_pkg", null).toString();
            }
            mRegistry.ensurePackageLoaded(foregroundPackage);
            if (!mRegistry.hasLoadedFieldDeclarations(foregroundPackage)) {
                return policy("DENY", "sensitivity_manifest_unavailable", null).toString();
            }
            if (mRegistry.isAction(foregroundPackage, targetResourceId)) {
                if (hasActiveApproval(foregroundPackage, targetResourceId, "TAP_ACTION")) {
                    return policy(ALLOW, null, null).toString();
                }
                String cid = newConsentLocked();
                mConsentPendingMeta.put(
                        cid,
                        new ConsentPending(foregroundPackage, targetResourceId, "TAP_ACTION"));
                scheduleConsentUi(cid, "sensitive_action_tap", foregroundPackage, targetResourceId);
                mAudit.add(
                        "consent_tap_action pkg=" + foregroundPackage + " rid=" + targetResourceId
                                + " "
                                + cid);
                return policy("CONSENT_REQUIRED", "sensitive_action", cid).toString();
            }
            return policy(ALLOW, null, null).toString();
        } catch (Exception e) {
            try {
                return policy("DENY", "policy_exception", null).toString();
            } catch (Exception ignore) {
                return "{}";
            }
        }
    }

    @Override
    public String getConsentState(String consentId) {
        enforce();
        return mConsent.getOrDefault(consentId, "UNKNOWN");
    }

    @Override
    public String listAuditTail(int maxLines) {
        enforce();
        return mAudit.tail(maxLines <= 0 ? 100 : maxLines);
    }

    @Override
    public String resolveVaultToken(String token, String purpose) {
        enforce();
        mAudit.add(
                "resolve_vault purpose="
                        + purpose
                        + " token="
                        + maskTokenForAudit(token));
        return mVault.peekPlaintext(token);
    }

    @Override
    public String registerSkillPolicy(String skillName, String securityJson) {
        enforce();
        try {
            mRegistry.registerSkillPolicyInternal(skillName, securityJson != null ? securityJson : "{}");
            return new JSONObject().put("ok", true).toString();
        } catch (Exception ex) {
            return "{\"ok\":false}";
        }
    }

    @Override
    public String filterFileListJson(String rawResultJson) {
        enforce();
        try {
            return AohpFilePathSecurity.filterFileListJson(rawResultJson);
        } catch (Exception ex) {
            try {
                return new JSONArray().put(new JSONObject().put("mode", "DENY").put(
                        "reason", "filter_exception")).toString();
            } catch (Exception ignore) {
                return "[]";
            }
        }
    }

    @Override
    public String checkFileSharePolicy(String devicePath, String targetPackage) {
        enforce();
        try {
            JSONObject base = AohpFilePathSecurity.checkFileSharePolicy(devicePath,
                    true);
            if (!ALLOW.equals(base.optString("mode"))) {
                base.put("consentId", newConsentLocked());
            }
            base.putOpt("targetPackage", targetPackage);
            return base.toString();
        } catch (Exception e) {
            try {
                JSONObject d = new JSONObject();
                d.put("mode", "DENY");
                d.put("reason", "policy_exception");
                return d.toString();
            } catch (Exception ignore) {
                return "{\"mode\":\"DENY\"}";
            }
        }
    }

    @Override
    public String checkSkillOutputPolicy(String skillName, String rawOutputJson) {
        enforce();
        try {
            String sj = mRegistry.getSkillPolicyJsonInternal(skillName);
            JSONObject o = AohpSkillPolicyHelper.checkSkillOutputPolicy(skillName,
                    sj, rawOutputJson, mVault, mTaint, mAudit);
            return o.toString();
        } catch (Exception e) {
            try {
                JSONObject d = new JSONObject();
                d.put("mode", "DENY");
                d.put("reason", "policy_exception");
                return d.toString();
            } catch (Exception ignore) {
                return "{\"mode\":\"DENY\"}";
            }
        }
    }

    @Override
    public String checkSkillInputPolicy(String skillName, String paramName, String value) {
        enforce();
        try {
            String sj = mRegistry.getSkillPolicyJsonInternal(skillName);
            return AohpSkillPolicyHelper.checkSkillInputPolicy(skillName, sj, paramName, value)
                    .toString();
        } catch (Exception e) {
            try {
                return policy("DENY", "policy_exception", null).toString();
            } catch (Exception ignore) {
                return "{}";
            }
        }
    }

    @Override
    public String checkFileReadPolicy(String devicePath) {
        enforce();
        try {
            JSONObject o = new JSONObject();
            o.put("mode", "DENY");
            o.put("stub", true);
            o.put("reason", "file_read_policy_not_implemented");
            o.putOpt("path", devicePath);
            return o.toString();
        } catch (Exception e) {
            return "{\"mode\":\"DENY\",\"stub\":true}";
        }
    }

    @Override
    public String checkFileWritePolicy(String devicePath) {
        enforce();
        return checkFileReadPolicy(devicePath);
    }

    @Override
    public void completeConsent(String consentId, boolean approved) {
        enforce();
        completeConsentInner(consentId, approved);
    }

    /** Local shell only (see {@link AohpSecurityShellBinder}). */
    void completeConsentTrusted(String consentId, boolean approved) {
        completeConsentInner(consentId, approved);
    }

    /** Trusted consent poll for shell / automation (no Binder permission check). */
    String getConsentStateTrusted(String consentId) {
        if (consentId == null) {
            return "UNKNOWN";
        }
        return mConsent.getOrDefault(consentId, "UNKNOWN");
    }

    /**
     * Clears in-memory consent records and temporary approvals used by {@link #hasActiveApproval}.
     * Intended for eng/adb automation only ({@link AohpSecurityShellBinder}) so consent suites can
     * assume a cold policy state without waiting for {@link #APPROVAL_TTL_MS}.
     */
    void resetTestConsentStateTrusted() {
        mConsent.clear();
        mConsentPendingMeta.clear();
        mApprovalUntilMs.clear();
        mAudit.add("consent_reset_test_state");
    }

    /** Shell-only; same as {@link #registerSkillPolicy} without Binder permission enforcement. */
    String registerSkillPolicyTrusted(String skillName, String securityJson) {
        try {
            mRegistry.registerSkillPolicyInternal(
                    skillName, securityJson != null ? securityJson : "{}");
            return new JSONObject().put("ok", true).toString();
        } catch (Exception ex) {
            try {
                return new JSONObject().put("ok", false).toString();
            } catch (Exception ignore) {
                return "{\"ok\":false}";
            }
        }
    }

    /** Shell-only wrapper around {@link AohpFilePathSecurity#filterFileListJson(String)}. */
    String filterFileListJsonTrusted(String rawResultJson) {
        try {
            return AohpFilePathSecurity.filterFileListJson(rawResultJson);
        } catch (Exception ex) {
            try {
                return new JSONArray()
                        .put(new JSONObject().put("mode", "DENY").put("reason", "filter_exception"))
                        .toString();
            } catch (Exception ignore) {
                return "[]";
            }
        }
    }

    /** Shell-only; mirrors {@link #checkFileSharePolicy(String, String)} without Binder enforce. */
    String checkFileSharePolicyTrusted(String devicePath, String targetPackage) {
        try {
            JSONObject base = AohpFilePathSecurity.checkFileSharePolicy(devicePath, true);
            if (!ALLOW.equals(base.optString("mode"))) {
                base.put("consentId", newConsentLocked());
            }
            base.putOpt("targetPackage", targetPackage);
            return base.toString();
        } catch (Exception e) {
            try {
                JSONObject d = new JSONObject();
                d.put("mode", "DENY");
                d.put("reason", "policy_exception");
                return d.toString();
            } catch (Exception ignore) {
                return "{\"mode\":\"DENY\"}";
            }
        }
    }

    /** Shell-only; mirrors {@link #checkFileReadPolicy(String)}. */
    String checkFileReadPolicyTrusted(String devicePath) {
        try {
            JSONObject o = new JSONObject();
            o.put("mode", "DENY");
            o.put("stub", true);
            o.put("reason", "file_read_policy_not_implemented");
            o.putOpt("path", devicePath);
            return o.toString();
        } catch (Exception e) {
            return "{\"mode\":\"DENY\",\"stub\":true}";
        }
    }

    /** Shell-only; mirrors {@link #checkFileWritePolicy(String)}. */
    String checkFileWritePolicyTrusted(String devicePath) {
        return checkFileReadPolicyTrusted(devicePath);
    }

    /** Shell-only; mirrors {@link #checkSkillOutputPolicy(String, String)}. */
    String checkSkillOutputPolicyTrusted(String skillName, String rawOutputJson) {
        try {
            String sj = mRegistry.getSkillPolicyJsonInternal(skillName);
            JSONObject o =
                    AohpSkillPolicyHelper.checkSkillOutputPolicy(
                            skillName, sj, rawOutputJson, mVault, mTaint, mAudit);
            return o.toString();
        } catch (Exception e) {
            try {
                JSONObject d = new JSONObject();
                d.put("mode", "DENY");
                d.put("reason", "policy_exception");
                return d.toString();
            } catch (Exception ignore) {
                return "{\"mode\":\"DENY\"}";
            }
        }
    }

    /** Shell-only; mirrors {@link #checkSkillInputPolicy(String, String, String)}. */
    String checkSkillInputPolicyTrusted(String skillName, String paramName, String value) {
        try {
            String sj = mRegistry.getSkillPolicyJsonInternal(skillName);
            return AohpSkillPolicyHelper.checkSkillInputPolicy(skillName, sj, paramName, value)
                    .toString();
        } catch (Exception e) {
            try {
                return policy("DENY", "policy_exception", null).toString();
            } catch (Exception ignore) {
                return "{}";
            }
        }
    }

    /** Shell-only; mirrors {@link #sanitizeEventJson(String)} without Binder enforce. */
    String sanitizeEventJsonTrustedShell(String eventDataJson) {
        return sanitizeEventJsonInner(eventDataJson);
    }

    private void completeConsentInner(String consentId, boolean approved) {
        if (consentId == null) {
            return;
        }
        ConsentPending pending = mConsentPendingMeta.remove(consentId);
        if (approved && pending != null) {
            recordApproval(pending.foregroundPackage, pending.resourceId, pending.kind);
        }
        mConsent.put(consentId, approved ? "APPROVED" : "DENIED");
        mAudit.add("consent_complete id=" + consentId + " ok=" + approved);
    }

    @Override
    public String sanitizeEventJson(String eventDataJson) {
        enforce();
        return sanitizeEventJsonInner(eventDataJson);
    }

    private String sanitizeEventJsonInner(String eventDataJson) {
        try {
            if (TextUtils.isEmpty(eventDataJson)) {
                return eventDataJson;
            }
            JSONObject root = new JSONObject(eventDataJson);
            String pkg = root.optString("packageName");
            sanitizeStringLeaf(root, "text", pkg, "event_text");

            JSONObject n = root.optJSONObject("notification");
            if (n != null) {
                sanitizeStringLeaf(n, "title", pkg, "notification_title");
                sanitizeStringLeaf(n, "text", pkg, "notification_text");
                sanitizeStringLeaf(n, "bigText", pkg, "notification_big_text");
            }
            root.put("aohpSanitizedEvent", true);
            return root.toString();
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("aohpSanitizedEvent", false);
                err.put("aohpSanitizeError", "exception");
                return err.toString();
            } catch (Exception ignore) {
                return "{\"aohpSanitizedEvent\":false}";
            }
        }
    }

    private void sanitizeStringLeaf(JSONObject parent, String key, String pkg, String auditTag)
            throws Exception {
        if (!parent.has(key)) return;
        String val = parent.optString(key);
        if (val.length() < 8 && AohpSecurityHeuristics.matchCategory(val) == null) return;
        if (val.contains("aohp://vault/")) return; // already tokenized
        String cat = AohpSecurityHeuristics.matchCategory(val);
        boolean longEnough = val.length() >= 32;
        if (cat == null && !longEnough) return;
        String tok =
                mVault.storeInternal(cat != null ? cat : "EVENT_LONG_STRING", val,
                        TextUtils.isEmpty(pkg) ? "unknown" : pkg);
        if (tok == null) return;
        String tid = mTaint.registerTaintForVault(tok, pkg, cat != null ? cat : "EVENT", true);
        parent.put(key, "[" + tok + "]");
        if (!TextUtils.isEmpty(tid)) {
            parent.put("aohpTaintId_" + key, tid);
        }
        mAudit.add("sanitize_event " + auditTag + " pkg=" + pkg + " tok=" + tok);
    }
}
