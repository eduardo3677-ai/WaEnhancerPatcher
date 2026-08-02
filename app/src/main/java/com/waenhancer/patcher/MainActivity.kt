package com.waenhancer.patcher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("patcher_prefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(64, 64, 64, 64)

        val title = TextView(this)
        title.text = "WaEnhancer Patcher"
        title.textSize = 22f
        layout.addView(title)

        val desc = TextView(this)
        desc.text = "\nActiva o desactiva los hooks de Pro en WaEnhancer X.\n\n" +
            "ON: Todas las features Pro desbloqueadas\n" +
            "OFF: WaEnhancer funciona normalmente sin modificaciones\n"
        desc.textSize = 14f
        desc.setPadding(0, 16, 0, 24)
        layout.addView(desc)

        val toggle = Switch(this)
        toggle.text = "Patcher activo"
        toggle.textSize = 16f
        toggle.isChecked = prefs.getBoolean("patcher_enabled", true)
        toggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("patcher_enabled", isChecked).apply()
        }
        layout.addView(toggle)

        setContentView(layout)
    }
}
