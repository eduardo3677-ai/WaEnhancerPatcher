package com.waenhancer.patcher

import de.robv.android.xposed.XposedBridge
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge

object ProConfig {
    val hookKeys = setOf(
        "message_bomber", "delete_message_file", "pro_status_splitter",
        "customize_status_control_class", "always_typing_global",
        "send_audio_as_voice_status", "file_size_spoofer",
        "filter_group_members_messages", "unlock_premium_customization",
        "recover_deleted_media", "license_verify", "filter_items",
        "voice_status_validator_str", "voice_status_prefix"
    )

    fun build(): JSONObject = JSONObject().apply {
        put("hooks", JSONObject().apply {
            hookKeys.forEach { put(it, it) }
        })
        put("pill_design_pro_enabled", true)
    }
}

object DexKitFinder {
    private var bridge: DexKitBridge? = null

    fun init(apkPath: String): Boolean {
        return try {
            bridge = DexKitBridge.createDexKit(apkPath)
            true
        } catch (t: Throwable) {
            XposedBridge.log("WAE-Patcher: DexKit init failed: $t")
            false
        }
    }

    fun findClassByStrings(vararg strings: String): String? {
        val b = bridge ?: return null
        return try {
            val results = b.findClasses {
                matcher = matcher {
                    strings.forEach { usingStrings(it) }
                }
            }
            results.firstOrNull()?.name
        } catch (t: Throwable) {
            XposedBridge.log("WAE-Patcher: findClass failed: $t")
            null
        }
    }

    fun findMethodByStrings(vararg strings: String): Pair<String, String>? {
        val b = bridge ?: return null
        return try {
            val results = b.findMethods {
                matcher = matcher {
                    strings.forEach { usingStrings(it) }
                }
            }
            results.firstOrNull()?.let { it.className to it.methodName }
        } catch (t: Throwable) {
            XposedBridge.log("WAE-Patcher: findMethod failed: $t")
            null
        }
    }
}
