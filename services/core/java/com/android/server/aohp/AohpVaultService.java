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

import com.android.internal.aohp.IAohpVault;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory data vault keyed by opaque tokens (persisted SQLite can replace this impl later).
 */
public final class AohpVaultService extends IAohpVault.Stub {
    public static final String SERVICE_NAME = "aohp_vault";
    private static final String PREFIX = "aohp://vault/";

    private final Context mContext;
    /** token -> plaintext + metadata */
    private final Map<String, Entry> mEntries = new ConcurrentHashMap<>();

    private static final class Entry {
        final String token;
        final String category;
        final String plaintext;
        final String sourcePackage;
        final long createdWallMs;

        Entry(String token, String category, String plaintext, String sourcePackage) {
            this.token = token;
            this.category = category;
            this.plaintext = plaintext;
            this.sourcePackage = sourcePackage;
            this.createdWallMs = System.currentTimeMillis();
        }
    }

    public AohpVaultService(Context context) {
        mContext = context;
    }

    private void enforce() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    /** Called from system_server security bridge only (in-process); not on Binder path. */
    public String storeInternal(String category, String plaintext, String sourcePackage) {
        if (TextUtils.isEmpty(plaintext)) {
            return null;
        }
        String id = UUID.randomUUID().toString();
        String token = PREFIX + id;
        Entry e = new Entry(token, category != null ? category : "GENERIC", plaintext,
                sourcePackage != null ? sourcePackage : "");
        mEntries.put(token, e);
        return token;
    }

    public String peekPlaintext(String token) {
        Entry e = mEntries.get(token);
        return e != null ? e.plaintext : null;
    }

    @Override
    public String listEntriesJson() {
        enforce();
        final long ident = Binder.clearCallingIdentity();
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : mEntries.values()) {
                JSONObject o = new JSONObject();
                o.put("token", e.token);
                o.put("category", e.category);
                o.put("sourcePackage", e.sourcePackage);
                o.put("createdWallMs", e.createdWallMs);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception ex) {
            return "[]";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String getInfoJson(String token) {
        enforce();
        try {
            Entry e = mEntries.get(token);
            if (e == null) {
                return new JSONObject().put("error", "unknown_token").toString();
            }
            JSONObject o = new JSONObject();
            o.put("token", e.token);
            o.put("category", e.category);
            o.put("sourcePackage", e.sourcePackage);
            o.put("createdWallMs", e.createdWallMs);
            return o.toString();
        } catch (Exception ex) {
            return "{\"error\":\"info_failed\"}";
        }
    }

    @Override
    public void revoke(String token) {
        enforce();
        mEntries.remove(token);
    }
}
