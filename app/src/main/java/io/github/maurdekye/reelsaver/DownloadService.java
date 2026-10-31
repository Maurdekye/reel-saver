package io.github.maurdekye.reelsaver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads one reel at a time and saves it to Movies/Reel Saver in the gallery. */
public class DownloadService extends Service {

    private static final String TAG = "ReelSaver";
    private static final String CHANNEL_ID = "downloads";
    private static final int FG_NOTIFICATION_ID = 1;
    private static int nextNotificationId = 100;

    static final String EXTRA_TEXT = "shared_text";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    static void start(Context context, String sharedText) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.putExtra(EXTRA_TEXT, sharedText);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String text = intent != null ? intent.getStringExtra(EXTRA_TEXT) : null;
        Notification initial = buildProgressNotification("Starting download…", -1);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(FG_NOTIFICATION_ID, initial,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_NOTIFICATION_ID, initial);
        }
        executor.execute(() -> {
            try {
                download(text);
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void download(String sharedText) {
        try {
            status("Reading the shared link…");
            ReelExtractor.ReelRef ref = ReelExtractor.parseSharedText(sharedText);
            Log.i(TAG, "Reel " + ref.type + "/" + ref.code);

            status("Finding the video…");
            String videoUrl = ReelExtractor.resolveVideoUrl(ref);
            Log.i(TAG, "Video URL found for " + ref.code);

            status("Downloading…");
            Uri saved = saveVideo(videoUrl, "reel_" + ref.code + ".mp4");
            String name = displayName(saved);
            status("Saved “" + name + "” to Movies/Reel Saver.");
            toast("Reel saved: " + name);
            notifySaved(saved, name);
        } catch (ReelExtractor.ReelException e) {
            fail(e.getMessage());
        } catch (IOException e) {
            Log.e(TAG, "Network failure", e);
            fail("Download failed: couldn't reach Instagram. Check your connection and try again.");
        } catch (Exception e) {
            Log.e(TAG, "Unexpected failure", e);
            fail("Download failed: " + e.getMessage());
        }
    }

    private Uri saveVideo(String videoUrl, String fileName) throws IOException {
        HttpURLConnection conn = ReelExtractor.open(videoUrl, ReelExtractor.CRAWLER_UA);
        conn.setRequestProperty("Referer", "https://www.instagram.com/");
        try {
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Video server returned HTTP " + status);
            }
            String contentType = conn.getContentType();
            long total = conn.getContentLengthLong();

            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Reel Saver");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values);
            if (uri == null) throw new IOException("Couldn't create the video file.");

            boolean checkedHeader = false;
            long copied = 0;
            try (InputStream in = conn.getInputStream();
                 OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("Couldn't open the video file for writing.");
                byte[] buffer = new byte[64 * 1024];
                int read;
                int lastPercent = -1;
                while ((read = in.read(buffer)) != -1) {
                    if (!checkedHeader) {
                        checkedHeader = true;
                        if (!looksLikeMp4(buffer, read)
                                && (contentType == null || !contentType.startsWith("video"))) {
                            throw new IOException("What came back wasn't a video.");
                        }
                    }
                    out.write(buffer, 0, read);
                    copied += read;
                    if (total > 0) {
                        int percent = (int) (copied * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            updateProgress("Downloading… " + percent + "%", percent);
                        }
                    }
                }
            } catch (IOException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            if (copied == 0) {
                resolver.delete(uri, null, null);
                throw new IOException("The download was empty.");
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, publish, null, null);
            Log.i(TAG, "Saved " + copied + " bytes to " + uri);
            return uri;
        } finally {
            conn.disconnect();
        }
    }

    private static boolean looksLikeMp4(byte[] buffer, int length) {
        return length >= 8
                && buffer[4] == 'f' && buffer[5] == 't' && buffer[6] == 'y' && buffer[7] == 'p';
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return "the reel";
    }

    private void status(String message) {
        StatusBus.post(message);
        updateProgress(message, -1);
    }

    private void updateProgress(String text, int percent) {
        getSystemService(NotificationManager.class)
                .notify(FG_NOTIFICATION_ID, buildProgressNotification(text, percent));
    }

    private Notification buildProgressNotification(String text, int percent) {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Reel Saver")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (percent >= 0) {
            builder.setProgress(100, percent, false);
        } else {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private void notifySaved(Uri videoUri, String name) {
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(videoUri, "video/mp4");
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent pending = PendingIntent.getActivity(this, 1, view,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Reel saved")
                .setContentText(name + " — in Movies/Reel Saver")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(nextNotificationId++, notification);
    }

    private void fail(String message) {
        Log.w(TAG, message);
        StatusBus.post(message);
        toast(message);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Reel Saver")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(nextNotificationId++, notification);
    }

    private void toast(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
