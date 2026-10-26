package id.bootmaker.cok;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * BootMakar UI. The layout replicates the original BootcardMaker
 * controls with the same labels and flow: "Choose disk", a u-boot
 * .bin picker, the "To Partition and Format SD-card:" section, a
 * label field, "Make" / "Cancel" buttons, an "About BootMakar"
 * dialog, and a log panel.
 *
 * <p>Root-only adaptation (this app requires a rooted device, no
 * exceptions): on startup the app checks {@code su -c id} for uid 0.
 * Without root, every write path is disabled and the banner says so
 * plainly — there is no silent fallback. Direct SD write is the
 * primary mode; image-file creation is a secondary mode for rooted
 * users who want to prepare or share an .img.</p>
 *
 * <p>Language (English / Bahasa Indonesia) and theme (system / light
 * / dark) follow the UBT-project pattern: a {@code t(en, id)} helper
 * and SharedPreferences, default language English because the
 * original tool is English.</p>
 */
public class MainActivity extends Activity {
    private static final String PREFS = "bootmakar_prefs";
    private static final int REQ_UBOOT = 1001;
    private static final int REQ_IMAGE = 1002;

    private static final long[] IMAGE_SIZES = {
            512L * 1024 * 1024, 1024L * 1024 * 1024,
            2L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024};
    private static final String[] IMAGE_SIZE_LABELS = {"512M", "1G", "2G", "4G"};

    private String language = "en";
    private boolean darkMode;
    private int bg;
    private int text;
    private int muted;
    private int accent;

    private Boolean rooted;
    private boolean jobRunning;
    private boolean modeDirect = true;

    private Uri ubootUri;
    private boolean ubootValid;
    private long ubootSize;

    private List<RootShell.BlockDevice> removableDisks = new ArrayList<>();
    private RootShell.BlockDevice selectedDisk;
    private long imageSizeBytes = IMAGE_SIZES[0];

