package dummydomain.yetanothercallblocker.data.sources;

import java.io.IOException;

/**
 * Downloads a number list over HTTP(S), using the validators of the previous
 * download for a conditional request.
 */
public interface RemoteListDownloader {

    /** Result of a download. Immutable. */
    final class Response {

        private final boolean notModified;
        private final byte[] body;
        private final String etag;
        private final String lastModified;

        private Response(boolean notModified, byte[] body, String etag, String lastModified) {
            this.notModified = notModified;
            this.body = body;
            this.etag = etag;
            this.lastModified = lastModified;
        }

        public static Response notModified(String etag, String lastModified) {
            return new Response(true, null, etag, lastModified);
        }

        public static Response ok(byte[] body, String etag, String lastModified) {
            return new Response(false, body, etag, lastModified);
        }

        /** True for HTTP 304: the list has not changed since the last download. */
        public boolean isNotModified() {
            return notModified;
        }

        /** The downloaded content, null if not modified. */
        public byte[] getBody() {
            return body;
        }

        /** {@code ETag} response header, or null. */
        public String getEtag() {
            return etag;
        }

        /** {@code Last-Modified} response header, or null. */
        public String getLastModified() {
            return lastModified;
        }
    }

    /** The server answered with an unexpected HTTP status. */
    class HttpStatusException extends IOException {

        private static final long serialVersionUID = 1L;

        private final int code;

        public HttpStatusException(int code, String message) {
            super("HTTP " + code + (message != null && !message.isEmpty() ? " " + message : ""));
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    /** The content exceeds the size limit. */
    class TooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        private final long limit;

        public TooLargeException(long limit) {
            super("The file is larger than " + (limit / (1024 * 1024)) + " MB");
            this.limit = limit;
        }

        public long getLimit() {
            return limit;
        }
    }

    /**
     * Downloads the URL.
     *
     * @param url          http or https URL
     * @param etag         {@code ETag} of the previous download for {@code If-None-Match}, or null
     * @param lastModified {@code Last-Modified} of the previous download for
     *                     {@code If-Modified-Since}, or null
     * @throws HttpStatusException for non-2xx responses (except 304)
     * @throws TooLargeException   if the content is larger than the limit
     * @throws IOException         for network errors
     */
    Response download(String url, String etag, String lastModified) throws IOException;

}
