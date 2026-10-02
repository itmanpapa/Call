package dummydomain.yetanothercallblocker.data.sources;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * {@link PhoneBlockClient.HttpTransport} based on OkHttp (3.12, as used by the app).
 * The response body is streamed, not buffered.
 */
public class OkHttpTransport implements PhoneBlockClient.HttpTransport {

    private final Supplier<OkHttpClient> clientSupplier;

    /**
     * @param clientSupplier supplies the client; called for every request, so it can
     *                       create the client lazily (network initialization on Android)
     */
    public OkHttpTransport(Supplier<OkHttpClient> clientSupplier) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
    }

    @Override
    public PhoneBlockClient.HttpResponse get(String url, Map<String, String> headers)
            throws IOException {
        Request.Builder builder = new Request.Builder().url(url).get();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }

        return execute(builder);
    }

    @Override
    public PhoneBlockClient.HttpResponse send(String method, String url,
                                              Map<String, String> headers,
                                              String contentType, String body)
            throws IOException {
        RequestBody requestBody = null;
        if (body != null) {
            requestBody = RequestBody.create(MediaType.parse(
                    contentType != null ? contentType : "application/octet-stream"), body);
        } else if ("POST".equals(method) || "PUT".equals(method)) {
            // OkHttp requires a body for these methods
            requestBody = RequestBody.create(null, new byte[0]);
        }

        Request.Builder builder = new Request.Builder().url(url).method(method, requestBody);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.header(header.getKey(), header.getValue());
        }
        return execute(builder);
    }

    private PhoneBlockClient.HttpResponse execute(Request.Builder builder) throws IOException {
        Response response = clientSupplier.get().newCall(builder.build()).execute();
        boolean ok = false;
        try {
            ResponseBody body = response.body();
            Reader reader = body != null ? body.charStream() : null;
            PhoneBlockClient.HttpResponse result
                    = new PhoneBlockClient.HttpResponse(response.code(), reader, response);
            ok = true;
            return result;
        } finally {
            if (!ok) response.close();
        }
    }

}
