/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.Manifest;
import android.content.Context;
import android.os.Binder;
import android.text.TextUtils;

import com.android.internal.aohp.IAohpTaintTracker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks taint ids associated with vault tokens and sources. */
public final class AohpTaintTrackerService extends IAohpTaintTracker.Stub {
    public static final String SERVICE_NAME = "aohp_taint";
    private static final String PREFIX = "aohp://taint/";

    private final Context mContext;
    private final Map<String, JSONObject> mTaints = new ConcurrentHashMap<>();

    public AohpTaintTrackerService(Context context) {
        mContext = context;
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    /** In-process register taint for a stored vault token. */
    public String registerTaintForVault(String vaultToken, String sourceApp, String category,
            boolean sensitive) {
        try {
            String id = PREFIX + UUID.randomUUID();
            JSONObject o = new JSONObject();
            o.put("taintId", id);
            o.put("vaultToken", vaultToken);
            o.put("sourceApp", sourceApp != null ? sourceApp : "");
            o.put("category", category != null ? category : "");
            o.put("sensitive", sensitive);
            o.put("createdWallMs", System.currentTimeMillis());
            mTaints.put(id, o);
            return id;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String listTaintsJson(String filterSourceApp) {
        enforce();
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : mTaints.values()) {
                if (!TextUtils.isEmpty(filterSourceApp)
                        && !filterSourceApp.equals(o.optString("sourceApp"))) {
                    continue;
                }
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @Override
    public String listSensitiveTaintsJson() {
        enforce();
        return listSensitiveTaintsInner();
    }

    /** {@link AohpSecurityShellBinder} only — no Binder permission gate. */
    public String listSensitiveTaintsTrusted() {
        return listSensitiveTaintsInner();
    }

    private String listSensitiveTaintsInner() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : mTaints.values()) {
                if (o.optBoolean("sensitive", false)) {
                    arr.put(o);
                }
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    @Override
    public String getTaintJson(String taintId) {
        enforce();
        JSONObject o = mTaints.get(taintId);
        if (o == null) {
            try {
                return new JSONObject().put("error", "unknown_taint").toString();
            } catch (Exception e) {
                return "{}";
            }
        }
        return o.toString();
    }
}
