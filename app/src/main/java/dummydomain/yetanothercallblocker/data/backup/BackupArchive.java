package dummydomain.yetanothercallblocker.data.backup;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Reads and writes a {@link BackupBundle} as a ZIP file:
 * <pre>
 * manifest.txt        "callguard-backup&lt;TAB&gt;1", then key=value lines
 * settings.txt        {@link SettingsCodec}
 * blacklist.csv       the blacklist export format
 * rules.txt           the rules file
 * user_marks.csv      the user marks file
 * lists/&lt;id&gt;.csv      imported and downloaded lists (number list files)
 * </pre>
 * <p>The manifest carries the format version: a newer major version is rejected, unknown
 * manifest keys and unknown entries are ignored (so later versions can add parts).
 * Reading checks entry names and sizes, so a crafted file can't exhaust the memory.</p>
 *
 * <p>Plain Java, no Android dependencies.</p>
 */
public final class BackupArchive {

    public static final String FORMAT_NAME = "callguard-backup";
    public static final int FORMAT_VERSION = 1;

    static final String ENTRY_MANIFEST = "manifest.txt";
    static final String ENTRY_SETTINGS = "settings.txt";
    static final String ENTRY_BLACKLIST = "blacklist.csv";
    static final String ENTRY_RULES = "rules.txt";
    static final String ENTRY_USER_MARKS = "user_marks.csv";
    static final String LISTS_PREFIX = "lists/";
    static final String LIST_SUFFIX = ".csv";

    static final String KEY_CREATED_AT = "createdAt";
    static final String KEY_APP_VERSION = "appVersion";
    static final String KEY_SECRETS = "secrets";

    /** Limits for reading (far above real backups). */
    static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    static final int MAX_ENTRIES = 1000;

    private static final Pattern LIST_ID = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");

    private BackupArchive() {
    }

    public static void write(BackupBundle bundle, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);

        StringBuilder manifest = new StringBuilder();
        manifest.append(FORMAT_NAME).append('\t').append(FORMAT_VERSION).append('\n');
        manifest.append(KEY_CREATED_AT).append('=').append(bundle.getCreatedAt()).append('\n');
        if (bundle.getAppVersion() != null) {
            manifest.append(KEY_APP_VERSION).append('=')
                    .append(SettingsCodec.escape(bundle.getAppVersion())).append('\n');
        }
        manifest.append(KEY_SECRETS).append('=').append(bundle.includesSecrets() ? 1 : 0)
                .append('\n');
        putText(zip, ENTRY_MANIFEST, manifest.toString());

        if (bundle.getSettings() != null) {
            putText(zip, ENTRY_SETTINGS, SettingsCodec.encode(bundle.getSettings()));
        }
        if (bundle.getBlacklistCsv() != null) {
            putText(zip, ENTRY_BLACKLIST, bundle.getBlacklistCsv());
        }
        if (bundle.getRulesText() != null) {
            putText(zip, ENTRY_RULES, bundle.getRulesText());
        }
        if (bundle.getUserMarksCsv() != null) {
            putText(zip, ENTRY_USER_MARKS, bundle.getUserMarksCsv());
        }
        for (Map.Entry<String, byte[]> e : bundle.getLists().entrySet()) {
            if (!LIST_ID.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("Invalid list id: " + e.getKey());
            }
            put(zip, LISTS_PREFIX + e.getKey() + LIST_SUFFIX, e.getValue());
        }

