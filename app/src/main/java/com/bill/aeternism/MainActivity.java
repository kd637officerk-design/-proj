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
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private static final int MEDIA_PERMISSION = 44;
  private WebView webView;

  @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
  @Override public void onCreate(Bundle state) {
    super.onCreate(state); setContentView(R.layout.activity_main);
    webView = findViewById(R.id.webview);
    WebSettings s = webView.getSettings();
    s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
    s.setAllowFileAccess(true); s.setAllowContentAccess(true);
    webView.setWebViewClient(new WebViewClient()); webView.setWebChromeClient(new WebChromeClient());
    webView.addJavascriptInterface(new MediaBridge(), "AndroidMedia");
    if (state == null) webView.loadUrl("file:///android_asset/index.html"); else webView.restoreState(state);
  }

  private void requestAndScan() {
    if (Build.VERSION.SDK_INT >= 33) {
      if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
        requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO}, MEDIA_PERMISSION); return;
      }
    } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, MEDIA_PERMISSION); return;
    }
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
  @Override public void onBackPressed(){ if(webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }
}
