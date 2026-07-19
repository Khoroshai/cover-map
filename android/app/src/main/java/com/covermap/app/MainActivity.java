package com.covermap.app;

import android.content.Intent;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(GoogleDrivePlugin.class);
        super.onCreate(savedInstanceState);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.setAcceptThirdPartyCookies(new WebView(this), true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == GoogleDrivePlugin.REQUEST_AUTHORIZE) {
            GoogleDrivePlugin plugin = GoogleDrivePlugin.getInstance();
            if (plugin != null) {
                plugin.handleActivityResult(requestCode, resultCode, data);
            }
        }
    }
}
