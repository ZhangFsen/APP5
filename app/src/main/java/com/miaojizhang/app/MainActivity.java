package com.miaojizhang.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import java.util.Calendar;

public class MainActivity extends Activity {
    public static final String CHANNEL_ID = "miaojizhang_reminder";
    private static final int REQ_NOTIFICATION = 1001;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        createNotificationChannel();
        requestNotificationPermissionIfNeeded();

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
        }

        webView.setBackgroundColor(Color.rgb(255, 248, 239));
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new AppWebChromeClient());
        webView.addJavascriptInterface(new AndroidBridge(this), "AndroidBridge");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "记账提醒",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("每日记账与家长提醒");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
        }
    }

    private class AppWebChromeClient extends WebChromeClient {
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("提示")
                    .setMessage(message)
                    .setPositiveButton("确定", (d, w) -> result.confirm())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("确认")
                    .setMessage(message)
                    .setPositiveButton("确定", (d, w) -> result.confirm())
                    .setNegativeButton("取消", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result) {
            EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false);
            input.setText(defaultValue == null ? "" : defaultValue);
            input.setSelection(input.getText().length());
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            input.setPadding(pad, pad / 2, pad, pad / 2);
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(message == null || message.length() == 0 ? "请输入" : message)
                    .setView(input)
                    .setPositiveButton("确定", (d, w) -> result.confirm(input.getText().toString()))
                    .setNegativeButton("取消", (d, w) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .show();
            return true;
        }
    }

    public static class AndroidBridge {
        private final Context context;

        AndroidBridge(Context context) {
            this.context = context.getApplicationContext();
        }

        @JavascriptInterface
        public void scheduleDailyReminder(String time) {
            if (time == null || !time.matches("^\\d{1,2}:\\d{2}$")) return;
            String[] parts = time.split(":");
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return;

            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            sp.edit().putBoolean("reminder_enabled", true).putString("reminder_time", String.format("%02d:%02d", hour, minute)).apply();
            schedule(context, hour, minute);
        }

        @JavascriptInterface
        public void cancelDailyReminder() {
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            sp.edit().putBoolean("reminder_enabled", false).apply();
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(reminderIntent(context));
        }

        @JavascriptInterface
        public void saveFeedback(String text) {
            if (text == null || text.trim().length() == 0) return;
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            String old = sp.getString("feedback", "");
            String line = System.currentTimeMillis() + "\t" + text.trim() + "\n";
            sp.edit().putString("feedback", old + line).apply();
        }

        @JavascriptInterface
        public String getSavedReminderTime() {
            SharedPreferences sp = context.getSharedPreferences("miaojizhang_native", Context.MODE_PRIVATE);
            return sp.getBoolean("reminder_enabled", false) ? sp.getString("reminder_time", "21:00") : "";
        }

        static void schedule(Context context, int hour, int minute) {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, hour);
            c.set(Calendar.MINUTE, minute);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_MONTH, 1);
            PendingIntent pi = reminderIntent(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), pi);
            }
        }

        static PendingIntent reminderIntent(Context context) {
            Intent intent = new Intent(context, ReminderReceiver.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getBroadcast(context, 20260429, intent, flags);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript("window.appBack ? window.appBack() : 'false';", value -> {
                if ("false".equals(value) || "null".equals(value)) {
                    MainActivity.super.onBackPressed();
                }
            });
        } else {
            super.onBackPressed();
        }
    }
}
