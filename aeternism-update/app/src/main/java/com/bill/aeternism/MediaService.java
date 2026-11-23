package com.bill.aeternism;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.MediaMetadataRetriever;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

public class MediaService extends Service {
  static final String CHANNEL = "aeternism_playback";
  static final int NOTIFICATION_ID = 71;
  static final String A_UPDATE = "com.bill.aeternism.UPDATE";
  static final String A_PLAY = "com.bill.aeternism.PLAY";
  static final String A_PAUSE = "com.bill.aeternism.PAUSE";
  static final String A_NEXT = "com.bill.aeternism.NEXT";
  static final String A_PREV = "com.bill.aeternism.PREV";
  static final String A_STOP = "com.bill.aeternism.STOP";

  static volatile MediaService instance;

  private final Handler main = new Handler(Looper.getMainLooper());
  private NotificationManager nm;
  private MediaSession session;
  private String title = "AETERNISM", artist = "", album = "", uri = "";
  private boolean playing, started;
  private long durationMs, positionMs, positionAt;
  private float speed = 1f;
  private Bitmap art;

  /** Called from the JS bridge (any thread). */
  static void deliver(Context c, Intent i) {
    final MediaService s = instance;
    if (s != null) { final Intent copy = i; s.main.post(() -> s.handle(copy)); return; }
    try {
      if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    } catch (Exception ignored) {}
  }

  static void stopFromJs() {
    final MediaService s = instance;
    if (s != null) s.main.post(() -> s.shutdown());
  }

  @Override public void onCreate() {
    super.onCreate();
    instance = this;
    nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) {
      NotificationChannel ch = new NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW);
      ch.setShowBadge(false);
      ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
      nm.createNotificationChannel(ch);
    }
    session = new MediaSession(this, "AETERNISM");
    session.setCallback(new MediaSession.Callback() {
      @Override public void onPlay() { MainActivity.sendAction("play", 0); setPlaying(true); }
      @Override public void onPause() { MainActivity.sendAction("pause", 0); setPlaying(false); }
      @Override public void onSkipToNext() { MainActivity.sendAction("next", 0); }
      @Override public void onSkipToPrevious() { MainActivity.sendAction("prev", 0); }
      @Override public void onSeekTo(long pos) { positionMs = pos; positionAt = SystemClock.elapsedRealtime(); MainActivity.sendAction("seek", pos); refresh(); }
      @Override public void onStop() { MainActivity.sendAction("stop", 0); shutdown(); }
    });
    session.setActive(true);
  }

  @Override public int onStartCommand(Intent i, int flags, int startId) {
    if (i != null) handle(i);
    return START_NOT_STICKY;
  }

  private long currentPos() {
    long p = positionMs;
    if (playing) p += (long) ((SystemClock.elapsedRealtime() - positionAt) * speed);
    if (durationMs > 0 && p > durationMs) p = durationMs;
    return Math.max(0, p);
  }

  private void setPlaying(boolean p) {
    positionMs = currentPos(); positionAt = SystemClock.elapsedRealtime();
    playing = p; refresh();
  }

  private void handle(Intent i) {
    String a = i.getAction();
    if (a == null) return;
    switch (a) {
      case A_UPDATE: {
        title = nz(i.getStringExtra("title"), "AETERNISM");
        artist = nz(i.getStringExtra("artist"), "");
        album = nz(i.getStringExtra("album"), "");
        String newUri = nz(i.getStringExtra("uri"), "");
        playing = i.getBooleanExtra("playing", false);
        durationMs = (long) i.getDoubleExtra("dur", 0);
        positionMs = (long) i.getDoubleExtra("pos", 0);
        positionAt = SystemClock.elapsedRealtime();
        speed = (float) i.getDoubleExtra("speed", 1.0);
        if (speed <= 0) speed = 1f;
        if (!newUri.equals(uri) || art == null) { uri = newUri; art = null; loadArt(newUri); }
        refresh();
        break;
      }
      case A_PLAY: MainActivity.sendAction("play", 0); setPlaying(true); break;
      case A_PAUSE: MainActivity.sendAction("pause", 0); setPlaying(false); break;
      case A_NEXT: MainActivity.sendAction("next", 0); break;
      case A_PREV: MainActivity.sendAction("prev", 0); break;
      case A_STOP: MainActivity.sendAction("stop", 0); shutdown(); break;
      default: break;
    }
  }

  private static String nz(String s, String d) { return s == null ? d : s; }

  private void loadArt(final String u) {
    new Thread(() -> {
      Bitmap bm = null;
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      try {
        if (!u.isEmpty()) {
          r.setDataSource(this, Uri.parse(u));
          byte[] d = r.getEmbeddedPicture();
          if (d != null) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(d, 0, d.length, o);
            int s = 1;
            while (o.outWidth / s > 1024) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            bm = BitmapFactory.decodeByteArray(d, 0, d.length, o2);
          }
        }
      } catch (Exception ignored) {
      } finally {
        try { r.release(); } catch (Exception ignored) {}
      }
      final Bitmap result = bm != null ? bm : BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
      main.post(() -> { if (u.equals(uri) && instance == this) { art = result; refresh(); } });
    }).start();
  }

  private void refresh() {
    if (session == null) return;
    MediaMetadata.Builder mb = new MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs > 0 ? durationMs : -1);
    if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
    session.setMetadata(mb.build());
    long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;
    session.setPlaybackState(new PlaybackState.Builder()
        .setActions(actions)
        .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, currentPos(), playing ? speed : 0f, SystemClock.elapsedRealtime())
        .build());
    Notification n = buildNotification();
    try {
      if (!started) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION_ID, n);
        started = true;
      } else {
        nm.notify(NOTIFICATION_ID, n);
      }
    } catch (Exception e) {
      try { nm.notify(NOTIFICATION_ID, n); } catch (Exception ignored) {}
    }
  }

  private Notification buildNotification() {
    Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
    Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
    PendingIntent openPi = PendingIntent.getActivity(this, 0, open != null ? open : new Intent(), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    b.setSmallIcon(R.drawable.ic_stat_music)
        .setContentTitle(title)
        .setContentText(artist.isEmpty() ? album : artist)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setOnlyAlertOnce(true)
        .setShowWhen(false)
        .setOngoing(playing)
        .setContentIntent(openPi)
        .addAction(action(android.R.drawable.ic_media_previous, "Previous", A_PREV, 1))
        .addAction(playing ? action(android.R.drawable.ic_media_pause, "Pause", A_PAUSE, 2) : action(android.R.drawable.ic_media_play, "Play", A_PLAY, 2))
        .addAction(action(android.R.drawable.ic_media_next, "Next", A_NEXT, 3))
        .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Close", A_STOP, 4))
        .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
    if (art != null) b.setLargeIcon(art);
    return b.build();
  }

  private Notification.Action action(int icon, String label, String act, int code) {
    Intent i = new Intent(this, MediaService.class).setAction(act);
    PendingIntent pi = PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, pi).build();
  }

  private void shutdown() {
    if (instance == this) instance = null;
    started = false;
    stopForeground(true);
    stopSelf();
  }

  @Override public void onTaskRemoved(Intent rootIntent) { shutdown(); super.onTaskRemoved(rootIntent); }

  @Override public void onDestroy() {
    if (instance == this) instance = null;
    if (session != null) { session.setActive(false); session.release(); session = null; }
    super.onDestroy();
  }

  @Override public IBinder onBind(Intent intent) { return null; }
}
