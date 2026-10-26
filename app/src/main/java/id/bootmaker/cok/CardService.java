package id.bootmaker.cok;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.content.pm.ServiceInfo;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Broadcast actions for card progress/log/result (package-scoped). */
class CardBroadcasts {
    static final String ACTION_PROGRESS = "id.bootmaker.cok.CARD_PROGRESS";
    static final String ACTION_LOG = "id.bootmaker.cok.CARD_LOG";
    static final String ACTION_RESULT = "id.bootmaker.cok.CARD_RESULT";
}

/** Service actions (startService intents). */
class CardServiceActions {
    static final String MAKE = "id.bootmaker.cok.MAKE";
    static final String CANCEL = "id.bootmaker.cok.CANCEL";
}

/**
 * Foreground service that owns a card-making job. The Activity must
 * NEVER own a write: a multi-GB write outlives Activity recreation,
 * and Doze would suspend it without the foreground service + partial
 * wakelock.
 *
 * <p>Progress, log lines and the final result are broadcast (package
 * scoped) so the Activity can show them in its log panel without
 * binding.</p>
 */
public class CardService extends Service {
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL_ID = "card";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile CardEngine engine;
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private PowerManager.WakeLock wakeLock;
    private String lastStep = "";
    private int lastPct = -2;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Card write", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CardServiceActions.CANCEL.equals(intent.getAction())) {
            CardEngine eng = engine;
            if (eng == null) {
                stopSelf();
            } else {
                cancelRequested = true;
                eng.cancel();
                broadcastLog("Cancel requested.");
            }
            return START_NOT_STICKY;
        }
        if (running) {
            broadcastLog("A card job is already running.");
            return START_NOT_STICKY;
        }
        if (intent == null || !CardServiceActions.MAKE.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }
        running = true;
        cancelRequested = false;
        startForegroundCompat(buildNotification("Starting card job..."));
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bootmakar:card");
            wakeLock.acquire(10 * 60 * 60 * 1000L);
        }
        final Intent work = intent;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                CardEngine.CardOutcome outcome;
                try {
                    outcome = runJob(work);
                } catch (Exception e) {
                    outcome = CardEngine.CardOutcome.failed(
                            e.getMessage() != null ? e.getMessage() : e.toString());
                }
                broadcastResult(outcome);
                finishJob();
            }
        });
        return START_NOT_STICKY;
    }

    private CardEngine.CardOutcome runJob(Intent intent) throws IOException {
        CardEngine.CardOptions options = new CardEngine.CardOptions();
        options.directMode = intent.getBooleanExtra("direct_mode", true);
        options.deviceName = intent.getStringExtra("device_name");
        options.devicePath = intent.getStringExtra("device_path");
        options.confirmed = intent.getBooleanExtra("confirmed", false);
        String ubootString = intent.getStringExtra("uboot_uri");
        if (ubootString == null) {
            return CardEngine.CardOutcome.failed("No u-boot file given.");
        }
        options.ubootUri = Uri.parse(ubootString);
        String imageString = intent.getStringExtra("image_uri");
        if (imageString != null) {
            options.imageUri = Uri.parse(imageString);
        }
        options.imageSizeBytes = intent.getLongExtra("image_size", 0);
        String label = intent.getStringExtra("label");
        if (label != null) {
            options.label = label;
        }
        if (!options.directMode && options.imageUri == null) {
            return CardEngine.CardOutcome.failed("No image output given.");
        }
        CardEngine eng = new CardEngine(
                new CardEngine.LogListener() {
                    @Override
                    public void onLog(String line) {
                        broadcastLog(line);
                    }
                },
                new CardEngine.ProgressListener() {
                    @Override
                    public void onProgress(String step, int percent) {
                        broadcastProgress(step, percent);
                    }
                });
        engine = eng;
        return eng.run(options, getContentResolver());
    }

    // ------------------------------------------------------------ helpers

    private void finishJob() {
        running = false;
        engine = null;
        try {
            if (wakeLock != null) {
                wakeLock.release();
            }
        } catch (RuntimeException ignored) {
        }
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("BootMakar")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, buildNotification(text));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void broadcastProgress(String step, int percent) {
        Intent intent = new Intent(CardBroadcasts.ACTION_PROGRESS);
        intent.setPackage(getPackageName());
        intent.putExtra("step", step);
        intent.putExtra("percent", percent);
        sendBroadcast(intent);
        String text = percent >= 0 ? step + ": " + percent + "%" : step;
        if (!step.equals(lastStep) || percent != lastPct) {
            lastStep = step;
            lastPct = percent;
            updateNotification(text);
        }
    }

    private void broadcastLog(String line) {
        Intent intent = new Intent(CardBroadcasts.ACTION_LOG);
        intent.setPackage(getPackageName());
        intent.putExtra("line", line);
        sendBroadcast(intent);
    }

    private void broadcastResult(CardEngine.CardOutcome outcome) {
        Intent intent = new Intent(CardBroadcasts.ACTION_RESULT);
        intent.setPackage(getPackageName());
        intent.putExtra("outcome", outcome.kind);
        if (outcome.reason != null) {
            intent.putExtra("reason", outcome.reason);
        }
        sendBroadcast(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        try {
            if (wakeLock != null) {
                wakeLock.release();
            }
        } catch (RuntimeException ignored) {
        }
        super.onDestroy();
    }
}
