package com.leantpm.mobile;

import android.Manifest;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(
        name = "FieldLocation",
        permissions = @Permission(
                alias = "location",
                strings = {
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }
        )
)
public class FieldLocationPlugin extends Plugin {
    @PluginMethod
    public void getCurrentPosition(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            requestPermissionForAlias("location", call, "locationPermissionCallback");
            return;
        }
        locate(call);
    }

    @PermissionCallback
    private void locationPermissionCallback(PluginCall call) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            call.reject("定位权限未授予", "LOCATION_PERMISSION_DENIED");
            return;
        }
        locate(call);
    }

    private void locate(PluginCall call) {
        LocationManager manager = (LocationManager) getContext()
                .getSystemService(android.content.Context.LOCATION_SERVICE);
        if (manager == null) {
            call.reject("定位服务不可用", "LOCATION_SERVICE_UNAVAILABLE");
            return;
        }
        try {
            List<String> enabled = manager.getProviders(true);
            String provider = enabled.contains(LocationManager.GPS_PROVIDER)
                    ? LocationManager.GPS_PROVIDER
                    : enabled.contains(LocationManager.NETWORK_PROVIDER)
                    ? LocationManager.NETWORK_PROVIDER
                    : null;
            if (provider == null) {
                call.reject("请开启系统定位服务", "LOCATION_PROVIDER_DISABLED");
                return;
            }
            Location recent = enabled.stream()
                    .map(name -> {
                        try { return manager.getLastKnownLocation(name); }
                        catch (SecurityException ignored) { return null; }
                    })
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.comparingLong(Location::getTime))
                    .orElse(null);
            if (recent != null && System.currentTimeMillis() - recent.getTime() < 30_000) {
                resolve(call, recent);
                return;
            }
            requestFreshLocation(call, manager, provider, recent);
        } catch (SecurityException exception) {
            call.reject("无法读取定位", "LOCATION_SECURITY_ERROR", exception);
        }
    }

    private void requestFreshLocation(
            PluginCall call,
            LocationManager manager,
            String provider,
            Location fallback
    ) {
        long timeout = Math.max(3_000, Math.min(20_000, call.getInt("timeoutMs", 12_000)));
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicBoolean completed = new AtomicBoolean(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            CancellationSignal signal = new CancellationSignal();
            Runnable timeoutAction = () -> {
                if (!completed.compareAndSet(false, true)) return;
                signal.cancel();
                if (fallback != null) resolve(call, fallback);
                else call.reject("定位超时，请到开阔位置重试", "LOCATION_TIMEOUT");
            };
            handler.postDelayed(timeoutAction, timeout);
            manager.getCurrentLocation(provider, signal, getContext().getMainExecutor(), location -> {
                if (!completed.compareAndSet(false, true)) return;
                handler.removeCallbacks(timeoutAction);
                if (location == null) call.reject("未获取到定位", "LOCATION_EMPTY");
                else resolve(call, location);
            });
            return;
        }
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!completed.compareAndSet(false, true)) return;
                manager.removeUpdates(this);
                resolve(call, location);
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
        };
        Runnable timeoutAction = () -> {
            if (!completed.compareAndSet(false, true)) return;
            manager.removeUpdates(listener);
            if (fallback != null) resolve(call, fallback);
            else call.reject("定位超时，请到开阔位置重试", "LOCATION_TIMEOUT");
        };
        handler.postDelayed(timeoutAction, timeout);
        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper());
    }

    private void resolve(PluginCall call, Location location) {
        JSObject result = new JSObject();
        result.put("latitude", location.getLatitude());
        result.put("longitude", location.getLongitude());
        result.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : 0);
        result.put("capturedAt", location.getTime());
        result.put("provider", location.getProvider());
        call.resolve(result);
    }
}
