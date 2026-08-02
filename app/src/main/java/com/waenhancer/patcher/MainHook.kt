package com.waenhancer.patcher

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "WAE-Patcher"
        private const val PKG = "com.waenhancer"

        private val HOOK_KEYS = setOf(
            "message_bomber", "delete_message_file", "pro_status_splitter",
            "customize_status_control_class", "always_typing_global",
            "send_audio_as_voice_status", "file_size_spoofer",
            "filter_group_members_messages", "unlock_premium_customization",
            "recover_deleted_media", "license_verify", "filter_items",
            "voice_status_validator_str", "voice_status_prefix"
        )

        @Suppress("UNCHECKED_CAST")
        private fun buildFakeConfig(): JSONObject = JSONObject().apply {
            put("hooks", JSONObject().apply {
                HOOK_KEYS.forEach { put(it, it) }
            })
            put("pill_design_pro_enabled", true)
            put("whitelist_channels", "beta")
        }
    }

    private var proHelperHooked = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG) return

        XposedBridge.log("$TAG: Starting hooks")
        val cl = lpparam.classLoader

        // Defer SharedPreferencesImpl hooks to after Application is created
        // Hooking too early causes "Failed to write LSPosed marker" error
        hookAppOnCreate(cl)
    }

    private fun hookAppOnCreate(cl: ClassLoader) {
        try {
            val appClass = cl.loadClass("com.waenhancer.App")
            XposedHelpers.findAndHookMethod(appClass, "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        XposedBridge.log("$TAG: App.onCreate BEFORE")
                        hookSharedPrefsImpl()
                        hookProHelperSafe(cl)
                    }
                })
        } catch (t: Throwable) {
            hookSharedPrefsImpl()
            hookProHelperSafe(cl)
        }
    }

    private fun hookProHelperSafe(cl: ClassLoader) {
        if (proHelperHooked) return
        proHelperHooked = true

        val proHelper = try {
            cl.loadClass("com.waenhancer.xposed.utils.ProHelper")
        } catch (t: Throwable) {
            scanDexForProHelper(cl)
        }

        if (proHelper == null) return
        hookProHelper(proHelper, cl)
    }

    private fun scanDexForProHelper(cl: ClassLoader): Class<*>? {
        try {
            var cls: Class<*>? = cl.javaClass
            var pathList: Any? = null
            while (cls != null && pathList == null) {
                try {
                    val f = cls!!.getDeclaredField("pathList")
                    f.isAccessible = true
                    pathList = f.get(cl)
                } catch (_: Throwable) { cls = cls!!.superclass }
            }
            if (pathList == null) return null
            val f = pathList!!.javaClass.getDeclaredField("dexElements")
            f.isAccessible = true
            val dexElements = f.get(pathList) as Array<*>
            for (element in dexElements) {
                if (element == null) continue
                val dexFile = getFieldValue(element.javaClass, element, "dexFile") ?: continue
                val entriesMethod = dexFile.javaClass.getMethod("entries")
                @Suppress("UNCHECKED_CAST")
                val entries = entriesMethod.invoke(dexFile) as java.util.Enumeration<String>
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.startsWith("android.") || entry.startsWith("java.") ||
                        entry.startsWith("kotlin.") || entry.startsWith("androidx.") ||
                        entry.startsWith("com.google.") || entry.startsWith("de.robv.") ||
                        entry.startsWith("org.")) continue
                    try {
                        val candidate = cl.loadClass(entry)
                        if (isProHelper(candidate)) return candidate
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun isProHelper(cls: Class<*>): Boolean {
        try {
            var hasJson = false
            var hasBoolStr = false
            var hasStrStr = false
            for (m in cls.declaredMethods) {
                if (m.returnType == JSONObject::class.java && m.parameterTypes.isEmpty()) hasJson = true
                if (m.returnType == java.lang.Boolean.TYPE &&
                    m.parameterTypes.contentEquals(arrayOf(String::class.java))) hasBoolStr = true
                if (m.returnType == String::class.java &&
                    m.parameterTypes.contentEquals(arrayOf(String::class.java))) hasStrStr = true
            }
            return hasJson && hasBoolStr && hasStrStr
        } catch (_: Throwable) { return false }
    }

    private fun getFieldValue(cls: Class<*>, obj: Any, fieldName: String): Any? {
        return try {
            val f = cls.getDeclaredField(fieldName)
            f.isAccessible = true
            f.get(obj)
        } catch (_: Throwable) { null }
    }

    private fun hookPluginClasses(pluginLoader: ClassLoader) {
        // Hook ProConfig.getHookString -> return hookKey (never null)
        try {
            val proConfigClass = pluginLoader.loadClass("com.waex.helper.utils.ProConfig")
            for (m in proConfigClass.declaredMethods) {
                if (m.name == "getHookString" && m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any {
                            return param.args[0] as String
                        }
                    })
                    XposedBridge.log("$TAG: ProConfig.getHookString -> passthrough")
                    break
                }
            }
        } catch (_: Throwable) {}

        // Hook ProFeature.nl -> true (native library loaded)
        try {
            val proFeatureClass = pluginLoader.loadClass("com.waex.helper.ProFeature")
            val nlField = proFeatureClass.getDeclaredField("nl")
            nlField.isAccessible = true
            nlField.setBoolean(null, true)
            XposedBridge.log("$TAG: ProFeature.nl -> true")
        } catch (_: Throwable) {}

        // Hook SecurityNative.getBaseUrl -> return a dummy URL
        try {
            val secNativeClass = pluginLoader.loadClass("com.waex.helper.utils.SecurityNative")
            for (m in secNativeClass.declaredMethods) {
                if (m.name == "getBaseUrl") {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant("https://waex.patcher.local"))
                    XposedBridge.log("$TAG: SecurityNative.getBaseUrl -> patched")
                    break
                }
            }
        } catch (_: Throwable) {}

        // Hook ProConfig.loadConfig -> inject fake config
        try {
            val proConfigClass = pluginLoader.loadClass("com.waex.helper.utils.ProConfig")
            for (m in proConfigClass.declaredMethods) {
                if (m.returnType == Void.TYPE && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == String::class.java) {
                    // loadConfig(String) -> inject our config instead
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                            // Set activeConfig directly
                            try {
                                val configField = proConfigClass.getDeclaredField("activeConfig")
                                configField.isAccessible = true
                                configField.set(null, buildFakeConfig())
                                XposedBridge.log("$TAG: ProConfig.activeConfig -> fake config injected")
                            } catch (_: Throwable) {}
                            return null
                        }
                    })
                    break
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookProHelper(proHelper: Class<*>, cl: ClassLoader) {
        XposedBridge.log("$TAG: Hooking ProHelper")

        // boolean() -> true (isProEnabled, isPillDesignProEnabled, isFilterItemsProEnabled)
        // DON'T hook isPluginInstalled/isPluginPackageInstalled — let real values pass
        // so the app can detect if plugin is missing and offer to download it
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE && m.parameterTypes.isEmpty()) {
                try { XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true)) } catch (_: Throwable) {}
            }
        }

        // String() -> ACTIVE / Pro Yearly
        for (m in proHelper.declaredMethods) {
            if (m.returnType == String::class.java && m.parameterTypes.isEmpty()) {
                try {
                    val result = if (m.name.contains("Status", true)) "ACTIVE" else "Pro Yearly"
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(result))
                } catch (_: Throwable) {}
            }
        }

        // setForceFree(boolean) -> no-op
        for (m in proHelper.declaredMethods) {
            if (m.returnType == Void.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE))) {
                try { XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING) } catch (_: Throwable) {}
            }
        }

        // getDecryptedConfig() -> fake config
        for (m in proHelper.declaredMethods) {
            if (m.returnType == JSONObject::class.java && m.parameterTypes.isEmpty()) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any {
                            return buildFakeConfig()
                        }
                    })
                } catch (_: Throwable) {}
            }
        }

        // String(String) -> passthrough (getHookStringSafely never returns null)
        for (m in proHelper.declaredMethods) {
            if (m.returnType == String::class.java &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any {
                            return param.args[0] as String
                        }
                    })
                } catch (_: Throwable) {}
            }
        }

        // boolean(String) -> differentiate isProFeature(true) vs isLimitedFree(false)
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                try {
                    if (m.name.contains("LimitedFree") || m.name.contains("limitedFree")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false))
                    } else {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    }
                } catch (_: Throwable) {}
            }
        }

        // updatePreferences -> no-op (prevents all lock/disable/badge logic)
        for (m in proHelper.declaredMethods) {
            if (m.returnType == Void.TYPE && m.parameterTypes.size == 2) {
                val params = m.parameterTypes
                if (params[0].name.contains("Context") && params[1].name.contains("PreferenceGroup")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    break
                }
            }
        }

        // Utils.handleSubscriptionDowngrade -> no-op
        try {
            val utilsClass = cl.loadClass("com.waenhancer.xposed.utils.Utils")
            for (m in utilsClass.declaredMethods) {
                if (m.name == "handleSubscriptionDowngrade" && m.parameterTypes.size == 2) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    break
                }
            }
        } catch (_: Throwable) {}

        // MainActivity.showDowngradeBottomSheet/showReversionBottomSheet -> no-op
        try {
            val maClass = cl.loadClass("com.waenhancer.activities.MainActivity")
            for (m in maClass.declaredMethods) {
                if (m.name == "showDowngradeBottomSheet" || m.name == "showReversionBottomSheet") {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                }
            }
        } catch (_: Throwable) {}

        // LicenseManager.silentCheck -> no-op
        try {
            val lmClass = cl.loadClass("com.waenhancer.xposed.utils.LicenseManager")
            for (m in lmClass.declaredMethods) {
                if (m.name == "silentCheck" && m.parameterTypes.size == 2) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    break
                }
            }
        } catch (_: Throwable) {}

        // Hook FeatureLoader to block companion plugin loading
        // The plugin (com.waex.helper) has its own license checks that call setForceFree(true)
        // and can override our hooks. By blocking getPluginClassLoader, we prevent the plugin
        // from loading its own Pro logic.
        try {
            val flClass = cl.loadClass("com.waenhancer.xposed.core.FeatureLoader")
            for (m in flClass.declaredMethods) {
                if (m.name == "plugins" && m.parameterTypes.isEmpty()) {
                    // Hook plugins() to run after it, then re-apply our hooks
                    // in case the plugin overrode them
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            XposedBridge.log("$TAG: FeatureLoader.plugins() completed, re-applying hooks")
                            // Re-hook in case plugin overrode anything
                            try {
                                val proHelper2 = cl.loadClass("com.waenhancer.xposed.utils.ProHelper")
                                for (m2 in proHelper2.declaredMethods) {
                                    if (m2.returnType == Void.TYPE &&
                                        m2.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE))) {
                                        try { XposedBridge.hookMethod(m2, XC_MethodReplacement.DO_NOTHING) } catch (_: Throwable) {}
                                    }
                                }
                            } catch (_: Throwable) {}
                        }
                    })
                    XposedBridge.log("$TAG: FeatureLoader.plugins() hooked")
                    break
                }
            }
        } catch (_: Throwable) {}

        // Hook ProHelper.getPluginClassLoader -> allow plugin to load
        // But hook ProConfig and ProFeature inside the plugin to bypass its checks
        try {
            for (m in proHelper.declaredMethods) {
                if (m.name == "getPluginClassLoader" && m.parameterTypes.size >= 1) {
                    // Hook AFTER to get the classloader, then hook plugin classes
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val pluginLoader = param.result as? ClassLoader ?: return
                            XposedBridge.log("$TAG: Plugin classloader obtained, hooking plugin classes")
                            hookPluginClasses(pluginLoader)
                        }
                    })
                    XposedBridge.log("$TAG: getPluginClassLoader hooked (monitor)")
                    break
                }
            }
        } catch (_: Throwable) {}

        // Hook HomeFragment.updateProUI -> no-op (we set the chip text ourselves)
        try {
            val hfClass = cl.loadClass("com.waenhancer.ui.fragments.HomeFragment")
            for (m in hfClass.declaredMethods) {
                if (m.name == "updateProUI" && m.parameterTypes.isEmpty()) {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                            try {
                                val bindingField = param.thisObject.javaClass.getDeclaredField("binding")
                                bindingField.isAccessible = true
                                val binding = bindingField.get(param.thisObject) ?: return null
                                val chipField = binding.javaClass.getDeclaredField("proStatusChip")
                                chipField.isAccessible = true
                                val chip = chipField.get(binding) ?: return null
                                val setTextMethod = chip.javaClass.getMethod("setText", CharSequence::class.java)
                                setTextMethod.invoke(chip, "Pro Yearly")
                            } catch (_: Throwable) {}
                            return null
                        }
                    })
                    XposedBridge.log("$TAG: HomeFragment.updateProUI -> Pro Yearly")
                    break
                }
            }
        } catch (_: Throwable) {}

        // ProPreferenceCategory init -> no-op
        try {
            val ppcClass = cl.loadClass("com.waenhancer.preference.ProPreferenceCategory")
            for (m in ppcClass.declaredMethods) {
                if (m.returnType == Void.TYPE && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0].name.contains("Context")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    break
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookSharedPrefsImpl() {
        // Hook SharedPreferencesImpl (main app process reads through this)
        val spImpl = try {
            Class.forName("android.app.SharedPreferencesImpl")
        } catch (_: Throwable) { null }

        spImpl?.let { sp ->
            try {
                XposedHelpers.findAndHookMethod(sp, "getBoolean",
                    String::class.java, java.lang.Boolean.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            if (param.args[0] == "is_pro_verified") param.result = true
                        }
                    })
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(sp, "getString",
                    String::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            when (param.args[0] as String) {
                                "license_key" -> param.result = "WAEX-PATCH-UNLK-0001"
                                "plan_name" -> param.result = "Pro Yearly"
                                "expires_at" -> param.result = "1893456000000"
                                "tg_username" -> param.result = "patcher"
                                "whitelist_channels" -> param.result = "beta,stable"
                                "encrypted_config" -> param.result = null
                                "pending_downgrade_reason_msg" -> param.result = null
                            }
                        }
                    })
            } catch (_: Throwable) {}

            try {
                XposedHelpers.findAndHookMethod(sp, "getLong",
                    String::class.java, java.lang.Long.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            if (param.args[0] == "expires_at") param.result = 1893456000000L
                        }
                    })
            } catch (_: Throwable) {}

            val editorImpl = try {
                Class.forName("android.app.SharedPreferencesImpl\$EditorImpl")
            } catch (_: Throwable) { null }

            editorImpl?.let { ei ->
                try {
                    XposedHelpers.findAndHookMethod(ei, "putBoolean",
                        String::class.java, java.lang.Boolean.TYPE,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                                if (param.args[0] == "is_pro_verified" && !(param.args[1] as Boolean)) {
                                    param.result = param.thisObject
                                }
                            }
                        })
                } catch (_: Throwable) {}

                try {
                    XposedHelpers.findAndHookMethod(ei, "remove",
                        String::class.java,
                        object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val key = param.args[0] as String
                            if (key == "encrypted_config" || key == "license_key" ||
                                key == "is_pro_verified" || key == "plan_name" ||
                                key == "expires_at" || key == "whitelist_channels" ||
                                key == "tg_username") {
                                param.result = param.thisObject
                            }
                        }
                    })
                } catch (_: Throwable) {}
            }
        }

        // Hook XSharedPreferences — ProHelper.getPrefs() returns Utils.xprefs which is XSharedPreferences
        // This is what getProStatus()/getProPlanName() actually read from
        try {
            val xspClass = Class.forName("de.robv.android.xposed.XSharedPreferences")
            XposedBridge.log("$TAG: Hooking XSharedPreferences")

            try {
                XposedHelpers.findAndHookMethod(xspClass, "getBoolean",
                    String::class.java, java.lang.Boolean.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            if (param.args[0] == "is_pro_verified") param.result = true
                        }
                    })
                XposedBridge.log("$TAG: XSharedPreferences.getBoolean hooked")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: XSP getBoolean: $t")
            }

            try {
                XposedHelpers.findAndHookMethod(xspClass, "getString",
                    String::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val key = param.args[0] as String
                            when (key) {
                                "license_key" -> param.result = "WAEX-PATCH-UNLK-0001"
                                "plan_name" -> param.result = "Pro Yearly"
                                "expires_at" -> param.result = "1893456000000"
                                "tg_username" -> param.result = "patcher"
                                "whitelist_channels" -> param.result = "beta,stable"
                                "encrypted_config" -> param.result = null
                                "pending_downgrade_reason_msg" -> param.result = null
                            }
                        }
                    })
                XposedBridge.log("$TAG: XSharedPreferences.getString hooked")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: XSP getString: $t")
            }

            try {
                XposedHelpers.findAndHookMethod(xspClass, "getLong",
                    String::class.java, java.lang.Long.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            if (param.args[0] == "expires_at") param.result = 1893456000000L
                        }
                    })
                XposedBridge.log("$TAG: XSharedPreferences.getLong hooked")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: XSP getLong: $t")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: XSharedPreferences class not found: $t")
        }
    }
}