        zip.finish();
        zip.flush();
    }

    /**
     * Reads and validates a backup (the stream is read to the end, not closed).
     *
     * @throws BackupException if the data is not a (supported) backup
     * @throws IOException     if reading fails
     */
    public static BackupBundle read(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        long total = 0;

        ZipInputStream zip = new ZipInputStream(in, StandardCharsets.UTF_8);
        try {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entries.size() >= MAX_ENTRIES) {
                    throw new BackupException(BackupException.Reason.TOO_LARGE,
                            "Too many entries");
                }
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) {
                    throw new BackupException(BackupException.Reason.CORRUPT,
                            "Bad entry name: " + name);
                }
                byte[] data = readLimited(zip, MAX_ENTRY_BYTES);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new BackupException(BackupException.Reason.TOO_LARGE, "Too large");
                }
                if (entries.put(name, data) != null) {
                    throw new BackupException(BackupException.Reason.CORRUPT,
                            "Duplicate entry: " + name);
                }
            }
        } catch (ZipException | IllegalArgumentException e) {
            throw new BackupException(BackupException.Reason.CORRUPT, "Damaged ZIP file", e);
        }

        byte[] manifestData = entries.get(ENTRY_MANIFEST);
        if (manifestData == null) {
            throw new BackupException(BackupException.Reason.NOT_A_BACKUP, "No manifest");
        }

        BackupBundle bundle = new BackupBundle();
        readManifest(text(manifestData), bundle);

        byte[] settings = entries.get(ENTRY_SETTINGS);
        if (settings != null) {
            try {
                bundle.setSettings(SettingsCodec.decode(text(settings)));
            } catch (IOException e) {
                throw new BackupException(BackupException.Reason.CORRUPT,
                        "Bad settings: " + e.getMessage(), e);
            }
        }
        byte[] blacklist = entries.get(ENTRY_BLACKLIST);
        if (blacklist != null) bundle.setBlacklistCsv(text(blacklist));
        byte[] rules = entries.get(ENTRY_RULES);
        if (rules != null) bundle.setRulesText(text(rules));
        byte[] marks = entries.get(ENTRY_USER_MARKS);
        if (marks != null) bundle.setUserMarksCsv(text(marks));

        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(LISTS_PREFIX) || !name.endsWith(LIST_SUFFIX)) continue;
            String id = name.substring(LISTS_PREFIX.length(), name.length() - LIST_SUFFIX.length());
            if (!LIST_ID.matcher(id).matches()) {
                throw new BackupException(BackupException.Reason.CORRUPT, "Bad list: " + name);
            }
            bundle.putList(id, e.getValue());
        }

        return bundle;
    }

    private static void readManifest(String text, BackupBundle bundle) throws IOException {
        BufferedReader in = new BufferedReader(new StringReader(text));
        String header = in.readLine();
        if (header != null && header.startsWith("﻿")) header = header.substring(1);
        if (header == null || !header.startsWith(FORMAT_NAME + "\t")) {
            throw new BackupException(BackupException.Reason.NOT_A_BACKUP, "Not a backup");
        }
        int version;
        try {
            version = Integer.parseInt(header.substring(FORMAT_NAME.length() + 1).trim());
        } catch (NumberFormatException e) {
            throw new BackupException(BackupException.Reason.CORRUPT, "Bad version: " + header);
        }
        if (version < 1) {
            throw new BackupException(BackupException.Reason.CORRUPT, "Bad version: " + version);
        }
        if (version > FORMAT_VERSION) {
            throw new BackupException(BackupException.Reason.NEWER_VERSION,
                    "Unsupported version: " + version);
        }

        String line;
        while ((line = in.readLine()) != null) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case KEY_CREATED_AT:
                    try {
                        bundle.setCreatedAt(Long.parseLong(value.trim()));
                    } catch (NumberFormatException e) {
                        throw new BackupException(BackupException.Reason.CORRUPT,
                                "Bad creation time: " + value);
                    }
                    break;
                case KEY_APP_VERSION:
                    bundle.setAppVersion(SettingsCodec.unescape(value));
                    break;
                case KEY_SECRETS:
                    bundle.setIncludesSecrets("1".equals(value.trim()));
                    break;
                default:
                    break; // a key of a newer version
            }
        }
    }

    private static void putText(ZipOutputStream zip, String name, String text) throws IOException {
        put(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long size = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            size += n;
            if (size > limit) {
                throw new BackupException(BackupException.Reason.TOO_LARGE, "Entry too large");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String text(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

}
