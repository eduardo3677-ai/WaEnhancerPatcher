package com.waenhancer.patcher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("WaEnhancer Patcher");
        title.setTextSize(20);
        layout.addView(title);

        TextView desc = new TextView(this);
        desc.setText("\nThis is an LSPosed module that unlocks all Pro features in WaEnhancer X.\n\n" +
                "Features:\n" +
                "- Bypasses license verification\n" +
                "- Forces Pro status to ACTIVE\n" +
                "- Bypasses plugin companion check\n" +
                "- Injects fake Pro config with all hooks\n" +
                "- Prevents license revocation\n\n" +
                "Install WaEnhancer X, enable this module in LSPosed, select WaEnhancer as scope, reboot.\n\n" +
                "All Pro toggles will be unlocked and functional.");
        desc.setTextSize(14);
        desc.setPadding(0, 24, 0, 0);
        layout.addView(desc);

        setContentView(layout);
    }
}
