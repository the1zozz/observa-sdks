package com.integralways.observa.sdk;

import java.io.IOException;

/**
 * How a batch reaches Observa: one HTTP POST of a JSON body with the secret key. {@link HttpTransport} by default;
 * replaceable for tests or to send through a proxy of the application's own.
 */
public interface Transport {

    /**
     * Observa's answer.
     *
     * @param retryAfterSeconds the {@code Retry-After} header, or 0 when there is none
     */
    final class Response {
        final int status;
        final String body;
        final long retryAfterSeconds;

        public Response(int status, String body, long retryAfterSeconds) {
            this.status = status;
            this.body = body;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** Sends the body to {@code url}; throws {@link IOException} when there is no answer (network, timeout). */
    Response send(String url, String secretKey, byte[] json) throws IOException;
}
