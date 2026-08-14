/*
 * SPDX-License-Identifier: GPL-3.0-only
 */

package com.best.deskclock.sync;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Low-level framing for the sync protocol: each message is a 4-byte big-endian length prefix
 * followed by UTF-8 JSON, mirroring the Windows implementation.
 */
public final class SyncWire {

    static final int MAX_MESSAGE_SIZE = 10 * 1024 * 1024;

    private SyncWire() {
    }

    static void writeSnapshot(OutputStream out, SyncModels.SyncSnapshot snapshot) throws IOException {
        try {
            writeJson(out, snapshot.toJson());
        } catch (JSONException e) {
            throw new IOException("failed to serialize snapshot", e);
        }
    }

    static void writeDone(OutputStream out) throws IOException {
        final JSONObject o = new JSONObject();
        try {
            o.put("type", "done");
        } catch (JSONException ignored) {
        }
        writeJson(out, o);
    }

    static SyncModels.SyncSnapshot readSnapshot(InputStream in) throws IOException {
        final JSONObject o = readJson(in);
        return o == null ? null : SyncModels.SyncSnapshot.fromJson(o);
    }

    static boolean readDone(InputStream in) throws IOException {
        final JSONObject o = readJson(in);
        return o != null && "done".equals(o.optString("type"));
    }

    private static void writeJson(OutputStream out, JSONObject json) throws IOException {
        final byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        final int length = bytes.length;
        out.write((length >>> 24) & 0xFF);
        out.write((length >>> 16) & 0xFF);
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(bytes);
        out.flush();
    }

    private static JSONObject readJson(InputStream in) throws IOException {
        final byte[] lengthBytes = new byte[4];
        if (!readExact(in, lengthBytes, 4)) {
            return null;
        }
        final int length = ((lengthBytes[0] & 0xFF) << 24)
            | ((lengthBytes[1] & 0xFF) << 16)
            | ((lengthBytes[2] & 0xFF) << 8)
            | (lengthBytes[3] & 0xFF);
        if (length < 0 || length > MAX_MESSAGE_SIZE) {
            return null;
        }
        final byte[] buffer = new byte[length];
        if (!readExact(in, buffer, length)) {
            return null;
        }
        try {
            return new JSONObject(new String(buffer, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return null;
        }
    }

    private static boolean readExact(InputStream in, byte[] buffer, int count) throws IOException {
        int offset = 0;
        while (offset < count) {
            final int read = in.read(buffer, offset, count - offset);
            if (read <= 0) {
                return false;
            }
            offset += read;
        }
        return true;
    }
}