    private TextView rootBanner;
    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private Button makeButton;
    private Button cancelButton;
    private Spinner diskSpinner;
    private LinearLayout diskRow;
    private LinearLayout sizeRow;
    private TextView modeHelp;
    private TextView ubootLabel;
    private TextView ubootStatus;
    private EditText labelEdit;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (CardBroadcasts.ACTION_LOG.equals(action)) {
                appendLog(intent.getStringExtra("line"));
            } else if (CardBroadcasts.ACTION_PROGRESS.equals(action)) {
                String step = intent.getStringExtra("step");
                int pct = intent.getIntExtra("percent", -1);
                statusView.setText(pct >= 0 ? step + ": " + pct + "%" : step);
            } else if (CardBroadcasts.ACTION_RESULT.equals(action)) {
                jobRunning = false;
                String outcome = intent.getStringExtra("outcome");
                String reason = intent.getStringExtra("reason");
                appendLog("Result: " + outcome + (reason != null ? " — " + reason : ""));
                statusView.setText(t("Idle", "Siaga"));
                updateMakeEnabled();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        language = prefs.getString("language", "en");
        String themeMode = prefs.getString("theme", "system");
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        darkMode = "dark".equals(themeMode)
                || ("system".equals(themeMode) && night == Configuration.UI_MODE_NIGHT_YES);
        if (darkMode) {
            setTheme(R.style.AppThemeDark);
        }
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        bg = darkMode ? Color.parseColor("#121212") : Color.WHITE;
        text = darkMode ? Color.parseColor("#E0E0E0") : Color.parseColor("#212121");
        muted = Color.parseColor("#888888");
        accent = darkMode ? Color.parseColor("#90CAF9") : Color.parseColor("#1565C0");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText("BootMakar");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(text);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        toolbar.addView(title, titleParams);
        toolbar.addView(spinnerLabel(t("Language", "Bahasa")));
        Spinner languageSpinner = spinner(new String[]{"English", "Bahasa Indonesia"});
        languageSpinner.setSelection("id".equals(language) ? 1 : 0);
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String selected = pos == 1 ? "id" : "en";
                if (!selected.equals(language)) {
                    language = selected;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("language", selected).apply();
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        toolbar.addView(languageSpinner, new LinearLayout.LayoutParams(dp(148), dp(34)));
        toolbar.addView(spinnerLabel(t("Theme", "Tema")));
        Spinner themeSpinner = spinner(new String[]{
                t("System", "Sistem"), t("Light", "Terang"), t("Dark", "Gelap")});
        themeSpinner.setSelection("light".equals(themeMode) ? 1
                : ("dark".equals(themeMode) ? 2 : 0));
        themeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String selected = pos == 1 ? "light" : (pos == 2 ? "dark" : "system");
                if (!selected.equals(getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString("theme", "system"))) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString("theme", selected).apply();
                    recreate();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        toolbar.addView(themeSpinner, new LinearLayout.LayoutParams(dp(84), dp(34)));
        root.addView(toolbar);

        rootBanner = new TextView(this);
        rootBanner.setText(t("Checking root...", "Memeriksa root..."));
        rootBanner.setTextColor(muted);
        rootBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(rootBanner);
        Button recheckButton = button(t("Recheck root", "Periksa root lagi"));
        recheckButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkRoot();
            }
        });
        root.addView(recheckButton);

        root.addView(sectionLabel(t("Mode", "Mode")));
        Spinner modeSpinner = spinner(new String[]{
                t("Direct SD card (root)", "Kartu SD langsung (root)"),
                t("Image file (secondary)", "Berkas image (sekunder)")});
        modeSpinner.setSelection(0);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                modeDirect = pos == 0;
                updateModeViews();
                updateMakeEnabled();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        root.addView(modeSpinner);
        modeHelp = new TextView(this);
        modeHelp.setTextColor(muted);
        modeHelp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(modeHelp);

        diskRow = new LinearLayout(this);
        diskRow.setOrientation(LinearLayout.VERTICAL);
        diskRow.addView(sectionLabel("Choose disk"));
        diskSpinner = spinner(new String[]{t("Checking root...", "Memeriksa root...")});
        diskSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos >= 0 && pos < removableDisks.size()) {
                    selectedDisk = removableDisks.get(pos);
                } else {
                    selectedDisk = null;
                }
                updateMakeEnabled();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        diskRow.addView(diskSpinner);
        Button refreshButton = button(t("Refresh disks", "Segarkan daftar disk"));
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshDisks();
            }
        });
        diskRow.addView(refreshButton);
        root.addView(diskRow);

        sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.VERTICAL);
        sizeRow.addView(sectionLabel(t("Image size", "Ukuran image")));
        Spinner sizeSpinner = spinner(IMAGE_SIZE_LABELS);
        sizeSpinner.setSelection(0);
        sizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                imageSizeBytes = IMAGE_SIZES[pos];
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        sizeRow.addView(sizeSpinner);
        root.addView(sizeRow);

        root.addView(sectionLabel(t("u-boot file (.bin)", "Berkas u-boot (.bin)")));
        Button pickButton = button(t("Choose u-boot .bin...", "Pilih u-boot .bin..."));
        pickButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                pick.putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/octet-stream"});
                pick.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(pick, REQ_UBOOT);
            }
        });
        root.addView(pickButton);
        ubootLabel = new TextView(this);
        ubootLabel.setText(t("No u-boot file selected.", "Belum ada berkas u-boot dipilih."));
        ubootLabel.setTextColor(muted);
        root.addView(ubootLabel);
        ubootStatus = new TextView(this);
        ubootStatus.setTextColor(muted);
        root.addView(ubootStatus);

        root.addView(sectionLabel("To Partition and Format SD-card:"));
        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView labelCaption = new TextView(this);
        labelCaption.setText(t("Label", "Label"));
        labelCaption.setTextColor(text);
        labelRow.addView(labelCaption, new LinearLayout.LayoutParams(dp(70),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        labelEdit = new EditText(this);
        labelEdit.setText("BOOT");
        labelEdit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(11)});
        labelEdit.setTextColor(text);
        labelRow.addView(labelEdit, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(labelRow);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        makeButton = button("Make");
        makeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onMakeClicked();
            }
        });
        buttons.addView(makeButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        cancelButton = button("Cancel");
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent cancel = new Intent(MainActivity.this, CardService.class);
                cancel.setAction(CardServiceActions.CANCEL);
                startService(cancel);
                appendLog("format canceled!");
            }
        });
        buttons.addView(cancelButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button aboutButton = button(t("About", "Tentang"));
        aboutButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("About BootMakar")
                        .setMessage("BootMakar 0.1.0\n\n"
                                + "A port of Amlogic BootcardMaker for rooted Android devices.\n"
                                + "Direct SD card writing is the primary function; image-file "
                                + "creation is a secondary mode for rooted users.\n\n"
                                + "This app REQUIRES a rooted device. Without root there is "
                                + "no write path at all.\n\n"
                                + "Direct writes ERASE the entire target card. Verify the "
                                + "device name twice before confirming.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
        buttons.addView(aboutButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(buttons);

        statusView = new TextView(this);
        statusView.setText(t("Idle", "Siaga"));
        statusView.setTextColor(accent);
        root.addView(statusView);

        TextView logCaption = new TextView(this);
        logCaption.setText(t("Log", "Log"));
        logCaption.setTextColor(text);
        root.addView(logCaption);
        logView = new TextView(this);
        logView.setTextColor(text);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220)));

        TextView help = new TextView(this);
        help.setText(t(
                "Direct mode writes the partition, u-boot and FAT format straight to the "
                        + "SD card block device (root required, all content erased). Image mode "
                        + "saves a .img file instead; write it to a card later with Rufus, "
                        + "balenaEtcher or dd. Both modes require a rooted device.",
                "Mode langsung menulis partisi, u-boot, dan format FAT langsung ke perangkat "
                        + "blok kartu SD (butuh root, seluruh isi terhapus). Mode image menyimpan "
                        + "berkas .img; tulis ke kartu nanti dengan Rufus, balenaEtcher, atau dd. "
                        + "Kedua mode membutuhkan perangkat yang sudah di-root."));
        help.setTextColor(muted);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(help);

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);

        updateModeViews();
        updateMakeEnabled();
        checkRoot();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter();
        filter.addAction(CardBroadcasts.ACTION_PROGRESS);
        filter.addAction(CardBroadcasts.ACTION_LOG);
        filter.addAction(CardBroadcasts.ACTION_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_UBOOT) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (RuntimeException ignored) {
            }
            ubootUri = uri;
            validateUbootPicked();
        } else if (requestCode == REQ_IMAGE) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (RuntimeException ignored) {
            }
            startJob(false, null, uri);
        }
    }

    // ------------------------------------------------------------ root

    private void checkRoot() {
        rooted = null;
        rootBanner.setText(t("Checking root...", "Memeriksa root..."));
        rootBanner.setTextColor(muted);
        updateMakeEnabled();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = RootShell.isRooted();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        rooted = ok;
                        if (ok) {
                            rootBanner.setText(t("Root: OK (uid 0)",
                                    "Root: OK (uid 0)"));
                            rootBanner.setTextColor(accent);
                            refreshDisks();
                        } else {
                            rootBanner.setText(t(
                                    "ROOT REQUIRED: BootMakar needs a rooted device. "
                                            + "No write path is available without root.",
                                    "BUTUH ROOT: BootMakar membutuhkan perangkat yang sudah "
                                            + "di-root. Tanpa root tidak ada jalur penulisan."));
                            rootBanner.setTextColor(Color.parseColor("#C62828"));
                        }
                        updateMakeEnabled();
                    }
                });
            }
        }).start();
    }

    private void refreshDisks() {
        if (rooted == null || !rooted) {
            setDiskSpinnerItems(new String[]{t("Root required", "Butuh root")});
            selectedDisk = null;
            updateMakeEnabled();
            return;
        }
        setDiskSpinnerItems(new String[]{t("Loading disks...", "Memuat disk...")});
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RootShell.BlockDevice> found = new ArrayList<>();
                String error = null;
                try {
                    for (RootShell.BlockDevice d : RootShell.listBlockDevices()) {
                        if (d.removable) {
                            found.add(d);
                        }
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final List<RootShell.BlockDevice> result = found;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        removableDisks = result;
                        if (err != null) {
                            appendLog("Disk list failed: " + err);
                            setDiskSpinnerItems(new String[]{t("Disk list failed",
                                    "Gagal memuat disk")});
                            selectedDisk = null;
                        } else if (result.isEmpty()) {
                            setDiskSpinnerItems(new String[]{t("No removable SD card found",
                                    "Tidak ada kartu SD lepas ditemukan")});
                            selectedDisk = null;
                        } else {
                            String[] names = new String[result.size()];
                            for (int i = 0; i < result.size(); i++) {
                                names[i] = result.get(i).toString();
                            }
                            setDiskSpinnerItems(names);
                            selectedDisk = result.get(0);
                            diskSpinner.setSelection(0);
                        }
                        updateMakeEnabled();
                    }
                });
            }
        }).start();
    }

    private void setDiskSpinnerItems(String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        diskSpinner.setAdapter(adapter);
    }

    // ------------------------------------------------------------ make

    private void validateUbootPicked() {
        ubootValid = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String status;
                boolean valid = false;
                long size = -1;
                try (SafIo.SafSource src = new SafIo.SafSource(
                        getContentResolver(), ubootUri)) {
                    size = src.size();
                    String err = CardLayout.validateUbootSize(size);
                    if (err == null) {
                        valid = true;
                        status = t("Valid u-boot: ", "u-boot valid: ") + size + " bytes";
                    } else {
                        status = err;
                    }
                } catch (Exception e) {
                    status = "Cannot read file: " + e.getMessage();
                }
                final String text = status;
                final boolean ok = valid;
                final long sz = size;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ubootValid = ok;
                        ubootSize = sz;
                        ubootLabel.setText(ubootUri.getLastPathSegment());
                        ubootStatus.setText(text);
                        updateMakeEnabled();
                    }
                });
            }
        }).start();
    }

    private void onMakeClicked() {
        if (rooted == null || !rooted) {
            appendLog(t("Refused: device is not rooted.", "Ditolak: perangkat belum di-root."));
            return;
        }
        if (jobRunning) {
            appendLog(t("A job is already running.", "Sudah ada pekerjaan yang berjalan."));
            return;
        }
        if (!ubootValid || ubootUri == null) {
            appendLog(t("Pick a valid u-boot .bin file first.",
                    "Pilih dulu berkas u-boot .bin yang valid."));
            return;
        }
        if (modeDirect) {
            if (selectedDisk == null) {
                appendLog(t("Pick a target disk first.", "Pilih dulu disk target."));
                return;
            }
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            new AlertDialog.Builder(this)
                    .setTitle("Make")
                    .setMessage(t(
                            "WARNING: ALL CONTENT ON " + selectedDisk.dev
                                    + " WILL BE ERASED.\n\nType the exact device name '"
                                    + selectedDisk.name + "' to continue.",
                            "PERINGATAN: SELURUH ISI " + selectedDisk.dev
                                    + " AKAN DIHAPUS.\n\nKetik persis nama perangkat '"
                                    + selectedDisk.name + "' untuk melanjutkan."))
                    .setView(input)
                    .setPositiveButton("Make", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            String typed = input.getText().toString().trim();
                            if (!typed.equals(selectedDisk.name)) {
                                appendLog("format canceled! (typed name did not match)");
                                return;
                            }
                            startJob(true, selectedDisk, null);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Make")
                    .setMessage(t(
                            "Create a bootcard image file (" + IMAGE_SIZE_LABELS[indexOfSize()]
                                    + "). The .img must later be written to an SD card with "
                                    + "Rufus, balenaEtcher or dd.",
                            "Buat berkas image bootcard (" + IMAGE_SIZE_LABELS[indexOfSize()]
                                    + "). Berkas .img nantinya harus ditulis ke kartu SD "
                                    + "dengan Rufus, balenaEtcher, atau dd."))
                    .setPositiveButton("Make", new android.content.DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(android.content.DialogInterface d, int which) {
                            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            create.addCategory(Intent.CATEGORY_OPENABLE);
                            create.setType("application/octet-stream");
                            create.putExtra(Intent.EXTRA_TITLE, "bootcard.img");
                            create.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                    | Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                            startActivityForResult(create, REQ_IMAGE);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    private int indexOfSize() {
        for (int i = 0; i < IMAGE_SIZES.length; i++) {
            if (IMAGE_SIZES[i] == imageSizeBytes) {
                return i;
            }
        }
        return 0;
    }

    private void startJob(boolean direct, RootShell.BlockDevice disk, Uri imageUri) {
        Intent intent = new Intent(this, CardService.class);
        intent.setAction(CardServiceActions.MAKE);
        intent.putExtra("direct_mode", direct);
        intent.putExtra("uboot_uri", ubootUri.toString());
        intent.putExtra("label", labelEdit.getText().toString());
        if (direct && disk != null) {
            intent.putExtra("device_name", disk.name);
            intent.putExtra("device_path", disk.dev);
            intent.putExtra("confirmed", true);
        } else if (imageUri != null) {
            intent.putExtra("image_uri", imageUri.toString());
            intent.putExtra("image_size", imageSizeBytes);
        }
        jobRunning = true;
        statusView.setText(t("Working...", "Bekerja..."));
        updateMakeEnabled();
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    // ------------------------------------------------------------ view

    private void updateModeViews() {
        diskRow.setVisibility(modeDirect ? View.VISIBLE : View.GONE);
        sizeRow.setVisibility(modeDirect ? View.GONE : View.VISIBLE);
        modeHelp.setText(modeDirect
                ? t("Primary mode: writes straight to the SD card block device. "
                        + "Root required; all content on the card is erased.",
                        "Mode utama: menulis langsung ke perangkat blok kartu SD. "
                                + "Butuh root; seluruh isi kartu terhapus.")
                : t("Secondary mode (rooted users only): prepares a .img file for "
                        + "sharing or later writing. The app still requires root.",
                        "Mode sekunder (hanya untuk pengguna root): menyiapkan berkas .img "
                                + "untuk dibagikan atau ditulis nanti. Aplikasi tetap butuh root."));
    }

    private void updateMakeEnabled() {
        boolean enabled = rooted != null && rooted && !jobRunning && ubootValid
                && (!modeDirect || selectedDisk != null);
        makeButton.setEnabled(enabled);
        cancelButton.setEnabled(jobRunning);
    }

    private void appendLog(String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date());
        logView.append(stamp + "  " + line + "\n");
        logScroll.post(new Runnable() {
            @Override
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private String t(String english, String indonesian) {
        return "id".equals(language) ? indonesian : english;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private TextView sectionLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(accent);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setPadding(0, dp(10), 0, dp(2));
        return v;
    }

    private TextView spinnerLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(muted);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setSingleLine(true);
        v.setPadding(0, 0, dp(4), 0);
        return v;
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(adapter);
        return s;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(text);
        return b;
    }
}
