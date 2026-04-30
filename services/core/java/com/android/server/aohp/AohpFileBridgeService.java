/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.Manifest;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.aohp.IAohpFileBridge;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * System-side bounded file lookup for AOHP automation.
 *
 * <p>The first implementation intentionally limits explicit paths to public external storage
 * (/sdcard or /storage/emulated/0). Future debug-only private app storage diagnostics should be
 * added as a separate, explicitly gated mode.</p>
 */
public final class AohpFileBridgeService extends IAohpFileBridge.Stub {
    private static final String TAG = "AohpFileBridgeService";
    public static final String SERVICE_NAME = "aohp_file_bridge";

    private static final int DEFAULT_MAX_DEPTH = 4;
    private static final int DEFAULT_MAX_FILES = 2000;
    private static final long DEFAULT_WINDOW_MS = 30_000L;

    private final Context mContext;
    private final Map<String, Snapshot> mSnapshots = new HashMap<>();

    public AohpFileBridgeService(Context context) {
        mContext = context;
    }

    private void enforcePermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    @Override
    public String stat(String path) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            File f = resolveAllowedPath(path);
            if (f == null) {
                return error("path_not_allowed", path);
            }
            JSONObject o = ok();
            o.put("file", fileJson(f, "direct", 0.5));
            return o.toString();
        } catch (Exception e) {
            Log.w(TAG, "stat", e);
            return error("stat_failed", e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String list(String path, String optionsJson) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            Options opts = Options.parse(optionsJson);
            File root = resolveAllowedPath(path);
            if (root == null) {
                return error("path_not_allowed", path);
            }
            long started = System.currentTimeMillis();
            DirectoryListResult list = listDirectory(root, opts);
            JSONObject out = ok();
            out.put("path", toDevicePath(root));
            out.put("devicePath", toDevicePath(root));
            out.put("displayName", root.getName());
            out.put("isDirectory", root.isDirectory());
            out.put("partial", list.partial);
            out.put("elapsedMs", System.currentTimeMillis() - started);
            out.put("files", list.files);
            out.put("listStatus", list.status);
            out.put("rootStats", new JSONArray()
                    .put(rootStat(root, list.visited, System.currentTimeMillis() - started,
                            list.status)));
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "list", e);
            return error("list_failed", e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private DirectoryListResult listDirectory(File path, Options opts) throws JSONException {
        DirectoryListResult result = new DirectoryListResult();
        File dir = path.isDirectory() ? path : path.getParentFile();
        if (dir == null || !dir.exists()) {
            appendMediaStoreDirectory(dir, path, opts, result, new HashSet<>());
            result.status = result.files.length() > 0 ? "ok" : "missing";
            return result;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            ArrayList<File> nio = listDirectoryChildrenWithNio(dir);
            if (nio == null) {
                appendMediaStoreDirectory(dir, path, opts, result, new HashSet<>());
                result.status = result.files.length() > 0 ? "ok" : "unreadable";
                return result;
            }
            children = nio.toArray(new File[0]);
        } else if (children.length == 0) {
            // listFiles() can return [] on some FUSE/external-storage stacks while the directory
            // still has entries; try NIO (same as the null case above).
            ArrayList<File> nio = listDirectoryChildrenWithNio(dir);
            if (nio != null && !nio.isEmpty()) {
                children = nio.toArray(new File[0]);
            }
        }
        ArrayList<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, children);
        sorted.sort(Comparator
                .comparing((File f) -> !f.isDirectory())
                .thenComparing((File f) -> f.isDirectory() ? f.getName().toLowerCase(Locale.US) : "")
                .thenComparingLong((File f) -> f.isDirectory() ? 0L : -f.lastModified())
                .thenComparing((File f) -> f.getName().toLowerCase(Locale.US)));
        HashSet<String> seen = new HashSet<>();
        for (File child : sorted) {
            if (result.visited >= opts.maxFiles) {
                result.partial = true;
                break;
            }
            if (!child.isDirectory() && !matchesMime(null, child, opts)) {
                continue;
            }
            JSONObject o = fileJson(child, "directory", child.isDirectory() ? 0.2 : 0.5);
            if (child.isDirectory()) {
                File[] nested = child.listFiles();
                o.put("childCount", nested != null ? nested.length : JSONObject.NULL);
            }
            o.put("selected", sameFile(child, path));
            result.files.put(o);
            seen.add(toDevicePath(child));
            result.visited++;
        }
        appendMediaStoreDirectory(dir, path, opts, result, seen);
        return result;
    }

    /**
     * When {@link File#listFiles()} returns null or an empty array incorrectly (FUSE / external
     * storage edge cases on some builds), try a java.nio directory stream under cleared identity.
     */
    private static ArrayList<File> listDirectoryChildrenWithNio(File dir) {
        try {
            ArrayList<File> out = new ArrayList<>();
            Path p = dir.toPath();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(p)) {
                for (Path child : stream) {
                    out.add(child.toFile());
                }
            }
            return out;
        } catch (IOException e) {
            Log.d(TAG, "listDirectoryChildrenWithNio failed: " + e.getMessage());
            return null;
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    private void appendMediaStoreDirectory(File dir, File selected, Options opts,
            DirectoryListResult result, Set<String> seen) throws JSONException {
        if (dir == null) return;
        String dirPath = dir.getAbsolutePath();
        while (dirPath.length() > 1 && dirPath.endsWith("/")) {
            dirPath = dirPath.substring(0, dirPath.length() - 1);
        }
        if (!isAllowedExternalPath(dirPath)) return;

        Uri uri = MediaStore.Files.getContentUri("external");
        String[] projection = new String[] {
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
        };
        String selection = MediaStore.MediaColumns.DATA + " LIKE ?";
        String[] args = new String[] { dirPath + "/%" };
        ArrayList<JSONObject> rows = new ArrayList<>();
        try (Cursor c = mContext.getContentResolver().query(uri, projection, selection, args,
                MediaStore.MediaColumns.DISPLAY_NAME + " COLLATE NOCASE ASC")) {
            if (c == null) return;
            int idIdx = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
            int dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            while (c.moveToNext() && result.visited + rows.size() < opts.maxFiles) {
                String data = dataIdx >= 0 ? c.getString(dataIdx) : null;
                if (TextUtils.isEmpty(data) || !data.startsWith(dirPath + "/")) continue;
                String childName = data.substring(dirPath.length() + 1);
                if (childName.isEmpty() || childName.contains("/")) continue;
                if (!isAllowedExternalPath(data)) continue;
                File f = new File(data);
                if (!f.exists()) continue;
                String mime = mimeIdx >= 0 ? c.getString(mimeIdx) : null;
                if (!matchesMime(mime, f, opts)) continue;
                String devicePath = toDevicePath(f);
                if (seen.contains(devicePath)) continue;

                JSONObject o = new JSONObject();
                o.put("devicePath", devicePath);
                o.put("contentUri", idIdx >= 0
                        ? Uri.withAppendedPath(uri, String.valueOf(c.getLong(idIdx))).toString()
                        : JSONObject.NULL);
                o.put("displayName", displayNameFromMediaStore(
                        nameIdx >= 0 ? c.getString(nameIdx) : null, f));
                o.put("mimeType", !TextUtils.isEmpty(mime) ? mime : guessMime(f));
                o.put("size", sizeFromMediaStore(sizeIdx >= 0 ? c.getLong(sizeIdx) : 0L, f));
                o.put("lastModified", lastModifiedFromMediaStore(
                        modIdx >= 0 ? c.getLong(modIdx) : 0L, f));
                o.put("isDirectory", false);
                o.put("exists", f.exists());
                o.put("source", "mediastore");
                o.put("confidence", 0.5);
                o.put("selected", sameFile(f, selected));
                rows.add(o);
                seen.add(devicePath);
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore directory query failed", e);
            return;
        }
        rows.sort(Comparator.comparing(
                (JSONObject o) -> o.optString("displayName", "").toLowerCase(Locale.US)));
        for (JSONObject row : rows) {
            if (result.visited >= opts.maxFiles) {
                result.partial = true;
                return;
            }
            result.files.put(row);
            result.visited++;
        }
    }

    @Override
    public String recent(String optionsJson) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            long started = System.currentTimeMillis();
            Options opts = Options.parse(optionsJson);
            ScanResult scan = new ScanResult();
            queryMediaStoreRecent(opts, scan);
            for (File root : resolveRoots(opts)) {
                if (scan.visited >= opts.maxFiles) {
                    scan.partial = true;
                    break;
                }
                scanRoot(root, opts, scan, 0);
            }
            JSONObject out = ok();
            out.put("partial", scan.partial);
            out.put("rootStats", scan.rootStats);
            out.put("elapsedMs", System.currentTimeMillis() - started);
            JSONArray candidates = scan.toSortedArray();
            out.put("candidates", candidates);
            if (candidates.length() > 0) {
                out.put("best", candidates.getJSONObject(0));
                out.put("detected", true);
            } else {
                out.put("detected", false);
                out.put("reason", scan.partial ? "partial_timeout" : "no_change_in_window");
            }
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "recent", e);
            return error("recent_failed", e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String snapshot(String optionsJson) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            Options opts = Options.parse(optionsJson);
            ScanResult scan = new ScanResult();
            for (File root : resolveRoots(opts)) {
                scanRoot(root, opts.withoutWindow(), scan, 0);
            }
            String id = UUID.randomUUID().toString();
            Snapshot snap = new Snapshot();
            snap.id = id;
            snap.files.putAll(scan.files);
            snap.createdAt = System.currentTimeMillis();
            synchronized (mSnapshots) {
                mSnapshots.put(id, snap);
            }
            JSONObject out = ok();
            out.put("snapshotId", id);
            out.put("createdAt", snap.createdAt);
            out.put("partial", scan.partial);
            out.put("files", scan.toSortedArray());
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "snapshot", e);
            return error("snapshot_failed", e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String diff(String beforeSnapshotId, String afterSnapshotId, String optionsJson) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            Snapshot before;
            Snapshot after;
            synchronized (mSnapshots) {
                before = mSnapshots.get(beforeSnapshotId);
                after = mSnapshots.get(afterSnapshotId);
            }
            if (before == null || after == null) {
                return error("snapshot_not_found", "before=" + beforeSnapshotId + " after=" + afterSnapshotId);
            }
            JSONArray created = new JSONArray();
            JSONArray modified = new JSONArray();
            JSONArray deleted = new JSONArray();
            for (Map.Entry<String, JSONObject> e : after.files.entrySet()) {
                JSONObject b = before.files.get(e.getKey());
                if (b == null) {
                    created.put(e.getValue());
                } else if (b.optLong("lastModified") != e.getValue().optLong("lastModified")
                        || b.optLong("size") != e.getValue().optLong("size")) {
                    modified.put(e.getValue());
                }
            }
            for (Map.Entry<String, JSONObject> e : before.files.entrySet()) {
                if (!after.files.containsKey(e.getKey())) {
                    deleted.put(e.getValue());
                }
            }
            JSONObject out = ok();
            out.put("created", created);
            out.put("modified", modified);
            out.put("deleted", deleted);
            JSONArray candidates = new JSONArray();
            for (int i = 0; i < created.length(); i++) candidates.put(created.getJSONObject(i));
            for (int i = 0; i < modified.length(); i++) candidates.put(modified.getJSONObject(i));
            out.put("candidates", candidates);
            out.put("detected", candidates.length() > 0);
            if (candidates.length() > 0) {
                out.put("best", candidates.getJSONObject(0));
            } else {
                out.put("reason", "no_change_in_window");
            }
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "diff", e);
            return error("diff_failed", e.getMessage());
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void queryMediaStoreRecent(Options opts, ScanResult scan) {
        Uri uri = MediaStore.Files.getContentUri("external");
        String[] projection = new String[] {
                MediaStore.Files.FileColumns._ID,
                MediaStore.MediaColumns.DATA,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
        };
        long sinceSec = Math.max(0L, opts.sinceMs / 1000L);
        String selection = MediaStore.MediaColumns.DATE_MODIFIED + ">=?";
        String[] args = new String[] { String.valueOf(sinceSec) };
        try (Cursor c = mContext.getContentResolver().query(uri, projection, selection, args,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (c == null) return;
            int idIdx = c.getColumnIndex(MediaStore.Files.FileColumns._ID);
            int dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int modIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            while (c.moveToNext() && scan.visited < opts.maxFiles) {
                String data = dataIdx >= 0 ? c.getString(dataIdx) : null;
                if (TextUtils.isEmpty(data) || !isAllowedExternalPath(data)) continue;
                File f = new File(data);
                if (!matchesRoots(f, opts) || !matchesMime(mimeIdx >= 0 ? c.getString(mimeIdx) : null, f, opts)) {
                    continue;
                }
                String devicePath = toDevicePath(f);
                long size = sizeFromMediaStore(sizeIdx >= 0 ? c.getLong(sizeIdx) : 0L, f);
                long lastModified = lastModifiedFromMediaStore(
                        modIdx >= 0 ? c.getLong(modIdx) : 0L, f);
                JSONObject o = new JSONObject();
                o.put("devicePath", devicePath);
                o.put("displayName", displayNameFromMediaStore(
                        nameIdx >= 0 ? c.getString(nameIdx) : null, f));
                o.put("mimeType", mimeIdx >= 0 ? c.getString(mimeIdx) : guessMime(f));
                o.put("size", size);
                o.put("lastModified", lastModified);
                o.put("contentUri", Uri.withAppendedPath(uri, String.valueOf(c.getLong(idIdx))).toString());
                o.put("source", "mediastore");
                o.put("confidence", score(devicePath, lastModified, size, opts, "mediastore"));
                scan.add(f, o);
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaStore recent query failed", e);
        }
    }

    private void scanRoot(File root, Options opts, ScanResult scan, int depth) throws JSONException {
        long started = System.currentTimeMillis();
        int before = scan.visited;
        if (root == null || !root.exists()) {
            scan.rootStats.put(rootStat(root, 0, 0, "missing"));
            return;
        }
        scanFileOrDir(root, opts, scan, depth);
        scan.rootStats.put(rootStat(root, scan.visited - before,
                System.currentTimeMillis() - started, "ok"));
    }

    private void scanFileOrDir(File f, Options opts, ScanResult scan, int depth) throws JSONException {
        if (scan.visited >= opts.maxFiles) {
            scan.partial = true;
            return;
        }
        if (f == null || !f.exists()) return;
        if (f.isFile()) {
            scan.visited++;
            if (f.lastModified() < opts.sinceMs || !matchesMime(null, f, opts)) return;
            JSONObject o = fileJson(f, "direct", score(f, opts, "direct"));
            scan.add(f, o);
            return;
        }
        if (!f.isDirectory() || depth > opts.maxDepth) return;
        File[] children = f.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (scan.visited >= opts.maxFiles) {
                scan.partial = true;
                return;
            }
            scanFileOrDir(child, opts, scan, depth + 1);
        }
    }

    private JSONObject fileJson(File f, String source, double confidence) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("devicePath", toDevicePath(f));
        o.put("contentUri", JSONObject.NULL);
        o.put("displayName", f.getName());
        o.put("mimeType", guessMime(f));
        o.put("size", f.exists() ? f.length() : 0L);
        o.put("lastModified", f.exists() ? f.lastModified() : 0L);
        o.put("isDirectory", f.isDirectory());
        o.put("exists", f.exists());
        o.put("source", source);
        o.put("confidence", confidence);
        return o;
    }

    private static String displayNameFromMediaStore(String mediaName, File f) {
        String fileName = f != null ? f.getName() : "";
        if (!TextUtils.isEmpty(fileName)) {
            return fileName;
        }
        return !TextUtils.isEmpty(mediaName) ? mediaName : "";
    }

    private static long sizeFromMediaStore(long mediaSize, File f) {
        long fileSize = f != null && f.exists() ? f.length() : 0L;
        return fileSize > 0L || mediaSize <= 0L ? fileSize : mediaSize;
    }

    private static long lastModifiedFromMediaStore(long mediaModifiedSec, File f) {
        long mediaModifiedMs = mediaModifiedSec > 0L ? mediaModifiedSec * 1000L : 0L;
        long fileModifiedMs = f != null && f.exists() ? f.lastModified() : 0L;
        return Math.max(mediaModifiedMs, fileModifiedMs);
    }

    private static JSONObject rootStat(File root, int visited, long elapsedMs, String status)
            throws JSONException {
        JSONObject o = new JSONObject();
        o.put("root", root != null ? root.getAbsolutePath() : "");
        o.put("path", root != null ? root.getAbsolutePath() : "");
        o.put("visited", visited);
        o.put("elapsedMs", elapsedMs);
        o.put("status", status);
        return o;
    }

    private List<File> resolveRoots(Options opts) {
        ArrayList<File> roots = new ArrayList<>();
        for (String r : opts.roots) {
            if (TextUtils.isEmpty(r)) continue;
            String key = r.trim();
            if (key.startsWith("/")) {
                File f = resolveAllowedPath(key);
                if (f != null) roots.add(f);
                continue;
            }
            for (String p : aliasToPaths(key)) {
                File f = resolveAllowedPath(p);
                if (f != null) roots.add(f);
            }
        }
        if (roots.isEmpty()) {
            roots.addAll(defaultRoots());
        }
        return roots;
    }

    private List<File> defaultRoots() {
        ArrayList<File> roots = new ArrayList<>();
        for (String alias : new String[] {"downloads", "pictures", "dcim", "documents", "screenshots"}) {
            for (String p : aliasToPaths(alias)) {
                File f = resolveAllowedPath(p);
                if (f != null) roots.add(f);
            }
        }
        return roots;
    }

    private static String[] aliasToPaths(String alias) {
        String a = alias.toLowerCase(Locale.US);
        switch (a) {
            case "downloads":
            case "download":
                return new String[] {"/sdcard/Download", "/sdcard/Downloads"};
            case "pictures":
                return new String[] {"/sdcard/Pictures"};
            case "dcim":
                return new String[] {"/sdcard/DCIM"};
            case "movies":
                return new String[] {"/sdcard/Movies"};
            case "music":
                return new String[] {"/sdcard/Music"};
            case "documents":
                return new String[] {"/sdcard/Documents"};
            case "screenshots":
                return new String[] {"/sdcard/Pictures/Screenshots", "/sdcard/DCIM/Screenshots"};
            case "bluetooth":
                return new String[] {"/sdcard/Bluetooth"};
            case "recordings":
                return new String[] {"/sdcard/Recordings"};
            case "aohp":
                return new String[] {"/sdcard/Download/AOHP"};
            case "allpublic":
                return new String[] {"/sdcard/Download", "/sdcard/Downloads", "/sdcard/Pictures",
                        "/sdcard/DCIM", "/sdcard/Movies", "/sdcard/Music", "/sdcard/Documents",
                        "/sdcard/Pictures/Screenshots", "/sdcard/DCIM/Screenshots",
                        "/sdcard/Bluetooth", "/sdcard/Recordings", "/sdcard/Download/AOHP"};
            default:
                return new String[] {"/sdcard/" + alias};
        }
    }

    private static File resolveAllowedPath(String path) {
        if (TextUtils.isEmpty(path)) return null;
        String p = path.trim();
        if (p.startsWith("/sdcard")) {
            p = Environment.getExternalStorageDirectory().getAbsolutePath() + p.substring("/sdcard".length());
        }
        if (!isAllowedExternalPath(p)) return null;
        return new File(p);
    }

    private static boolean isAllowedExternalPath(String path) {
        if (TextUtils.isEmpty(path)) return false;
        String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
        return path.equals(ext) || path.startsWith(ext + "/") || path.startsWith("/storage/emulated/0/");
    }

    private static String toDevicePath(File f) {
        String p = f.getAbsolutePath();
        String ext = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (p.equals(ext)) return "/sdcard";
        if (p.startsWith(ext + "/")) return "/sdcard" + p.substring(ext.length());
        return p;
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        return toDevicePath(a).equals(toDevicePath(b));
    }

    private static boolean matchesRoots(File f, Options opts) {
        if (opts.roots.isEmpty()) return true;
        String path = f.getAbsolutePath();
        for (String r : opts.roots) {
            if (r.startsWith("/")) {
                File root = resolveAllowedPath(r);
                if (root != null && path.startsWith(root.getAbsolutePath())) return true;
            } else {
                for (String rp : aliasToPaths(r)) {
                    File root = resolveAllowedPath(rp);
                    if (root != null && path.startsWith(root.getAbsolutePath())) return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesMime(String mime, File f, Options opts) {
        if (opts.mimeTypes.isEmpty()) return true;
        String actual = !TextUtils.isEmpty(mime) ? mime : guessMime(f);
        for (String wanted : opts.mimeTypes) {
            if (wanted.endsWith("/*")) {
                String prefix = wanted.substring(0, wanted.length() - 1);
                if (actual.startsWith(prefix)) return true;
            } else if (wanted.equalsIgnoreCase(actual)) {
                return true;
            }
        }
        return false;
    }

    private static String guessMime(File f) {
        String name = f.getName().toLowerCase(Locale.US);
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }

    private static double score(File f, Options opts, String source) {
        return score(toDevicePath(f), f.lastModified(), f.length(), opts, source);
    }

    private static double score(String devicePath, long lastModified, long size, Options opts,
            String source) {
        double s = "mediastore".equals(source) ? 0.55 : 0.35;
        long age = Math.max(0L, System.currentTimeMillis() - lastModified);
        if (age <= opts.windowMs) s += 0.3;
        if (size > 0) s += 0.1;
        String path = devicePath.toLowerCase(Locale.US);
        if (path.contains("/download") || path.contains("/pictures") || path.contains("/dcim")) s += 0.05;
        return Math.min(0.99, s);
    }

    private static JSONObject ok() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        return o;
    }

    private static String error(String code, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", true);
            o.put("code", code);
            o.put("message", message != null ? message : code);
            return o.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"error\":true}";
        }
    }

    private static final class Options {
        final List<String> roots = new ArrayList<>();
        final Set<String> mimeTypes = new HashSet<>();
        long windowMs = DEFAULT_WINDOW_MS;
        long sinceMs = System.currentTimeMillis() - DEFAULT_WINDOW_MS;
        int maxDepth = DEFAULT_MAX_DEPTH;
        int maxFiles = DEFAULT_MAX_FILES;

        static Options parse(String json) {
            Options o = new Options();
            try {
                JSONObject j = TextUtils.isEmpty(json) ? new JSONObject() : new JSONObject(json);
                readStrings(j, "roots", o.roots);
                readStrings(j, "mimeTypes", o.mimeTypes);
                readStrings(j, "mime", o.mimeTypes);
                o.windowMs = j.optLong("windowMs", DEFAULT_WINDOW_MS);
                if (j.has("sinceMs")) {
                    o.sinceMs = j.optLong("sinceMs");
                } else {
                    o.sinceMs = System.currentTimeMillis() - o.windowMs;
                }
                o.maxDepth = j.optInt("maxDepth", DEFAULT_MAX_DEPTH);
                o.maxFiles = j.optInt("maxFiles", DEFAULT_MAX_FILES);
            } catch (JSONException ignored) {
            }
            if (o.roots.isEmpty()) {
                Collections.addAll(o.roots, "downloads", "pictures", "dcim", "documents", "screenshots");
            }
            return o;
        }

        Options withoutWindow() {
            Options o = new Options();
            o.roots.addAll(roots);
            o.mimeTypes.addAll(mimeTypes);
            o.windowMs = Long.MAX_VALUE / 4;
            o.sinceMs = 0L;
            o.maxDepth = maxDepth;
            o.maxFiles = maxFiles;
            return o;
        }

        private static void readStrings(JSONObject j, String key, List<String> out) throws JSONException {
            Object v = j.opt(key);
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    String s = a.optString(i, "");
                    if (!TextUtils.isEmpty(s)) out.add(s);
                }
            } else if (v instanceof String) {
                String[] parts = ((String) v).split(",");
                for (String p : parts) {
                    String s = p.trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
        }

        private static void readStrings(JSONObject j, String key, Set<String> out) throws JSONException {
            ArrayList<String> list = new ArrayList<>();
            readStrings(j, key, list);
            out.addAll(list);
        }
    }

    private static final class ScanResult {
        final Map<String, JSONObject> files = new LinkedHashMap<>();
        final JSONArray rootStats = new JSONArray();
        int visited;
        boolean partial;

        void add(File f, JSONObject o) {
            files.put(f.getAbsolutePath(), o);
        }

        JSONArray toSortedArray() throws JSONException {
            ArrayList<JSONObject> sorted = new ArrayList<>(files.values());
            sorted.sort(Comparator
                    .comparingDouble((JSONObject o) -> o.optDouble("confidence", 0.0)).reversed()
                    .thenComparingLong((JSONObject o) -> o.optLong("lastModified", 0L)).reversed());
            JSONArray a = new JSONArray();
            for (JSONObject o : sorted) {
                a.put(o);
            }
            return a;
        }
    }

    private static final class DirectoryListResult {
        final JSONArray files = new JSONArray();
        int visited;
        boolean partial;
        String status = "ok";
    }

    private static final class Snapshot {
        String id;
        long createdAt;
        final Map<String, JSONObject> files = new LinkedHashMap<>();
    }
}
