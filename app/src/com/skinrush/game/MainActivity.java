package com.skinrush.game;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.IOException;
import java.io.InputStream;

/**
 * SKIN RUSH — обёртка вокруг одного HTML-файла.
 *
 * Игра лежит в assets/index.html и отдаётся WebView по адресу
 * https://appassets.androidplatform.net/assets/index.html — это «виртуальный» https-хост.
 * Зачем не file://: с file:// браузер считает origin пустым (null), и тогда
 * localStorage может теряться между запусками, а запросы к Firebase идут как
 * cross-origin с мусорным источником. С https-хостом всё ведёт себя как на сайте.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";

    private WebView web;
    private FrameLayout root;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#07090D"));
        w.setNavigationBarColor(Color.parseColor("#07090D"));
        if (Build.VERSION.SDK_INT >= 21) {
            w.setStatusBarColor(Color.parseColor("#07090D"));
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#07090D"));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#07090D"));
        web.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // localStorage: сейв игры живёт тут
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(false);            // у игры свой viewport, масштаб не ломаем
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 21) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            s.setSafeBrowsingEnabled(false);
        }

        /* 4.4.9: без WebChromeClient WebView НЕ показывает диалоги страницы:
           alert() молчит, а confirm() мгновенно возвращает false, будто человек
           нажал «Отмена». Из-за этого в приложении не работал импорт кода
           сохранения, сброс прогресса, выход из аккаунта, покупка темы и очистка
           ставки — кнопка нажималась и не происходило ничего. */
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("ОК", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("ОК", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setCancelable(false)
                        .setPositiveButton("ОК", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                result.confirm(input.getText().toString());
                            }
                        })
                        .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { result.cancel(); }
                        })
                        .show();
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u != null && HOST.equals(u.getHost())) {
                    String path = u.getPath();
                    if (path != null && path.startsWith("/assets/")) {
                        return asset(path.substring("/assets/".length()));
                    }
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u == null) return false;
                if (HOST.equals(u.getHost())) return false;   // свои файлы — грузим внутри
                // внешние ссылки (если когда-нибудь появятся) — в системный браузер
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        root.addView(web);
        setContentView(root);
        web.loadUrl(START_URL);
    }

    private WebResourceResponse asset(String name) {
        try {
            InputStream is = getAssets().open(name);
            String mime = "text/html";
            if (name.endsWith(".js")) mime = "application/javascript";
            else if (name.endsWith(".json")) mime = "application/json";
            else if (name.endsWith(".css")) mime = "text/css";
            else if (name.endsWith(".png")) mime = "image/png";
            else if (name.endsWith(".svg")) mime = "image/svg+xml";
            return new WebResourceResponse(mime, "UTF-8", 200, "OK",
                    new java.util.HashMap<String, String>() {{
                        put("Cache-Control", "no-cache");
                        put("Access-Control-Allow-Origin", "*");
                    }}, is);
        } catch (IOException e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                    new java.util.HashMap<String, String>(), null);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }
}
