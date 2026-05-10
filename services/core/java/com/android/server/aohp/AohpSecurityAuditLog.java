/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;

/**
 * Simple in-memory ring buffer for AOHP security audit lines (system_server only).
 */
public final class AohpSecurityAuditLog {
    private static final int DEFAULT_MAX = 512;
    private final ArrayDeque<String> mLines = new ArrayDeque<>();
    /** Parallel JSON lines ({@code {"msg":"..."}}) for tooling / case runners. */
    private final ArrayDeque<String> mJsonLines = new ArrayDeque<>();
    private final int mMax;

    public AohpSecurityAuditLog() {
        mMax = DEFAULT_MAX;
    }

    /** Clears text and JSON tails (shell-only / trusted callers). */
    public synchronized void clear() {
        mLines.clear();
        mJsonLines.clear();
    }

    public synchronized void add(String line) {
        if (line == null) {
            return;
        }
        while (mLines.size() >= mMax) {
            mLines.removeFirst();
        }
        mLines.addLast(line);
        try {
            JSONObject j = new JSONObject();
            j.put("msg", line);
            while (mJsonLines.size() >= mMax) {
                mJsonLines.removeFirst();
            }
            mJsonLines.addLast(j.toString());
        } catch (JSONException ignored) {
        }
    }

    public synchronized String tail(int maxLines) {
        int n = Math.max(1, Math.min(maxLines, mMax));
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, mLines.size() - n);
        int i = 0;
        for (String s : mLines) {
            if (i++ < skip) {
                continue;
            }
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    /** Last {@code maxLines} audit entries as a JSON array of objects. */
    public synchronized String tailJson(int maxLines) throws JSONException {
        int n = Math.max(1, Math.min(maxLines, mMax));
        JSONArray arr = new JSONArray();
        int skip = Math.max(0, mJsonLines.size() - n);
        int i = 0;
        for (String s : mJsonLines) {
            if (i++ < skip) {
                continue;
            }
            arr.put(new JSONObject(s));
        }
        return arr.toString();
    }
}
