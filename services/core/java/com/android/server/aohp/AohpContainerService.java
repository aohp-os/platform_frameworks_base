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
import android.util.Log;

import com.android.internal.aohp.IAohpContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;

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
            out.write((command + "\n").getBytes());
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
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

    @Override
    public String[] listContainers() {
        enforcePermission();
        final long ident = Binder.clearCallingIdentity();
        try {
            String resp = sendCommand("LIST");
            if (!isOk(resp)) {
                return new String[0];
            }
            String payload = resp.length() > 3 ? resp.substring(3).trim() : "";
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
                return resp.substring(3).trim();
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
            out.write(("SHELL " + containerName + "\n").getBytes());
            out.flush();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            String resp = reader.readLine();

            if (!isOk(resp)) {
                socket.close();
                return null;
            }

            // After "OK shell\n", the socket becomes a bidirectional PTY relay.
            // Wrap the socket's fd into a ParcelFileDescriptor for the caller.
            return ParcelFileDescriptor.fromFd(socket.getFileDescriptor().getInt$());
        } catch (Exception e) {
            Log.e(TAG, "openShell failed", e);
            return null;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
