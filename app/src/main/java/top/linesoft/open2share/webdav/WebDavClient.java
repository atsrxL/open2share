package top.linesoft.open2share.webdav;

import android.annotation.SuppressLint;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

/**
 * A minimal WebDAV client (PUT / MKCOL / PROPFIND / HEAD) built on OkHttp, modelled after the way
 * HTTP-Shortcuts performs its file uploads: the file is streamed straight from its content URI, the
 * credentials are sent as HTTP basic authentication and the upload progress is reported through a
 * wrapping request body.
 */
public class WebDavClient {

    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?><D:propfind xmlns:D=\"DAV:\"><D:prop>"
                    + "<D:resourcetype/></D:prop></D:propfind>";

    public interface ProgressListener {
        void onProgress(long bytesWritten, long totalBytes);
    }

    public interface StreamOpener {
        InputStream open() throws IOException;
    }

    /** Thrown when the server answered, but with an unexpected status code. */
    public static class HttpStatusException extends IOException {
        public final int code;

        HttpStatusException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final WebDavConfig config;
    private final OkHttpClient client;

    public WebDavClient(WebDavConfig config) {
        this.config = config;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true);
        if (config.trustAllCerts) {
            applyUnsafeTls(builder);
        }
        this.client = builder.build();
    }

    private Request.Builder newRequest(HttpUrl url) {
        Request.Builder builder = new Request.Builder().url(url);
        if (!TextUtils.isEmpty(config.username) || !TextUtils.isEmpty(config.password)) {
            builder.header("Authorization",
                    Credentials.basic(config.username, config.password, StandardCharsets.UTF_8));
        }
        return builder;
    }

    /** PROPFIND on a collection; returns the status code (207 on success). */
    public int propfind(HttpUrl url, int depth) throws IOException {
        Request request = newRequest(url)
                .header("Depth", String.valueOf(depth))
                .method("PROPFIND", RequestBody.create(PROPFIND_BODY, XML))
                .build();
        try (Response response = client.newCall(request).execute()) {
            return response.code();
        }
    }

    /** Checks whether a resource exists; a server error is reported instead of being swallowed. */
    public boolean exists(HttpUrl url) throws IOException {
        Request request = newRequest(url).head().build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == 404 || response.code() == 410) {
                return false;
            }
            if (response.code() == 405 || response.code() == 501) {
                // Server does not support HEAD, fall back to PROPFIND.
                int code = propfind(url, 0);
                return code != 404 && code != 410;
            }
            checkSuccess(response, url);
            return true;
        }
    }

    /** Creates {@code folder} below the base path, including all missing parent collections. */
    public void createFolders(String folder) throws IOException {
        List<String> segments = WebDavConfig.splitPath(folder);
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(segment);
            HttpUrl url = config.folderUrl(path.toString());
            if (url == null) {
                return;
            }
            mkcol(url);
        }
    }

    private void mkcol(HttpUrl url) throws IOException {
        Request request = newRequest(url).method("MKCOL", null).build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            // 405 (already exists) and 301 (already exists, redirect to the canonical URL) are fine.
            if (code == 405 || code == 301 || code == 409 || response.isSuccessful()) {
                return;
            }
            throw new HttpStatusException(code, "MKCOL " + url.encodedPath() + " -> " + code + " " + response.message());
        }
    }

    /** Uploads a stream to {@code url} via PUT. */
    public void put(HttpUrl url, final StreamOpener source, @Nullable String contentType,
                    final long contentLength, @Nullable final ProgressListener progressListener)
            throws IOException {
        RequestBody body = new RequestBody() {
            @Override
            public MediaType contentType() {
                return contentType != null ? MediaType.parse(contentType) : null;
            }

            @Override
            public long contentLength() {
                return contentLength > 0 ? contentLength : -1;
            }

            @Override
            public void writeTo(@NonNull BufferedSink sink) throws IOException {
                try (InputStream stream = source.open()) {
                    if (stream == null) {
                        throw new IOException("Unable to open the file");
                    }
                    byte[] buffer = new byte[64 * 1024];
                    long written = 0;
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        sink.write(buffer, 0, read);
                        written += read;
                        if (progressListener != null) {
                            progressListener.onProgress(written, contentLength);
                        }
                    }
                    sink.flush();
                }
            }
        };
        Request request = newRequest(url)
                .header("Connection", "close")
                .put(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            checkSuccess(response, url);
        }
    }

    /** Runs a PROPFIND against the base URL (or a folder) and returns a readable result. */
    public String testConnection(@Nullable String folder) throws IOException {
        HttpUrl url = TextUtils.isEmpty(folder) ? config.folderUrl("") : config.folderUrl(folder);
        if (url == null) {
            throw new IOException("Invalid server address");
        }
        Request request = newRequest(url)
                .header("Depth", "0")
                .method("PROPFIND", RequestBody.create(PROPFIND_BODY, XML))
                .build();
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            if (code == 207 || response.isSuccessful()) {
                return url + " -> " + code;
            }
            if (code == 405 || code == 501) {
                // Not a WebDAV server, but a plain HTTP server may still accept PUT.
                return url + " -> " + code;
            }
            throw new HttpStatusException(code, code + " " + response.message());
        }
    }

    private static void checkSuccess(Response response, HttpUrl url) throws IOException {
        if (!response.isSuccessful()) {
            throw new HttpStatusException(response.code(),
                    response.code() + " " + response.message());
        }
    }

    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier"})
    private static void applyUnsafeTls(OkHttpClient.Builder builder) {
        try {
            X509TrustManager trustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustManager}, new SecureRandom());
            SSLSocketFactory socketFactory = sslContext.getSocketFactory();
            builder.sslSocketFactory(socketFactory, trustManager);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            // Keep the default (safe) TLS configuration.
        }
    }

    public static String describeError(Throwable error) {
        if (error instanceof HttpStatusException) {
            return error.getMessage();
        }
        if (error instanceof SocketTimeoutException) {
            return "Timeout";
        }
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }
}
