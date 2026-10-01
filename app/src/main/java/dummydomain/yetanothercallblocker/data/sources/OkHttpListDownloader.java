package dummydomain.yetanothercallblocker.data.sources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

/**
 * {@link RemoteListDownloader} based on OkHttp 3.12: conditional requests with
 * {@code If-None-Match} / {@code If-Modified-Since}, a size limit (checked against
 * {@code Content-Length} and while reading) and timeouts. Plain Java, no Android
 * dependencies; must not be used on the Android main thread.
 */
public class OkHttpListDownloader implements RemoteListDownloader {

    /** Supplies the HTTP client (lets the app initialize the TLS provider lazily). */
    public interface ClientFactory {
        OkHttpClient create();
    }

    /** Default size limit: the known community lists are well below 1 MB. */
    public static final long DEFAULT_MAX_SIZE = 20L * 1024 * 1024;

    static final long TIMEOUT_SECONDS = 30;
    static final String USER_AGENT = "YetAnotherCallBlocker (number list update)";

    private static final Logger LOG = LoggerFactory.getLogger(OkHttpListDownloader.class);

    private final ClientFactory clientFactory;
    private final long maxSize;

    private volatile OkHttpClient client;

    public OkHttpListDownloader(ClientFactory clientFactory) {
        this(clientFactory, DEFAULT_MAX_SIZE);
    }

    public OkHttpListDownloader(ClientFactory clientFactory, long maxSize) {
        this.clientFactory = clientFactory;
        this.maxSize = maxSize;
    }

    private OkHttpClient getClient() {
        OkHttpClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    client = c = clientFactory.create().newBuilder()
                            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return c;
    }

    @Override
    public RemoteListDownloader.Response download(String url, String etag,
                                                  String lastModified) throws IOException {
        HttpUrl httpUrl = HttpUrl.parse(url);
        if (httpUrl == null) throw new IOException("Invalid URL: " + url);

        Request.Builder request = new Request.Builder()
                .url(httpUrl)
                .header("User-Agent", USER_AGENT);
        if (etag != null) request.header("If-None-Match", etag);
        if (lastModified != null) request.header("If-Modified-Since", lastModified);

        LOG.debug("download() url={}, etag={}, lastModified={}", url, etag, lastModified);

        // okhttp3.Response, not the inherited RemoteListDownloader.Response
        try (okhttp3.Response response = getClient().newCall(request.build()).execute()) {
            String newEtag = response.header("ETag");
            String newLastModified = response.header("Last-Modified");

            if (response.code() == 304) {
                LOG.debug("download() not modified");
                return RemoteListDownloader.Response.notModified(
                        newEtag != null ? newEtag : etag,
                        newLastModified != null ? newLastModified : lastModified);
            }
            if (!response.isSuccessful()) {
                throw new HttpStatusException(response.code(), response.message());
            }

            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response");

            long contentLength = body.contentLength();
            if (contentLength > maxSize) throw new TooLargeException(maxSize);

            byte[] content = readLimited(body.byteStream(), maxSize);
            LOG.debug("download() got {} bytes", content.length);
            return RemoteListDownloader.Response.ok(content, newEtag, newLastModified);
        }
    }

    static byte[] readLimited(InputStream in, long maxSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + (long) n > maxSize) throw new TooLargeException(maxSize);
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

}
