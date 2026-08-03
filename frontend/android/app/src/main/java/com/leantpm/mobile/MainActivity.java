package com.leantpm.mobile;

import android.content.Intent;
import android.os.Bundle;
import org.json.JSONObject;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    public static final String EXTRA_NOTIFICATION_ROUTE = "leantpm_notification_route";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SecureVaultPlugin.class);
        registerPlugin(FieldLocationPlugin.class);
        registerPlugin(LocalAlertsPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String route = intent.getStringExtra(EXTRA_NOTIFICATION_ROUTE);
        if (route == null || route.isBlank() || getBridge() == null) return;
        String javascript = "window.dispatchEvent(new CustomEvent('leantpm-notification-open',"
                + "{detail:{route:" + JSONObject.quote(route) + "}}));";
        getBridge().getWebView().post(() -> getBridge().getWebView()
                .evaluateJavascript(javascript, null));
        intent.removeExtra(EXTRA_NOTIFICATION_ROUTE);
    }
}
