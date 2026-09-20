package top.linesoft.open2share.webdav;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands the opened files over from the share activity to the upload service. They live in the same
 * process, so there is no need to send the file descriptors through an intent.
 */
public final class UploadRequestStore {

    private static final Map<String, List<UploadItem>> REQUESTS = new HashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private UploadRequestStore() {
    }

    public static synchronized String put(List<UploadItem> items) {
        String id = "upload_" + NEXT_ID.incrementAndGet();
        REQUESTS.put(id, items);
        return id;
    }

    @Nullable
    public static synchronized List<UploadItem> take(String id) {
        return REQUESTS.remove(id);
    }
}
