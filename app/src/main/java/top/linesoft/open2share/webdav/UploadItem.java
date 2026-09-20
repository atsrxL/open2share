package top.linesoft.open2share.webdav;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One file (or text snippet) that is waiting to be uploaded.
 * <p>
 * The file descriptor is opened while the share activity is still running, because the read
 * permission that the sharing app grants us is tied to that activity; it is revoked as soon as the
 * activity finishes, which would leave the upload service without access to the file.
 */
public class UploadItem {

    private static final String TAG = "WebDavUpload";

    public final String fileName;
    public final String mimeType;
    public final long size;
    @Nullable
    private final ParcelFileDescriptor descriptor;
    @Nullable
    private final byte[] content;

    private UploadItem(String fileName, String mimeType, long size,
                       @Nullable ParcelFileDescriptor descriptor, @Nullable byte[] content) {
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.size = size;
        this.descriptor = descriptor;
        this.content = content;
    }

    /** Opens {@code uri} and collects the metadata needed for the upload. */
    public static UploadItem fromUri(Context context, Uri uri, @Nullable String fallbackMimeType)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String mimeType = resolver.getType(uri);
        if (TextUtils.isEmpty(mimeType)) {
            mimeType = fallbackMimeType;
        }
        String fileName = resolveFileName(resolver, uri, mimeType);
        if (TextUtils.isEmpty(mimeType) || "*/*".equals(mimeType)) {
            mimeType = guessMimeType(fileName);
        }
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r");
        if (descriptor == null) {
            throw new FileNotFoundException(uri.toString());
        }
        long size = querySize(resolver, uri);
        if (size <= 0) {
            long statSize = descriptor.getStatSize();
            size = statSize > 0 ? statSize : -1;
        }
        return new UploadItem(fileName, mimeType, size, descriptor, null);
    }

    /** A text snippet that is uploaded as a UTF-8 text file. */
    public static UploadItem fromText(String text, @Nullable String subject) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        return new UploadItem(buildTextFileName(subject), "text/plain; charset=utf-8",
                bytes.length, null, bytes);
    }

    /**
     * Opens the content for reading. Can be called more than once as long as the underlying file is
     * seekable, which is the case for regular files.
     */
    public InputStream openStream() throws IOException {
        if (content != null) {
            return new java.io.ByteArrayInputStream(content);
        }
        if (descriptor == null) {
            throw new IOException("File is no longer available");
        }
        ParcelFileDescriptor duplicate = descriptor.dup();
        try {
            android.system.Os.lseek(duplicate.getFileDescriptor(), 0, android.system.OsConstants.SEEK_SET);
        } catch (android.system.ErrnoException e) {
            // Not seekable (a pipe, for instance); the stream can then only be read once.
            Log.d(TAG, "Stream is not seekable: " + e.getMessage());
        }
        return new ParcelFileDescriptor.AutoCloseInputStream(duplicate);
    }

    public void close() {
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException e) {
                Log.w(TAG, "Could not close the file", e);
            }
        }
    }

    private static String resolveFileName(ContentResolver resolver, Uri uri, @Nullable String mimeType) {
        String name = null;
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0 && !cursor.isNull(index)) {
                        name = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not read the file name", e);
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = uri.getLastPathSegment();
        }
        if (!TextUtils.isEmpty(name)) {
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        }
        if (TextUtils.isEmpty(name)) {
            name = "file_" + timestamp("yyyyMMdd_HHmmss");
        }
        if (!name.contains(".") && !TextUtils.isEmpty(mimeType)) {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
            if (!TextUtils.isEmpty(extension)) {
                name = name + "." + extension;
            }
        }
        return name;
    }

    private static long querySize(ContentResolver resolver, Uri uri) {
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme()) && uri.getPath() != null) {
            return new java.io.File(uri.getPath()).length();
        }
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read the file size", e);
        }
        return -1;
    }

    private static String guessMimeType(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String type = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(fileName.substring(dot + 1).toLowerCase(Locale.US));
            if (!TextUtils.isEmpty(type)) {
                return type;
            }
        }
        return "application/octet-stream";
    }

    private static String buildTextFileName(@Nullable String subject) {
        String time = timestamp("yyyyMMdd_HHmm");
        if (!TextUtils.isEmpty(subject)) {
            String cleaned = subject.replaceAll("[\\\\/:*?\"<>|\n\r]", "_").trim();
            if (cleaned.length() > 60) {
                cleaned = cleaned.substring(0, 60);
            }
            if (!cleaned.isEmpty()) {
                return cleaned + "_" + time + ".txt";
            }
        }
        return "clip_" + time + ".txt";
    }

    private static String timestamp(String pattern) {
        return new SimpleDateFormat(pattern, Locale.US).format(new Date());
    }
}
