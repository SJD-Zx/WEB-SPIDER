package com.webslinger.game;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
  private WebView w;
  private GameServer server;

  public class Bridge {
    @JavascriptInterface
    public String startServer() {
      try {
        if (server == null) server = new GameServer(MainActivity.this, 3000);
        server.start();
        return GameServer.ips();
      } catch (Exception e) {
        return "ERR " + e.getMessage();
      }
    }

    @JavascriptInterface
    public void stopServer() {
      if (server != null) server.stop();
      server = null;
    }
  }

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    w = new WebView(this);
    setContentView(w);
    WebSettings s = w.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setAllowFileAccess(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    w.addJavascriptInterface(new Bridge(), "Android");
    w.setWebChromeClient(new WebChromeClient());
    w.setWebViewClient(new WebViewClient());
    hideBars();
    w.loadUrl("file:///android_asset/index.html");
  }

  private void hideBars() {
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  @Override public void onWindowFocusChanged(boolean f) {
    super.onWindowFocusChanged(f);
    if (f) hideBars();
  }

  @Override protected void onDestroy() {
    if (server != null) server.stop();
    super.onDestroy();
  }
}
