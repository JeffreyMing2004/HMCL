/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.util.io;

import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.util.Pair;
import org.jackhuang.hmcl.util.function.ExceptionalSupplier;
import org.jackhuang.hmcl.util.gson.JsonUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Lang.wrap;
import static org.jackhuang.hmcl.util.gson.JsonUtils.GSON;
import static org.jackhuang.hmcl.util.io.NetworkUtils.createHttpConnection;
import static org.jackhuang.hmcl.util.io.NetworkUtils.resolveConnection;

public abstract class HttpRequest {
    protected final String url;
    protected final String method;
    protected final Map<String, String> headers = new HashMap<>();
    protected final Set<Integer> toleratedHttpCodes = new HashSet<>();
    protected int retryTimes = 1;
    protected boolean ignoreHttpCode;

    private HttpRequest(String url, String method) {
        this.url = url;
        this.method = method;
    }

    public String getUrl() {
        return url;
    }

    public HttpRequest accept(String contentType) {
        return header("Accept", contentType);
    }

    public HttpRequest authorization(String token) {
        return header("Authorization", token);
    }

    public HttpRequest authorization(String tokenType, String tokenString) {
        return authorization(tokenType + " " + tokenString);
    }

    public HttpRequest header(String key, String value) {
        headers.put(key, value);
        return this;
    }

    public HttpRequest ignoreHttpCode() {
        ignoreHttpCode = true;
        return this;
    }

    public HttpRequest retry(int retryTimes) {
        if (retryTimes < 1) {
            throw new IllegalArgumentException("retryTimes >= 1");
        }
        this.retryTimes = retryTimes;
        return this;
    }

    public abstract String getString() throws IOException;

    public CompletableFuture<String> getStringAsync() {
        return CompletableFuture.supplyAsync(wrap(this::getString), Schedulers.io());
    }

    public <T> T getJson(Class<T> typeOfT) throws IOException, JsonParseException {
        return JsonUtils.fromNonNullJson(getString(), typeOfT);
    }

    public <T> T getJson(TypeToken<T> type) throws IOException, JsonParseException {
        return JsonUtils.fromNonNullJson(getString(), type);
    }

    public <T> CompletableFuture<T> getJsonAsync(Class<T> typeOfT) {
        return getStringAsync().thenApplyAsync(jsonString -> JsonUtils.fromNonNullJson(jsonString, typeOfT));
    }

    public <T> CompletableFuture<T> getJsonAsync(TypeToken<T> type) {
        return getStringAsync().thenApplyAsync(jsonString -> JsonUtils.fromNonNullJson(jsonString, type));
    }

    public HttpRequest ignoreHttpErrorCode(int code) {
        toleratedHttpCodes.add(code);
        return this;
    }

