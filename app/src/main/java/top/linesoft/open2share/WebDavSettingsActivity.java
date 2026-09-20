package top.linesoft.open2share;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.HttpUrl;
import top.linesoft.open2share.webdav.WebDavClient;
import top.linesoft.open2share.webdav.WebDavConfig;

/**
 * Lets the user configure the WebDAV target: protocol, host, port, base path, credentials and the
 * upload folders. The "test" button performs a PROPFIND against the server with the values that are
 * currently in the form, without having to save them first.
 */
public class WebDavSettingsActivity extends AppCompatActivity {

    private MaterialSwitch enabledSwitch;
    private MaterialAutoCompleteTextView schemeInput;
    private TextInputEditText hostInput;
    private TextInputEditText portInput;
    private TextInputEditText basePathInput;
    private TextInputEditText usernameInput;
    private TextInputEditText passwordInput;
    private TextInputEditText foldersInput;
    private MaterialSwitch askFolderSwitch;
    private MaterialSwitch createDirsSwitch;
    private MaterialSwitch overwriteSwitch;
    private MaterialSwitch trustAllCertsSwitch;
    private MaterialButton testButton;
    private TextView statusText;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_webdav_settings);
        setTitle(R.string.webdav_title);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        enabledSwitch = findViewById(R.id.switch_enabled);
        schemeInput = findViewById(R.id.input_scheme);
        hostInput = findViewById(R.id.input_host);
        portInput = findViewById(R.id.input_port);
        basePathInput = findViewById(R.id.input_base_path);
        usernameInput = findViewById(R.id.input_username);
        passwordInput = findViewById(R.id.input_password);
        foldersInput = findViewById(R.id.input_folders);
        askFolderSwitch = findViewById(R.id.switch_ask_folder);
        createDirsSwitch = findViewById(R.id.switch_create_dirs);
        overwriteSwitch = findViewById(R.id.switch_overwrite);
        trustAllCertsSwitch = findViewById(R.id.switch_trust_all_certs);
        testButton = findViewById(R.id.button_test);
        statusText = findViewById(R.id.text_status);

        schemeInput.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new String[]{WebDavConfig.SCHEME_HTTPS, WebDavConfig.SCHEME_HTTP}));

        notificationPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                });

        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                requestNotificationPermission();
            }
        });
        trustAllCertsSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (checked && button.isPressed()) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.warn)
                        .setMessage(R.string.webdav_trust_all_certs_warning)
                        .setPositiveButton(R.string.yes, null)
                        .setNegativeButton(R.string.no, (dialog, which) -> trustAllCertsSwitch.setChecked(false))
                        .show();
            }
        });

        testButton.setOnClickListener(view -> testConnection());
        findViewById(R.id.button_save).setOnClickListener(view -> {
            if (save()) {
                Toast.makeText(this, R.string.webdav_saved, Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        load();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            save();
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void load() {
        WebDavConfig config = WebDavConfig.load(this);
        enabledSwitch.setChecked(config.enabled);
        schemeInput.setText(config.scheme, false);
        hostInput.setText(config.host);
        portInput.setText(config.port > 0 ? String.valueOf(config.port) : "");
        basePathInput.setText(config.basePath);
        usernameInput.setText(config.username);
        passwordInput.setText(config.password);
        foldersInput.setText(config.folders);
        askFolderSwitch.setChecked(config.askFolder);
        createDirsSwitch.setChecked(config.createDirs);
        overwriteSwitch.setChecked(config.overwrite);
        trustAllCertsSwitch.setChecked(config.trustAllCerts);
        updateStatus(config);
    }

    /** Reads the form into a config object, accepting a full URL in the "host" field as well. */
    private WebDavConfig readForm() {
        WebDavConfig config = new WebDavConfig();
        config.enabled = enabledSwitch.isChecked();
        config.scheme = WebDavConfig.SCHEME_HTTP.equals(text(schemeInput))
                ? WebDavConfig.SCHEME_HTTP
                : WebDavConfig.SCHEME_HTTPS;
        config.host = text(hostInput);
        config.port = WebDavConfig.parsePort(text(portInput));
        config.basePath = WebDavConfig.trimSlashes(text(basePathInput));
        config.username = text(usernameInput);
        config.password = text(passwordInput);
        config.folders = text(foldersInput);
        config.askFolder = askFolderSwitch.isChecked();
        config.createDirs = createDirsSwitch.isChecked();
        config.overwrite = overwriteSwitch.isChecked();
        config.trustAllCerts = trustAllCertsSwitch.isChecked();

        // Convenience: the user may paste a complete URL such as "https://nas.example.com:5006/dav".
        if (config.host.contains("://")) {
            HttpUrl url = HttpUrl.parse(config.host.trim());
            if (url != null) {
                config.scheme = url.scheme();
                config.host = url.host();
                if (url.port() != HttpUrl.defaultPort(url.scheme())) {
                    config.port = url.port();
                }
                String path = WebDavConfig.trimSlashes(url.encodedPath());
                if (!path.isEmpty() && config.basePath.isEmpty()) {
                    config.basePath = path;
                }
            }
        } else if (config.host.contains(":") && !config.host.contains("[")) {
            // "host:port" typed into the host field.
            String[] parts = config.host.trim().split(":", 2);
            int port = WebDavConfig.parsePort(parts[1]);
            if (port > 0) {
                config.host = parts[0];
                config.port = port;
            }
        }
        config.host = WebDavConfig.trimSlashes(config.host);
        return config;
    }

    private boolean save() {
        WebDavConfig config = readForm();
        if (config.enabled && TextUtils.isEmpty(config.host)) {
            statusText.setText(R.string.webdav_host_missing);
            hostInput.requestFocus();
            return false;
        }
        if (config.enabled && config.baseUrl() == null) {
            statusText.setText(R.string.webdav_host_invalid);
            hostInput.requestFocus();
            return false;
        }
        config.save(this);
        applyToForm(config);
        updateStatus(config);
        return true;
    }

    private void applyToForm(WebDavConfig config) {
        schemeInput.setText(config.scheme, false);
        hostInput.setText(config.host);
        portInput.setText(config.port > 0 ? String.valueOf(config.port) : "");
        basePathInput.setText(config.basePath);
    }

    private void testConnection() {
        final WebDavConfig config = readForm();
        if (config.baseUrl() == null) {
            statusText.setText(R.string.webdav_host_invalid);
            return;
        }
        testButton.setEnabled(false);
        statusText.setText(R.string.webdav_testing);
        final String folder = config.folderList().get(0);
        executor.execute(() -> {
            String message;
            try {
                WebDavClient client = new WebDavClient(config);
                String result = client.testConnection(folder);
                if (config.createDirs && !TextUtils.isEmpty(folder)) {
                    try {
                        client.createFolders(folder);
                    } catch (Exception ignored) {
                        // Reported by the upload itself if it really is a problem.
                    }
                }
                message = getString(R.string.webdav_test_success) + "\n" + result;
            } catch (Exception e) {
                message = getString(R.string.webdav_test_failed) + "\n" + WebDavClient.describeError(e);
            }
            final String finalMessage = message;
            mainHandler.post(() -> {
                testButton.setEnabled(true);
                statusText.setText(finalMessage);
            });
        });
    }

    private void updateStatus(WebDavConfig config) {
        HttpUrl url = config.folderUrl(config.folderList().get(0));
        statusText.setText(url != null ? getString(R.string.webdav_current_target, url.toString()) : "");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private static String text(TextView view) {
        CharSequence value = view.getText();
        return value == null ? "" : value.toString().trim();
    }
}
