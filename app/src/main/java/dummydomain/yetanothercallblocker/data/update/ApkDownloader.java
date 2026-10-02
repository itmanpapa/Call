package dummydomain.yetanothercallblocker.data.update;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads an update APK into a directory of its own (the app's cache), reporting the
 * progress. The file is written as {@code *.part} and renamed when complete; its size
 * must match the size announced by the release (and by {@code Content-Length}). Other
 * files in the directory (older downloads) are deleted. Plain Java with OkHttp 3.12;
 * must not be used on the Android main thread.
 */
public class ApkDownloader {

    /** Receives the download progress (on the downloading thread). */
    public interface ProgressListener {
        /**
         * @param downloaded bytes downloaded so far
         * @param total      expected size in bytes, -1 if unknown
         */
        void onProgress(long downloaded, long total);
    }

    /** Thrown when the download was cancelled. */
    public static class CancelledException extends InterruptedIOException {

        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("Download cancelled");
        }
    }

    /** No real release APK is that big; protects the cache from a broken server. */
    public static final long MAX_SIZE = 200L * 1024 * 1024;

    static final String PART_SUFFIX = ".part";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final long TIMEOUT_SECONDS = 60;

    private static final Logger LOG = LoggerFactory.getLogger(ApkDownloader.class);

    private final Supplier<OkHttpClient> clientSupplier;
    private final File directory;
    private final String userAgent;

    /**
     * @param clientSupplier supplies a base client (timeouts are adjusted here)
     * @param directory      directory for the downloads, used exclusively by this class
     * @param userAgent      User-Agent header, may be null
     */
    public ApkDownloader(Supplier<OkHttpClient> clientSupplier, File directory,
                         String userAgent) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.userAgent = userAgent;
    }

    public File getDirectory() {
        return directory;
    }

    /**
     * @return the already downloaded, complete file of the release, or null
     */
    public File findDownloaded(ReleaseInfo release) {
        if (!release.hasApk()) return null;
        File file = new File(directory, fileName(release));
        if (!file.isFile()) return null;
        if (release.getApkSize() > 0 && file.length() != release.getApkSize()) return null;
        return file;
    }

    /**
     * Downloads the APK of the release (or returns the existing complete download).
     *
     * @param cancelled polled while downloading; true aborts with {@link CancelledException}
     * @return the downloaded file
     */
    public File download(ReleaseInfo release, ProgressListener listener,
                         BooleanSupplier cancelled) throws IOException {
        if (!release.hasApk()) throw new IOException("The release has no APK");

        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Can't create " + directory);
        }

        String name = fileName(release);
        File target = new File(directory, name);
        deleteOtherFiles(name);

        File existing = findDownloaded(release);
        if (existing != null) {
            LOG.debug("download() already downloaded: {}", existing);
            if (listener != null) listener.onProgress(existing.length(), existing.length());
            return existing;
        }

        File part = new File(directory, name + PART_SUFFIX);
        delete(part);
        delete(target);

        long expected = release.getApkSize();
        if (expected > MAX_SIZE) throw new IOException("The APK is too big: " + expected);

        OkHttpClient client = clientSupplier.get().newBuilder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();

        Request.Builder requestBuilder = new Request.Builder()
                .url(release.getApkUrl())
                .header("Accept", "application/octet-stream");
        if (userAgent != null) requestBuilder.header("User-Agent", userAgent);

        LOG.info("download() {} -> {}", release.getApkUrl(), target);

        boolean ok = false;
        try (Response response = client.newCall(requestBuilder.build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            ResponseBody body = response.body();
            if (body == null) throw new IOException("Empty response");

            long contentLength = body.contentLength();
            if (expected > 0 && contentLength >= 0 && contentLength != expected) {
                throw new IOException("Unexpected size: " + contentLength
                        + " instead of " + expected);
            }
            long total = expected > 0 ? expected : contentLength;
            if (total > MAX_SIZE) throw new IOException("The APK is too big: " + total);

            long downloaded = 0;
            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                if (listener != null) listener.onProgress(0, total);
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new CancelledException();
                    }
                    out.write(buffer, 0, n);
                    downloaded += n;
                    if (downloaded > MAX_SIZE || (total > 0 && downloaded > total)) {
                        throw new IOException("The download is bigger than expected");
                    }
                    if (listener != null) listener.onProgress(downloaded, total);
                }
            }

            if (total > 0 && downloaded != total) {
                throw new IOException("Incomplete download: " + downloaded + " of " + total);
            }
            if (downloaded == 0) throw new IOException("Empty download");

            if (!part.renameTo(target)) throw new IOException("Can't rename " + part);
            ok = true;
            LOG.info("download() finished, {} bytes", downloaded);
            return target;
        } finally {
            if (!ok) delete(part);
        }
    }

    /** Deletes all downloads. */
    public void clear() {
        deleteOtherFiles(null);
    }

    /** @return a safe local file name for the APK of the release */
    static String fileName(ReleaseInfo release) {
        String name = release.getApkName();
        if (name == null || name.isEmpty()) name = "callguard-" + release.getTag() + ".apk";
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.startsWith(".")) name = "_" + name;
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) name += ".apk";
        return name;
    }

    private void deleteOtherFiles(String keep) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (keep != null && file.getName().equals(keep)) continue;
            if (file.isFile()) delete(file);
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) LOG.warn("delete() failed: {}", file);
    }

}
