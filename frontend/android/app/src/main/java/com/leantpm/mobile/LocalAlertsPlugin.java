package com.leantpm.mobile;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
        name = "LocalAlerts",
        permissions = @Permission(
                alias = "notifications",
                strings = Manifest.permission.POST_NOTIFICATIONS
        )
)
public class LocalAlertsPlugin extends Plugin {
    private static final String CHANNEL_ID = "leantpm_task_alerts";

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || getPermissionState("notifications") == PermissionState.GRANTED) {
            resolvePermission(call, true);
            return;
        }
        requestPermissionForAlias("notifications", call, "notificationPermissionCallback");
    }

    @PermissionCallback
    private void notificationPermissionCallback(PluginCall call) {
        resolvePermission(call, getPermissionState("notifications") == PermissionState.GRANTED);
    }

    @PluginMethod
    public void show(PluginCall call) {
        String title = call.getString("title");
        String body = call.getString("body");
        String route = call.getString("route", "/mobile/messages");
        int id = call.getInt("id", (int) (System.currentTimeMillis() % Integer.MAX_VALUE));
        if (title == null || title.isBlank() || body == null || body.isBlank()) {
            call.reject("通知标题和内容不能为空", "NOTIFICATION_CONTENT_REQUIRED");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && getPermissionState("notifications") != PermissionState.GRANTED) {
            call.reject("通知权限未授予", "NOTIFICATION_PERMISSION_DENIED");
            return;
        }
        NotificationManager manager = (NotificationManager) getContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            call.reject("系统通知服务不可用", "NOTIFICATION_SERVICE_UNAVAILABLE");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "LeanTPM 任务提醒", NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("点检、维保、异常和维修工单提醒");
            manager.createNotificationChannel(channel);
        }
        Intent intent = new Intent(getContext(), MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_NOTIFICATION_ROUTE, route);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                getContext(), id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        NotificationCompat.Builder notification = new NotificationCompat.Builder(getContext(), CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
        manager.notify(id, notification.build());
        call.resolve();
    }

    @PluginMethod
    public void getLaunchRoute(PluginCall call) {
        Intent intent = getActivity().getIntent();
        String route = intent == null ? null : intent.getStringExtra(MainActivity.EXTRA_NOTIFICATION_ROUTE);
        if (intent != null) intent.removeExtra(MainActivity.EXTRA_NOTIFICATION_ROUTE);
        JSObject result = new JSObject();
        if (route != null && !route.isBlank()) result.put("route", route);
        call.resolve(result);
    }

    private void resolvePermission(PluginCall call, boolean granted) {
        JSObject result = new JSObject();
        result.put("granted", granted);
        call.resolve(result);
    }
}
