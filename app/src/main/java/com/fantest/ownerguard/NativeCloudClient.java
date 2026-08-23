package com.fantest.ownerguard;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Strict authenticated JSON client for the native Cloud workspace. */
final class NativeCloudClient {
    interface Callback {
        void onSuccess(JSONObject response);
        void onError(String message);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;

    private NativeCloudClient() {}

    static void load(Context context, String section, Map<String,String> params, Callback callback) {
        LinkedHashMap<String,String> query = new LinkedHashMap<>();
        query.put("section", section == null ? "dashboard" : section);
        if (params != null) query.putAll(params);
        request(context, "GET", query, callback);
    }

    static void toggleUser(Context context, int userId, Callback callback) {
        LinkedHashMap<String,String> body = new LinkedHashMap<>();
        body.put("action", "toggle_user");
        body.put("section", "users");
        body.put("id", Integer.toString(userId));
        request(context, "POST", body, callback);
    }

    private static void request(Context context, String method,
                                LinkedHashMap<String,String> values, Callback callback) {
        Context app = context.getApplicationContext();
        String token = CloudAccountManager.token(app);
        String base = CloudBackupManager.baseUrl(app);
        if (token.isEmpty()) {
            deliverError(callback, "Cloud account is not signed in");
            return;
        }
        EXECUTOR.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String encoded = form(values);
                String endpoint = base + "/api/native_workspace.php";
                if ("GET".equals(method) && !encoded.isEmpty()) endpoint += "?" + encoded;
                URL url = new URL(endpoint);
                if (!"https".equalsIgnoreCase(url.getProtocol())) {
                    throw new IllegalStateException("OwnerGuard Cloud must use HTTPS");
                }
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setRequestMethod(method);
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setRequestProperty("User-Agent", "OwnerGuard-Android/1.0.36 NativeCloud");
                connection.setRequestProperty("X-OwnerGuard-Client", "android-native");
                if ("POST".equals(method)) {
                    byte[] data = encoded.getBytes(StandardCharsets.UTF_8);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type",
                            "application/x-www-form-urlencoded; charset=UTF-8");
                    connection.setFixedLengthStreamingMode(data.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(data);
                    }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    throw new IllegalStateException(
                            "Cloud API redirected to a web page. The native API path must bypass Cloudflare Access.");
                }
                String raw = read(status >= 400
                        ? connection.getErrorStream() : connection.getInputStream()).trim();
                if (raw.startsWith("<") || (connection.getContentType() != null
                        && connection.getContentType().toLowerCase().contains("text/html"))) {
                    throw new IllegalStateException(
                            "Cloud returned HTML instead of native JSON. Deploy the native workspace API.");
                }
                if (raw.isEmpty()) throw new IllegalStateException("Cloud returned an empty response");
                JSONObject json = new JSONObject(raw);
                if (status < 200 || status >= 300 || !json.optBoolean("ok", false)) {
                    String message = json.optString("error", "Cloud request failed (HTTP " + status + ")");
                    if (status == 401) CloudAccountManager.logout(app);
                    throw new IllegalStateException(message);
                }
                MAIN.post(() -> {
                    if (callback != null) callback.onSuccess(json);
                });
            } catch (Throwable error) {
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = error.getClass().getSimpleName();
                }
                deliverError(callback, message.trim());
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static String form(LinkedHashMap<String,String> values) throws Exception {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String,String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
            out.append('=');
            out.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        return out.toString();
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE) throw new IllegalStateException("Cloud response is too large");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }

    private static void deliverError(Callback callback, String message) {
        MAIN.post(() -> {
            if (callback != null) callback.onError(message);
        });
    }
}
