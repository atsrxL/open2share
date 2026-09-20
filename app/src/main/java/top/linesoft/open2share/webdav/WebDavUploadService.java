package top.linesoft.open2share.webdav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.HttpUrl;
import top.linesoft.open2share.R;
import top.linesoft.open2share.WebDavSettingsActivity;

/**
 * Uploads the shared files to the configured WebDAV server. It runs as a foreground service so that
 * the transfer survives the (invisible) share activity being closed right away.
 */
public class WebDavUploadService extends Service {

    private static final String TAG = "WebDavUpload";

    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_FOLDER = "folder";

    private static final String CHANNEL_PROGRESS = "webdav_progress";
    private static final String CHANNEL_RESULT = "webdav_result";
    private static final int NOTIFICATION_ID_PROGRESS = 4711;

    private final AtomicInteger pendingJobs = new AtomicInteger();
    private final AtomicInteger resultNotificationId = new AtomicInteger(4712);
    private ExecutorService executor;
    private NotificationManagerCompat notificationManager;
    private PowerManager.WakeLock wakeLock;
    private long lastProgressUpdate;

    /** Posts an error notification; used by the share activity when the upload cannot even start. */
    public static void notifyFailure(Context context, String detail) {
        createChannels(context);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setContentTitle(context.getString(R.string.webdav_upload_failed))
                .setContentText(detail)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(
                        context,
                        0,
                        new Intent(context, WebDavSettingsActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        try {
            NotificationManagerCompat.from(context).notify(4799, builder.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Missing notification permission", e);
        }
    }

    public static Intent newIntent(Context context, String requestId, String folder) {
        Intent intent = new Intent(context, WebDavUploadService.class);
        intent.putExtra(EXTRA_REQUEST_ID, requestId);
        intent.putExtra(EXTRA_FOLDER, folder);
        return intent;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        notificationManager = NotificationManagerCompat.from(this);
        createChannels(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final String folder = WebDavConfig.trimSlashes(intent.getStringExtra(EXTRA_FOLDER));
        final List<UploadItem> items = UploadRequestStore.take(intent.getStringExtra(EXTRA_REQUEST_ID));
        final WebDavConfig config = WebDavConfig.load(this);

        pendingJobs.incrementAndGet();
        startForeground(buildProgressNotification(getString(R.string.webdav_uploading), null, 0, 0, 0));
        acquireWakeLock();

        executor.execute(() -> {
            try {
                if (items == null || items.isEmpty()) {
                    notifyResult(getString(R.string.webdav_upload_failed),
                            getString(R.string.webdav_nothing_to_upload), true);
                } else {
                    runJob(config, items, folder);
                }
            } catch (Exception e) {
                Log.e(TAG, "Upload job failed", e);
                notifyResult(getString(R.string.webdav_upload_failed), WebDavClient.describeError(e), true);
            } finally {
                if (items != null) {
                    for (UploadItem item : items) {
                        item.close();
                    }
                }
                if (pendingJobs.decrementAndGet() <= 0) {
                    releaseWakeLock();
                    stopForeground(true);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        if (executor != null) {
            executor.shutdown();
        }
        super.onDestroy();
    }

    private void runJob(WebDavConfig config, List<UploadItem> items, String folder) {
        WebDavClient client = new WebDavClient(config);
        if (config.createDirs && !TextUtils.isEmpty(folder)) {
            try {
                client.createFolders(folder);
            } catch (IOException e) {
                Log.w(TAG, "Could not create the target folder", e);
            }
        }

        int total = items.size();
        int succeeded = 0;
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            UploadItem item = items.get(i);
            updateProgress(item.fileName, i + 1, total, 0);
            try {
                upload(client, config, folder, item, i + 1, total);
                succeeded++;
            } catch (Exception e) {
                Log.e(TAG, "Upload of " + item.fileName + " failed", e);
                errors.add(item.fileName + ": " + WebDavClient.describeError(e));
            }
        }

        String target = TextUtils.isEmpty(folder) ? "/" : folder;
        if (errors.isEmpty()) {
            String title = total == 1
                    ? getString(R.string.webdav_upload_succeeded)
                    : getString(R.string.webdav_upload_succeeded_multiple, succeeded);
            String detail = (total == 1 ? items.get(0).fileName + "\n" : "")
                    + getString(R.string.webdav_uploaded_to, target);
            notifyResult(title, detail, false);
        } else {
            notifyResult(getString(R.string.webdav_upload_failed), TextUtils.join("\n", errors), true);
        }
    }

    private void upload(WebDavClient client, WebDavConfig config, String folder, final UploadItem item,
                        int index, int total) throws IOException {
        File cacheFile = null;
        try {
            long length = item.size;
            if (length <= 0 || !item.isReReadable()) {
                // The size is unknown, or the provider only handed us a one-shot stream: buffer the
                // file first, so that the PUT request can send a Content-Length instead of using
                // chunked encoding (which not every server accepts) and the body stays re-readable.
                cacheFile = copyToCache(item);
                length = cacheFile.length();
            }
            final File source = cacheFile;
            final String name = uploadName(client, config, folder, item.fileName);
            HttpUrl url = config.fileUrl(folder, name);
            if (url == null) {
                throw new IOException("Invalid server address");
            }
            final long totalBytes = length;
            client.put(url,
                    () -> source != null ? new FileInputStream(source) : item.openStream(),
                    item.mimeType,
                    length,
                    (written, ignored) -> {
                        int percent = totalBytes > 0 ? (int) (written * 100 / totalBytes) : 0;
                        updateProgress(name, index, total, percent);
                    });
        } finally {
            if (cacheFile != null && !cacheFile.delete()) {
                cacheFile.deleteOnExit();
            }
        }
    }

    /** Returns the name to upload with, renaming the file when it exists and overwriting is off. */
    private String uploadName(WebDavClient client, WebDavConfig config, String folder, String fileName)
            throws IOException {
        if (config.overwrite) {
            return fileName;
        }
        HttpUrl url = config.fileUrl(folder, fileName);
        if (url == null || !client.exists(url)) {
            return fileName;
        }
        String suffix = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        int dot = fileName.lastIndexOf('.');
        String candidate = insertSuffix(fileName, dot, suffix);
        for (int i = 2; i < 100; i++) {
            HttpUrl candidateUrl = config.fileUrl(folder, candidate);
            if (candidateUrl == null || !client.exists(candidateUrl)) {
                return candidate;
            }
            candidate = insertSuffix(fileName, dot, suffix + "_" + i);
        }
        return candidate;
    }

    private static String insertSuffix(String fileName, int dot, String suffix) {
        return dot > 0
                ? fileName.substring(0, dot) + "_" + suffix + fileName.substring(dot)
                : fileName + "_" + suffix;
    }

    private File copyToCache(UploadItem item) throws IOException {
        File cacheDir = new File(getCacheDir(), "uploads");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("Could not create the cache directory");
        }
        File file = new File(cacheDir, "upload_" + System.nanoTime());
        try (InputStream in = item.openStream();
             OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return file;
    }

    private void updateProgress(String fileName, int index, int total, int percent) {
        long now = System.currentTimeMillis();
        if (percent > 0 && percent < 100 && now - lastProgressUpdate < 400) {
            return;
        }
        lastProgressUpdate = now;
        notify(NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(getString(R.string.webdav_uploading), fileName, index, total, percent));
    }

    private Notification buildProgressNotification(String title, @Nullable String fileName,
                                                   int index, int total, int percent) {
        String text = fileName == null ? "" : fileName;
        if (total > 1) {
            text = "(" + index + "/" + total + ") " + text;
        }
        return new NotificationCompat.Builder(this, CHANNEL_PROGRESS)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, percent, percent <= 0)
                .build();
    }

    private void notifyResult(String title, @Nullable String detail, boolean isError) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_RESULT)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setSmallIcon(isError
                        ? android.R.drawable.stat_notify_error
                        : android.R.drawable.stat_sys_upload_done)
                .setAutoCancel(true)
                .setPriority(isError ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_LOW);
        if (isError) {
            builder.setContentIntent(PendingIntent.getActivity(
                    this,
                    0,
                    new Intent(this, WebDavSettingsActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        notify(resultNotificationId.incrementAndGet(), builder.build());
    }

    private void notify(int id, Notification notification) {
        try {
            notificationManager.notify(id, notification);
        } catch (SecurityException e) {
            // The notification permission was not granted; the upload itself still runs.
            Log.w(TAG, "Missing notification permission", e);
        }
    }

    private void startForeground(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID_PROGRESS, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID_PROGRESS, notification);
        }
    }

    private static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_PROGRESS,
                    context.getString(R.string.webdav_channel_progress), NotificationManager.IMPORTANCE_LOW));
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_RESULT,
                    context.getString(R.string.webdav_channel_result), NotificationManager.IMPORTANCE_DEFAULT));
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "open2share:webdav");
            wakeLock.acquire(30 * 60 * 1000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        wakeLock = null;
    }
}
