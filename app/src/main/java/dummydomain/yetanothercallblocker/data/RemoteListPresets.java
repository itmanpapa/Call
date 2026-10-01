package dummydomain.yetanothercallblocker.data;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Built-in suggestions for community number lists that can be added by URL.
 *
 * <p>A preset only fills in the "add list by URL" dialog; nothing is downloaded until
 * the user adds the list. Only lists whose license allows redistribution are offered
 * (lists without a license, e.g. hvoigt/telefon-spam, can still be added manually).</p>
 */
public final class RemoteListPresets {

    /** A suggested list. Immutable. */
    public static final class Preset {

        private final String name;
        private final String url;
        private final String homepage;
        private final String license;
        private final String lastUpdated;

        Preset(String name, String url, String homepage, String license, String lastUpdated) {
            this.name = name;
            this.url = url;
            this.homepage = homepage;
            this.license = license;
            this.lastUpdated = lastUpdated;
        }

        public String getName() {
            return name;
        }

        /** Raw download URL. */
        public String getUrl() {
            return url;
        }

        /** Project page with the description and the license. */
        public String getHomepage() {
            return homepage;
        }

        /** SPDX license id of the list. */
        public String getLicense() {
            return license;
        }

        /** Date of the last change of the list file known at build time (ISO 8601). */
        public String getLastUpdated() {
            return lastUpdated;
        }
    }

    /**
     * dontobi/SpamCalllist: ~380 German and foreign numbers and prefixes, MIT license;
     * the repository is archived (the author recommends PhoneBlock instead).
     * andreaspreuss/fritzbox_blacklists: ~390 German numbers with descriptions
     * (CSV "name,number"; the same list exists as an older Fritz!Box XML export), GPL-2.0.
     */
    private static final List<Preset> PRESETS = Collections.unmodifiableList(Arrays.asList(
            new Preset("SpamCalllist (dontobi)",
                    "https://raw.githubusercontent.com/dontobi/SpamCalllist/main/export/fritzbox_export.xml",
                    "https://github.com/dontobi/SpamCalllist",
                    "MIT",
                    "2024-02-12"),
            new Preset("Fritzbox Blacklist (andreaspreuss)",
                    "https://raw.githubusercontent.com/andreaspreuss/fritzbox_blacklists/master/"
                            + "fritzbox-telephone-blacklist-germany.csv",
                    "https://github.com/andreaspreuss/fritzbox_blacklists",
                    "GPL-2.0",
                    "2020-03-02")
    ));

    private RemoteListPresets() {}

    public static List<Preset> getPresets() {
        return PRESETS;
    }

}
