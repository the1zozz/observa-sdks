package com.integralways.observa.sdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** {@link Transport} over {@link HttpURLConnection}, which every Java from 8 on has: no library needed. */
public final class HttpTransport implements Transport {

    private final int timeoutMillis;
    private final String userAgent;

    public HttpTransport(int timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
        this.userAgent = "observa-java/" + Observa.VERSION + " (Java " + System.getProperty("java.version") + ")";
    }

    @Override
    public Response send(String url, String secretKey, byte[] json) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + secretKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setFixedLengthStreamingMode(json.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(json);
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, in == null ? "" : read(in), retryAfter(connection.getHeaderField("Retry-After")));
        } finally {
            connection.disconnect();
        }
    }

    /** Seconds from a {@code Retry-After} header; 0 when absent or given as a date (then the backoff decides). */
    static long retryAfter(String header) {
        if (header == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            // Observa's answers are small; anything bigger than 64 KB is not one of them.
            while ((n = stream.read(buffer)) > 0 && out.size() < 65_536) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
