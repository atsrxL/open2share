package top.linesoft.open2share.webdav;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

import okhttp3.HttpUrl;

/**
 * Holds the WebDAV server settings. The values are kept in the default SharedPreferences, so that
 * they can be edited both from {@link top.linesoft.open2share.WebDavSettingsActivity} and read from
 * the upload service without any extra plumbing.
 */
public class WebDavConfig {

    public static final String KEY_ENABLED = "webdav_enabled";
    public static final String KEY_SCHEME = "webdav_scheme";
    public static final String KEY_HOST = "webdav_host";
    public static final String KEY_PORT = "webdav_port";
    public static final String KEY_BASE_PATH = "webdav_base_path";
    public static final String KEY_USERNAME = "webdav_username";
    public static final String KEY_PASSWORD = "webdav_password";
    public static final String KEY_FOLDERS = "webdav_folders";
    public static final String KEY_ASK_FOLDER = "webdav_ask_folder";
    public static final String KEY_CREATE_DIRS = "webdav_create_dirs";
    public static final String KEY_OVERWRITE = "webdav_overwrite";
    public static final String KEY_TRUST_ALL_CERTS = "webdav_trust_all_certs";
    public static final String KEY_LAST_FOLDER = "webdav_last_folder";

    public static final String SCHEME_HTTP = "http";
    public static final String SCHEME_HTTPS = "https";

    public boolean enabled;
    public String scheme = SCHEME_HTTPS;
    public String host = "";
    /** 0 means "use the default port of the scheme". */
    public int port;
    public String basePath = "";
    public String username = "";
    public String password = "";
    public String folders = "";
    public boolean askFolder = true;
    public boolean createDirs = true;
    public boolean overwrite;
    public boolean trustAllCerts;

    public static WebDavConfig load(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        WebDavConfig config = new WebDavConfig();
        config.enabled = prefs.getBoolean(KEY_ENABLED, false);
        config.scheme = prefs.getString(KEY_SCHEME, SCHEME_HTTPS);
        config.host = prefs.getString(KEY_HOST, "");
        config.port = parsePort(prefs.getString(KEY_PORT, ""));
        config.basePath = prefs.getString(KEY_BASE_PATH, "");
        config.username = prefs.getString(KEY_USERNAME, "");
        config.password = prefs.getString(KEY_PASSWORD, "");
        config.folders = prefs.getString(KEY_FOLDERS, "");
        config.askFolder = prefs.getBoolean(KEY_ASK_FOLDER, true);
        config.createDirs = prefs.getBoolean(KEY_CREATE_DIRS, true);
        config.overwrite = prefs.getBoolean(KEY_OVERWRITE, false);
        config.trustAllCerts = prefs.getBoolean(KEY_TRUST_ALL_CERTS, false);
        return config;
    }

    public void save(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_SCHEME, scheme)
                .putString(KEY_HOST, host)
                .putString(KEY_PORT, port > 0 ? String.valueOf(port) : "")
                .putString(KEY_BASE_PATH, basePath)
                .putString(KEY_USERNAME, username)
                .putString(KEY_PASSWORD, password)
                .putString(KEY_FOLDERS, folders)
                .putBoolean(KEY_ASK_FOLDER, askFolder)
                .putBoolean(KEY_CREATE_DIRS, createDirs)
                .putBoolean(KEY_OVERWRITE, overwrite)
                .putBoolean(KEY_TRUST_ALL_CERTS, trustAllCerts)
                .apply();
    }

    public static int parsePort(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return 0;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 && port <= 65535 ? port : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public boolean isComplete() {
        return !TextUtils.isEmpty(host) && baseUrl() != null;
    }

    /** The server root, including the optional base path, e.g. {@code https://nas.example.com:5006/dav}. */
    public HttpUrl baseUrl() {
        if (TextUtils.isEmpty(host)) {
            return null;
        }
        try {
            HttpUrl.Builder builder = new HttpUrl.Builder()
                    .scheme(SCHEME_HTTP.equals(scheme) ? SCHEME_HTTP : SCHEME_HTTPS)
                    .host(host.trim());
            if (port > 0) {
                builder.port(port);
            }
            for (String segment : splitPath(basePath)) {
                builder.addPathSegment(segment);
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The URL of {@code folder} (may be empty for the root), always with a trailing slash. */
    public HttpUrl folderUrl(String folder) {
        HttpUrl base = baseUrl();
        if (base == null) {
            return null;
        }
        HttpUrl.Builder builder = base.newBuilder();
        for (String segment : splitPath(folder)) {
            builder.addPathSegment(segment);
        }
        // A collection URL must end with a slash, otherwise some servers answer with a redirect.
        builder.addPathSegment("");
        return builder.build();
    }

    public HttpUrl fileUrl(String folder, String fileName) {
        HttpUrl base = baseUrl();
        if (base == null) {
            return null;
        }
        HttpUrl.Builder builder = base.newBuilder();
        for (String segment : splitPath(folder)) {
            builder.addPathSegment(segment);
        }
        builder.addPathSegment(fileName);
        return builder.build();
    }

    /** The configured upload folders, one per line. Always contains at least one entry. */
    public List<String> folderList() {
        List<String> result = new ArrayList<>();
        if (!TextUtils.isEmpty(folders)) {
            for (String line : folders.split("\n")) {
                String folder = trimSlashes(line);
                if (!folder.isEmpty() && !result.contains(folder)) {
                    result.add(folder);
                }
            }
        }
        if (result.isEmpty()) {
            result.add("");
        }
        return result;
    }

    public static List<String> splitPath(String path) {
        List<String> segments = new ArrayList<>();
        if (TextUtils.isEmpty(path)) {
            return segments;
        }
        for (String segment : path.split("/")) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                segments.add(trimmed);
            }
        }
        return segments;
    }

    public static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.trim();
    }
}
