package com.ghostpanter.scrcpy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

// Foreground service whose only job is to keep the app process alive
// while the user is mirroring. Mirror starts it on entry and stops it
// in onDestroy; the running foreground service (with its notification)
// is what keeps the OOM killer away, so the activity can survive being
// briefly backgrounded (rotation, IME, swipe-to-home) without the
// scrcpy server tearing down.
//
// Type is FOREGROUND_SERVICE_MEDIA_PLAYBACK: the app continuously plays
// remote audio/video for as long as the user keeps a mirror session open.
//
// No binder API - the service does not own Session. Mirror owns it.
public final class Sessions extends Service {

    private static final String CHANNEL_ID = "scrcpy-android-session";
    private static final int    NOTIF_ID   = 1;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("sessions: onCreate");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Mirror passes its target so the notification can lead back to
        // the session it describes. Restarting the service with a new
        // target just rebuilds the notification.
        String host = intent == null ? null : intent.getStringExtra(Mirror.EXTRA_HOST);
        int    port = intent == null ? -1   : intent.getIntExtra(Mirror.EXTRA_PORT, -1);

        ensureChannel();
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.scrcpy_title))
                .setContentText(getString(R.string.notif_session_active))
                .setContentIntent(reopenIntent(host, port))
                .setOngoing(true)
                .build();
        // 3-arg startForeground(type) is API 29+; required for typed FGS on
        // targetSdk 34+. API 28 uses the 2-arg overload.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i("sessions: onDestroy");
        super.onDestroy();
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.scrcpy_title),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_session_active));
        nm.createNotificationChannel(ch);
    }

    // Tapping an ongoing "mirroring active" notification must return to
    // the mirror. It used to point at Main with FLAG_ACTIVITY_CLEAR_TOP,
    // which finished Mirror on the way - the notification destroyed the
    // session it was advertising. Mirror is singleTask, so a plain
    // NEW_TASK launch brings the existing instance forward, and carrying
    // the target means a launch after the activity died still works.
    private PendingIntent reopenIntent(String host, int port) {
        Intent i = new Intent(this, Mirror.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (host != null) {
            i.putExtra(Mirror.EXTRA_HOST, host);
            i.putExtra(Mirror.EXTRA_PORT, port);
        }
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
