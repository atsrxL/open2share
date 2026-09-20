package top.linesoft.open2share;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
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

    private List<Uri> uris;
    private String text;
    private String subject;
    private String mimeType;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        uris = extractUris(intent);
        text = intent.getStringExtra(Intent.EXTRA_TEXT);
        subject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        mimeType = intent.getType();

        if (uris.isEmpty() && TextUtils.isEmpty(text)) {
            Toast.makeText(this, R.string.webdav_nothing_to_upload, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        WebDavConfig config = WebDavConfig.load(this);
        if (!config.enabled || !config.isComplete()) {
            showSetupDialog();
            return;
        }

        List<String> folders = config.folderList();
        if (config.askFolder && folders.size() > 1) {
            showFolderPicker(folders);
        } else {
            startUpload(folders.get(0));
        }
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
                .setNegativeButton(R.string.no, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void startUpload(String folder) {
        List<UploadItem> items = new ArrayList<>();
        try {
            for (Uri uri : uris) {
                // Opened here, while this activity still holds the read permission that the sharing
                // app granted us: the grant is gone once the activity finishes.
                items.add(UploadItem.fromUri(this, uri, mimeType));
            }
            if (items.isEmpty() && !TextUtils.isEmpty(text)) {
                items.add(UploadItem.fromText(text, subject));
            }
        } catch (Exception e) {
            for (UploadItem item : items) {
                item.close();
            }
            Toast.makeText(this, getString(R.string.webdav_upload_failed) + "\n" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            String requestId = UploadRequestStore.put(items);
            ContextCompat.startForegroundService(this, WebDavUploadService.newIntent(this, requestId, folder));
            String target = TextUtils.isEmpty(folder) ? "/" : folder;
            Toast.makeText(this, getString(R.string.webdav_upload_started, target), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.webdav_upload_failed) + "\n" + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        finish();
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
            } else if (intent.getData() != null) {
                // Also supports being launched with a VIEW intent ("open with").
                result.add(intent.getData());
            }
        }
        return result;
    }

    @SuppressWarnings("deprecation")
    private static Uri getParcelableExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    @SuppressWarnings("deprecation")
    private static ArrayList<Uri> getParcelableArrayListExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        return intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
    }
}
