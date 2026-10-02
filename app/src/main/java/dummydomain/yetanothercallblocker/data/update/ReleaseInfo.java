package dummydomain.yetanothercallblocker.data.update;

import java.util.Objects;

/**
 * A published release of the app on GitHub with its APK asset. Immutable.
 */
public final class ReleaseInfo {

    private final String tag;
    private final String version;
    private final String name;
    private final String notes;
    private final String pageUrl;
    private final String apkName;
    private final String apkUrl;
    private final long apkSize;
    private final long publishedAt;

    /**
     * @param tag         the tag, e.g. {@code v0.12.0}
     * @param version     the version derived from the tag, e.g. {@code 0.12.0}
     * @param name        the release title, may be null
     * @param notes       the release notes (Markdown as written on GitHub), never null
     * @param pageUrl     the release page in the browser, may be null
     * @param apkName     file name of the APK asset, null if the release has no APK
     * @param apkUrl      download URL of the APK asset, null if the release has no APK
     * @param apkSize     size of the APK in bytes, -1 if unknown
     * @param publishedAt publication time, millis since the epoch, 0 if unknown
     */
    public ReleaseInfo(String tag, String version, String name, String notes, String pageUrl,
                       String apkName, String apkUrl, long apkSize, long publishedAt) {
        this.tag = Objects.requireNonNull(tag, "tag");
        this.version = Objects.requireNonNull(version, "version");
        this.name = name;
        this.notes = notes != null ? notes : "";
        this.pageUrl = pageUrl;
        this.apkName = apkName;
        this.apkUrl = apkUrl;
        this.apkSize = apkSize;
        this.publishedAt = publishedAt;
    }

    public String getTag() {
        return tag;
    }

    /** The version without the "v" prefix, e.g. {@code 0.12.0}. */
    public String getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    /** The release notes as written (GitHub Markdown); empty if there are none. */
    public String getNotes() {
        return notes;
    }

    /** The release notes with the most common Markdown markup removed. */
    public String getPlainNotes() {
        return MarkdownText.toPlainText(notes);
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public String getApkName() {
        return apkName;
    }

    public String getApkUrl() {
        return apkUrl;
    }

    public boolean hasApk() {
        return apkUrl != null;
    }

    /** Size of the APK in bytes, -1 if unknown. */
    public long getApkSize() {
        return apkSize;
    }

    /** Publication time in millis since the epoch, 0 if unknown. */
    public long getPublishedAt() {
        return publishedAt;
    }

    /** @return true if this release is newer than the given (installed) version */
    public boolean isNewerThan(String currentVersion) {
        return AppVersion.isNewer(version, currentVersion);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReleaseInfo)) return false;
        ReleaseInfo that = (ReleaseInfo) o;
        return apkSize == that.apkSize && publishedAt == that.publishedAt
                && tag.equals(that.tag) && version.equals(that.version)
                && Objects.equals(name, that.name) && notes.equals(that.notes)
                && Objects.equals(pageUrl, that.pageUrl)
                && Objects.equals(apkName, that.apkName)
                && Objects.equals(apkUrl, that.apkUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tag, version, apkUrl, apkSize, publishedAt);
    }

    @Override
    public String toString() {
        return "ReleaseInfo{tag='" + tag + "', version='" + version + "', apk='" + apkName
                + "', apkSize=" + apkSize + ", publishedAt=" + publishedAt + '}';
    }

}