    public HttpURLConnection createConnection() throws IOException {
        HttpURLConnection con = createHttpConnection(url);
        con.setRequestMethod(method);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            con.setRequestProperty(entry.getKey(), entry.getValue());
        }
        return con;
    }

    protected void checkResponseCode(HttpURLConnection con) throws IOException {
        int code = con.getResponseCode();
        if (code / 100 != 2) {
            if (!ignoreHttpCode && !toleratedHttpCodes.contains(code)) {
                try {
                    throw new ResponseCodeException(this.url, code, NetworkUtils.readFullyAsString(con));
                } catch (IOException e) {
                    throw new ResponseCodeException(this.url, code, e);
                }
            }
        }
    }

    public static abstract class HttpSimpleRequest extends HttpRequest {
        protected HttpSimpleRequest(String url, String method) {
            super(url, method);
        }

        @Override
        public String getString() throws IOException {
            return getStringWithRetry(() -> {
                HttpURLConnection con = createConnection();
                con = resolveConnection(con);
                checkResponseCode(con);

                return IOUtils.readFullyAsString("gzip".equals(con.getContentEncoding())
                        ? IOUtils.wrapFromGZip(con.getInputStream())
                        : con.getInputStream());
            }, retryTimes);
        }
    }

    public static class HttpGetRequest extends HttpSimpleRequest {
        protected HttpGetRequest(String url) {
            super(url, "GET");
        }
    }

    public static class HttpDeleteRequest extends HttpSimpleRequest {
        protected HttpDeleteRequest(String url) {
            super(url, "DELETE");
        }
    }

    @SuppressWarnings("unchecked")
    public static abstract class HttpEntityRequest<T extends HttpEntityRequest<T>> extends HttpRequest {
        protected byte[] bytes;

        protected HttpEntityRequest(String url, String method) {
            super(url, method);
        }

        public T contentType(String contentType) {
            headers.put("Content-Type", contentType);
            return (T) this;
        }

        public T json(Object payload) throws JsonParseException {
            return string(payload instanceof String ? (String) payload : GSON.toJson(payload), "application/json");
        }

        public T form(Map<String, String> params) {
            return string(NetworkUtils.withQuery("", params), "application/x-www-form-urlencoded");
        }

        @SafeVarargs
        public final T form(Pair<String, String>... params) {
            return form(mapOf(params));
        }

        public T string(String payload, String contentType) {
            bytes = payload.getBytes(UTF_8);
            header("Content-Length", String.valueOf(bytes.length));
            contentType(contentType + "; charset=utf-8");
            return (T) this;
        }

        @Override
        public String getString() throws IOException {
            return getStringWithRetry(() -> {
                HttpURLConnection con = createConnection();
                con.setDoOutput(true);

                if (bytes != null) {
                    try (OutputStream os = con.getOutputStream()) {
                        os.write(bytes);
                    }
                }

                checkResponseCode(con);
                return NetworkUtils.readFullyAsString(con);
            }, retryTimes);
        }
    }

    public static final class HttpPostRequest extends HttpEntityRequest<HttpPostRequest> {
        private HttpPostRequest(String url) {
            super(url, "POST");
        }
    }

    public static final class HttpPutRequest extends HttpEntityRequest<HttpPutRequest> {
        private HttpPutRequest(String url) {
            super(url, "PUT");
        }
    }

    public static HttpGetRequest GET(String url) {
        return new HttpGetRequest(url);
    }

    @SafeVarargs
    public static HttpGetRequest GET(String url, Pair<String, String>... query) {
        return GET(NetworkUtils.withQuery(url, mapOf(query)));
    }

    public static HttpPostRequest POST(String url) throws MalformedURLException {
        return new HttpPostRequest(url);
    }

    public static HttpPutRequest PUT(String url) throws MalformedURLException {
        return new HttpPutRequest(url);
    }

    public static HttpDeleteRequest DELETE(String url) {
        return new HttpDeleteRequest(url);
    }

    @SafeVarargs
    public static HttpDeleteRequest DELETE(String url, Pair<String, String>... query) {
        return DELETE(NetworkUtils.withQuery(url, mapOf(query)));
    }

    /// Retries the supplier with exponential backoff.
    /// <p>
    /// Rate-limited responses (HTTP 429) wait for a progressively longer cool-down before the next
    /// attempt, because retrying them immediately only keeps the server-side limit active. Other
    /// failures wait for a short fixed delay, and deterministic client errors (4xx except 429) are
    /// not retried at all, since repeating them cannot succeed and only adds more rejected requests.
    private static String getStringWithRetry(ExceptionalSupplier<String, IOException> supplier, int retryTimes) throws IOException {
        Throwable exception = null;
        for (int i = 0; i < retryTimes; i++) {
            if (exception != null) {
                try {
                    Thread.sleep(backoffMillis(i, exception));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                return supplier.get();
            } catch (Throwable e) {
                exception = e;
                if (isDeterministicFailure(e)) {
                    break;
                }
            }
        }
        if (exception != null) {
            if (exception instanceof IOException) {
                throw (IOException) exception;
            } else {
                throw new IOException(exception);
            }
        }
        throw new IOException("retry 0");
    }

    /// Returns whether the request failed with an HTTP client error (4xx except 429) whose outcome
    /// does not depend on timing, so that retrying it is pointless.
    private static boolean isDeterministicFailure(Throwable exception) {
        return exception instanceof ResponseCodeException
                && ((ResponseCodeException) exception).getResponseCode() / 100 == 4
                && ((ResponseCodeException) exception).getResponseCode() != 429;
    }

    /// Returns the cool-down in milliseconds before the next retry attempt: an exponential backoff
    /// with jitter for rate-limited responses, or a short fixed delay for any other failure.
    /// <p>
    /// {@code failedAttempts} is the number of failed attempts already made (1-based in effect:
    /// the wait after the first failure uses the smallest backoff).
    private static long backoffMillis(int failedAttempts, Throwable exception) {
        if (exception instanceof ResponseCodeException && ((ResponseCodeException) exception).getResponseCode() == 429) {
            return Math.min(15000, 2000L << Math.min(failedAttempts - 1, 3)) + ThreadLocalRandom.current().nextLong(1000);
        }
        return 1000;
    }
}
