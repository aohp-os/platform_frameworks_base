/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.android.server.aohp;

import android.Manifest;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Log;

import com.android.internal.aohp.IAohpContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Binder service that exposes Linux container management to privileged apps.
 * Communicates with the native {@code aohp-containerd} daemon via a Unix
 * domain socket created by init.
 */
public final class AohpContainerService extends IAohpContainer.Stub {
    private static final String TAG = "AohpContainerService";
    public static final String SERVICE_NAME = "aohp_container";
    private static final String DAEMON_SOCKET = "aohp_container";

    private final Context mContext;

    public AohpContainerService(Context context) {
        mContext = context;
    }

    private void enforcePermission() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_AOHP_VIRTUAL_DISPLAY, null);
    }

    /**
     * Send a single-line command to the daemon and read the single-line
     * response.  The socket is closed after each request (short-lived).
     */
    private String sendCommand(String command) throws IOException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(DAEMON_SOCKET,
                    LocalSocketAddress.Namespace.RESERVED));

            OutputStream out = socket.getOutputStream();
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String response = reader.readLine();
            return response != null ? response : "ERR no response";
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean isOk(String response) {
        return response != null && response.startsWith("OK");
    }

    private static String okPayload(String response) {
        if (response == null || response.length() <= 3) return "";
        return response.substring(3).trim();
    }

    @Override
    public String[] listContainers() {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("LIST");
            if (!isOk(resp)) {
                return new String[0];
            }
            String payload = okPayload(resp);
            if (payload.isEmpty()) {
                return new String[0];
            }
            return payload.split(",");
        } catch (IOException e) {
            Log.e(TAG, "listContainers failed", e);
            return new String[0];
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String createContainer(String name, String template) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("CREATE " + name + " " + template);
            if (isOk(resp)) {
                return "";
            }
            Log.e(TAG, "createContainer daemon response: " + resp);
            if (resp != null && resp.startsWith("ERR ")) {
                return resp.substring(4).trim();
            }
            return resp != null ? resp : "no response";
        } catch (IOException e) {
            Log.e(TAG, "createContainer failed", e);
            return e.getMessage() != null ? e.getMessage() : "IOException";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean destroyContainer(String name) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("DESTROY " + name);
            return isOk(resp);
        } catch (IOException e) {
            Log.e(TAG, "destroyContainer failed", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean resetContainer(String name) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("RESET " + name);
            return isOk(resp);
        } catch (IOException e) {
            Log.e(TAG, "resetContainer failed", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String execSync(String containerName, String command, int timeoutMs) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("EXEC " + containerName + " " + timeoutMs + " " + command);
            if (isOk(resp) && resp.length() > 3) {
                return okPayload(resp);
            }
            return "{\"exitCode\":-1,\"stdout\":\"\",\"stderr\":\"" +
                    (resp != null ? resp.replace("\"", "\\\"") : "daemon error") + "\"}";
        } catch (IOException e) {
            Log.e(TAG, "execSync failed", e);
            return "{\"exitCode\":-1,\"stdout\":\"\",\"stderr\":\"" +
                    e.getMessage().replace("\"", "\\\"") + "\"}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public ParcelFileDescriptor openShell(String containerName) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            LocalSocket socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(DAEMON_SOCKET,
                    LocalSocketAddress.Namespace.RESERVED));

            OutputStream out = socket.getOutputStream();
            out.write(("SHELL " + containerName + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String resp = reader.readLine();

            if (!isOk(resp)) {
                socket.close();
                return null;
            }

            return ParcelFileDescriptor.fromFd(socket.getFileDescriptor().getInt$());
        } catch (Exception e) {
            Log.e(TAG, "openShell failed", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String templateInfo(String containerName) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("TEMPLATE_INFO " + containerName);
            if (isOk(resp)) {
                return okPayload(resp);
            }
            return "";
        } catch (IOException e) {
            Log.e(TAG, "templateInfo failed", e);
            return "";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public long startService(String containerName, String serviceId, String command) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("START_SVC " + containerName + " " + serviceId + " " + command);
            if (isOk(resp)) {
                String p = okPayload(resp);
                try {
                    return Long.parseLong(p.trim());
                } catch (NumberFormatException e) {
                    return -1L;
                }
            }
            return -1L;
        } catch (IOException e) {
            Log.e(TAG, "startService failed", e);
            return -1L;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean stopService(String containerName, String serviceId) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("STOP_SVC " + containerName + " " + serviceId);
            return isOk(resp);
        } catch (IOException e) {
            Log.e(TAG, "stopService failed", e);
            return false;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String listServices(String containerName) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("LIST_SVC " + containerName);
            if (isOk(resp)) {
                return okPayload(resp);
            }
            return "[]";
        } catch (IOException e) {
            Log.e(TAG, "listServices failed", e);
            return "[]";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String serviceLog(String containerName, String serviceId, int tailBytes) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand(
                    "SVC_LOG " + containerName + " " + serviceId + " " + tailBytes);
            if (isOk(resp)) {
                String b64 = okPayload(resp);
                if (b64.isEmpty()) return "";
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                return new String(raw, StandardCharsets.UTF_8);
            }
            return "";
        } catch (IOException e) {
            Log.e(TAG, "serviceLog failed", e);
            return "";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String getUsage(String containerName) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("USAGE " + containerName);
            if (isOk(resp)) {
                return okPayload(resp);
            }
            return "{}";
        } catch (IOException e) {
            Log.e(TAG, "getUsage failed", e);
            return "{}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public String diagnose(String containerName) {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("DIAG " + containerName);
            if (isOk(resp)) {
                return okPayload(resp);
            }
            return "{}";
        } catch (IOException e) {
            Log.e(TAG, "diagnose failed", e);
            return "{}";
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
