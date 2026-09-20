package top.linesoft.open2share;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import top.linesoft.open2share.webdav.UploadItem;
import top.linesoft.open2share.webdav.UploadRequestStore;
import top.linesoft.open2share.webdav.WebDavConfig;
import top.linesoft.open2share.webdav.WebDavUploadService;

/**
 * Receives files (or plain text) from the Android share sheet and hands them over to the WebDAV
 * upload service. The activity itself stays invisible, except for the optional folder picker.
 */
public class ShareReceiveActivity extends AppCompatActivity {

    private static final String TAG = "WebDavUpload";

    private List<Uri> uris;
    private String text;
    private String subject;
    private String mimeType;
    /** Opened right away in onCreate; handed over to the service, or closed again on cancel. */
    private List<UploadItem> items;
    private boolean handedOver;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        uris = extractUris(intent);
        text = intent.getStringExtra(Intent.EXTRA_TEXT);
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        mimeType = intent.getType();
        Log.i(TAG, "Received " + intent.getAction() + " type=" + mimeType
                + " uris=" + uris.size() + " text=" + (text == null ? 0 : text.length()));

        if (uris.isEmpty() && TextUtils.isEmpty(text)) {
            Log.w(TAG, "Nothing to upload in " + intent);
            WebDavUploadService.notifyFailure(this, getString(R.string.webdav_nothing_to_upload)
                    + "\n" + intent.getAction() + " " + mimeType);
            Toast.makeText(this, R.string.webdav_nothing_to_upload, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        WebDavConfig config = WebDavConfig.load(this);
        if (!config.enabled || !config.isComplete()) {
            showSetupDialog();
            return;
        }

        // The files are opened before the folder picker is shown, not after: apps like WeChat hand
        // out a temporary file that they delete as soon as their own screen goes away. Once the file
        // descriptor is open the content stays readable even if that file is removed meanwhile.
        if (!openItems()) {
            return;
        }

        // When the app is opened directly ("open with"), always let the user pick the folder.
        boolean openedDirectly = Intent.ACTION_VIEW.equals(intent.getAction());
        List<String> folders = config.folderList();
        if (openedDirectly || (config.askFolder && folders.size() > 1)) {
            showFolderPicker(folders);
        } else {
            startUpload(folders.get(0));
        }
    }

    /** Opens every shared file; returns false (and finishes) when that is not possible. */
    private boolean openItems() {
        items = new ArrayList<>();
        try {
            for (Uri uri : uris) {
                items.add(UploadItem.fromUri(this, uri, mimeType));
            }
            if (items.isEmpty() && !TextUtils.isEmpty(text)) {
                items.add(UploadItem.fromText(text, subject));
            }
            return true;
        } catch (Exception e) {
            closeItems();
            reportFailure("Could not read the shared file", e);
            finish();
            return false;
        }
    }

    private void closeItems() {
        if (items != null) {
            for (UploadItem item : items) {
                item.close();
            }
            items = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (!handedOver) {
            closeItems();
        }
        super.onDestroy();
    }

    private void showSetupDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.webdav_title)
                .setMessage(R.string.webdav_not_configured)
                .setPositiveButton(R.string.webdav_open_settings, (dialog, which) -> {
                    startActivity(new Intent(this, WebDavSettingsActivity.class));
                    finish();
                })
                .setNegativeButton(R.string.no, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void showFolderPicker(final List<String> folders) {
        String lastFolder = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(WebDavConfig.KEY_LAST_FOLDER, "");
        int checked = Math.max(0, folders.indexOf(lastFolder));
        final int[] selection = {checked};
        String[] labels = new String[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            labels[i] = folders.get(i).isEmpty() ? "/" : folders.get(i);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.webdav_choose_folder)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> selection[0] = which)
                .setPositiveButton(R.string.webdav_upload, (dialog, which) -> {
                    String folder = folders.get(selection[0]);
                    PreferenceManager.getDefaultSharedPreferences(this)
                            .edit()
                            .putString(WebDavConfig.KEY_LAST_FOLDER, folder)
                            .apply();
                    startUpload(folder);
                })
                .setNegativeButton(R.string.no, (dialog, which) -> {
                    closeItems();
                    finish();
                })
                .setOnCancelListener(dialog -> {
                    closeItems();
                    finish();
                })
                .show();
    }

    private void startUpload(String folder) {
        if (items == null || items.isEmpty()) {
            finish();
            return;
        }
        try {
            String requestId = UploadRequestStore.put(items);
            handedOver = true;
            ContextCompat.startForegroundService(this, WebDavUploadService.newIntent(this, requestId, folder));
            String target = TextUtils.isEmpty(folder) ? "/" : folder;
            Toast.makeText(this, getString(R.string.webdav_upload_started, target), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            reportFailure("Could not start the upload", e);
        }
        finish();
    }

    /** A toast is too short-lived for an error, so the reason is also logged and shown as a notification. */
    private void reportFailure(String context, Exception e) {
        Log.e(TAG, context, e);
        String detail = context + ": " + e.getClass().getSimpleName()
                + (e.getMessage() != null ? " - " + e.getMessage() : "");
        WebDavUploadService.notifyFailure(this, detail);
        Toast.makeText(this, getString(R.string.webdav_upload_failed), Toast.LENGTH_LONG).show();
    }

    private List<Uri> extractUris(Intent intent) {
        List<Uri> result = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            List<Uri> extra = getParcelableArrayListExtra(intent);
            if (extra != null) {
                for (Uri uri : extra) {
                    if (uri != null) {
                        result.add(uri);
                    }
                }
            }
        } else {
            Uri uri = getParcelableExtra(intent);
            if (uri != null) {
                result.add(uri);
            }
        }
        if (result.isEmpty()) {
            // Some senders (and the system chooser when it forwards an intent) leave EXTRA_STREAM
            // unreadable; the clip data carries the same URIs and is what the read permission was
            // granted for, so it is the more reliable source.
            ClipData clipData = intent.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    Uri uri = clipData.getItemAt(i).getUri();
                    if (uri != null && !result.contains(uri)) {
                        result.add(uri);
                    }
                }
            }
        }
        if (result.isEmpty() && intent.getData() != null) {
            // Launched with a VIEW intent ("open with").
            result.add(intent.getData());
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private static Uri getParcelableExtra(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                if (uri != null) {
                    return uri;
                }
            }
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        } catch (Exception e) {
            Log.w(TAG, "Could not read EXTRA_STREAM", e);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<Uri> getParcelableArrayListExtra(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
                if (uris != null) {
                    return uris;
                }
            }
            return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        } catch (Exception e) {
            Log.w(TAG, "Could not read EXTRA_STREAM", e);
            return null;
        }
    }
}
