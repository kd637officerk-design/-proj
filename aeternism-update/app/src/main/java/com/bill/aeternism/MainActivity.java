package com.bill.aeternism;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.ContentValues;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private static final int MEDIA_PERMISSION = 44;
  private WebView webView;
  private static WeakReference<MainActivity> current = new WeakReference<>(null);

  /** Called by MediaService when a notification button is pressed. */
  static void sendAction(String action, long arg) {
    final MainActivity m = current.get();
    if (m == null || m.webView == null) return;
    final String js = "window.aeternismMediaAction&&window.aeternismMediaAction(" + JSONObject.quote(action) + "," + arg + ")";
    m.runOnUiThread(() -> m.webView.evaluateJavascript(js, null));
  }

  @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
  @Override public void onCreate(Bundle state) {
    super.onCreate(state); current = new WeakReference<>(this); setContentView(R.layout.activity_main);
    webView = findViewById(R.id.webview);
    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(true); s.setAllowContentAccess(true);
    webView.setWebViewClient(new WebViewClient()); webView.setWebChromeClient(new WebChromeClient());
    webView.addJavascriptInterface(new MediaBridge(), "AndroidMedia");
    if (state == null) webView.loadUrl("file:///android_asset/index.html"); else webView.restoreState(state);
  }

  private void requestAndScan() {
    ArrayList<String> need = new ArrayList<>();
    if (Build.VERSION.SDK_INT >= 33) {
      if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_AUDIO);
      if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.READ_MEDIA_VIDEO);
      if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
    } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
    }
    if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), MEDIA_PERMISSION); return; }
    deliverMedia();
  }

  @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
    super.onRequestPermissionsResult(code,p,r);
    if(code==MEDIA_PERMISSION) deliverMedia();
  }

  private void deliverMedia() {
    new Thread(() -> {
      JSONArray all = new JSONArray(); queryStore(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "audio", all); queryStore(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video", all);
      String js = "window.aeternismLoadMedia(" + JSONObject.quote(all.toString()) + ")";
      runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }).start();
  }

  private void queryStore(Uri base, String kind, JSONArray out) {
    String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DURATION, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM};
    try(Cursor c=getContentResolver().query(base, cols, null, null, MediaStore.MediaColumns.DATE_ADDED+" DESC")) {
      if(c==null) return;
      while(c.moveToNext()) try {
        long id=c.getLong(0); JSONObject o=new JSONObject(); o.put("id",kind+id); o.put("name",c.getString(1)); o.put("mime",c.getString(2)); o.put("addedAt",c.getLong(3)*1000L); o.put("folder",c.getString(4)); o.put("duration",c.getLong(5)); o.put("artist",c.getString(6)); o.put("album",c.getString(7)); o.put("kind",kind); o.put("url",ContentUris.withAppendedId(base,id).toString()); out.put(o);
      } catch(Exception ignored) {}
    } catch(Exception ignored) {}
  }

  private void message(String text) { runOnUiThread(() -> webView.evaluateJavascript("window.aeternismNativeMessage("+JSONObject.quote(text)+")",null)); }

  public class MediaBridge {
    @JavascriptInterface public void updateNotification(String title, String artist, String album, String uri, boolean playing, double durMs, double posMs, double speed){
      Intent i = new Intent(MainActivity.this, MediaService.class).setAction(MediaService.A_UPDATE);
      i.putExtra("title", title); i.putExtra("artist", artist); i.putExtra("album", album); i.putExtra("uri", uri);
      i.putExtra("playing", playing); i.putExtra("dur", durMs); i.putExtra("pos", posMs); i.putExtra("speed", speed);
      MediaService.deliver(MainActivity.this, i);
    }
    @JavascriptInterface public void stopNotification(){ MediaService.stopFromJs(); }
    @JavascriptInterface public void scan(){ runOnUiThread(() -> requestAndScan()); }
    @JavascriptInterface public void rename(String raw, String name){
      try { ContentValues v=new ContentValues(); v.put(MediaStore.MediaColumns.DISPLAY_NAME,name); int n=getContentResolver().update(Uri.parse(raw),v,null,null); message(n>0?"Renamed media":"Android did not allow rename"); } catch(Exception e){ message("Rename needs Android file approval"); }
    }
    @JavascriptInterface public void deleteMedia(String raw){
      try { int n=getContentResolver().delete(Uri.parse(raw),null,null); message(n>0?"Deleted media":"Android did not allow deletion"); } catch(Exception e){ message("Delete needs Android file approval"); }
    }
    @JavascriptInterface public void share(String raw, String mime){
      runOnUiThread(() -> { Intent i=new Intent(Intent.ACTION_SEND); i.setType(mime); i.putExtra(Intent.EXTRA_STREAM,Uri.parse(raw)); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(i,"Share with AETERNISM")); });
    }
  }

  @Override protected void onSaveInstanceState(Bundle out){ webView.saveState(out); super.onSaveInstanceState(out); }
  @Override public void onBackPressed(){ if(webView.canGoBack()) webView.goBack(); else moveTaskToBack(true); }
  @Override protected void onDestroy(){ if (current.get() == this) current = new WeakReference<>(null); if (isFinishing()) MediaService.stopFromJs(); super.onDestroy(); }
}
