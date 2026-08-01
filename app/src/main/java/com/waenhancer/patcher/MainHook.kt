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
        }
    }

    private var proHelperHooked = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG) return

        XposedBridge.log("$TAG: Starting hooks")
        val cl = lpparam.classLoader

        hookSharedPrefsImpl()
        hookAppOnCreate(cl)
    }

    private fun hookAppOnCreate(cl: ClassLoader) {
        try {
            val appClass = cl.loadClass("com.waenhancer.App")
            XposedHelpers.findAndHookMethod(appClass, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        XposedBridge.log("$TAG: App.onCreate fired, hooking ProHelper")
                        hookProHelperSafe(cl)
                    }
                })
            XposedBridge.log("$TAG: App.onCreate hook installed")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: App.onCreate hook failed: $t")
            hookProHelperSafe(cl)
        }
    }

    private fun hookProHelperSafe(cl: ClassLoader) {
        if (proHelperHooked) return
        proHelperHooked = true

        val proHelper = try {
            cl.loadClass("com.waenhancer.xposed.utils.ProHelper")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: ProHelper not found by name, scanning dex...")
            scanDexForProHelper(cl)
        }

        if (proHelper == null) {
            XposedBridge.log("$TAG: ProHelper not found")
            return
        }

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
                } catch (_: Throwable) {
                    cls = cls!!.superclass
                }
            }
            if (pathList == null) return null

            val dexElementsField = pathList!!.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(pathList) as Array<*>
            if (dexElements.isEmpty()) return null

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
                        if (isProHelper(candidate)) {
                            XposedBridge.log("$TAG: Found ProHelper: $entry")
                            return candidate
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Dex scan failed: $t")
        }
        return null
    }

    private fun isProHelper(cls: Class<*>): Boolean {
        try {
            val methods = cls.declaredMethods
            var hasJsonReturn = false
            var hasBoolStringMethod = false
            var hasStringStringMethod = false

            for (m in methods) {
                if (m.returnType == JSONObject::class.java && m.parameterTypes.isEmpty()) {
                    hasJsonReturn = true
                }
                if (m.returnType == java.lang.Boolean.TYPE &&
                    m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                    hasBoolStringMethod = true
                }
                if (m.returnType == String::class.java &&
                    m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                    hasStringStringMethod = true
                }
            }

            return hasJsonReturn && hasBoolStringMethod && hasStringStringMethod
        } catch (_: Throwable) {
            return false
        }
    }

    private fun getFieldValue(cls: Class<*>, obj: Any, fieldName: String): Any? {
        return try {
            val f = cls.getDeclaredField(fieldName)
            f.isAccessible = true
            f.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookProHelper(proHelper: Class<*>, cl: ClassLoader) {
        XposedBridge.log("$TAG: Hooking ProHelper: ${proHelper.name}")

        // isProEnabled() -> true
        // isPillDesignProEnabled() -> true
        // isFilterItemsProEnabled() -> true
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE && m.parameterTypes.isEmpty()) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    XposedBridge.log("$TAG: ${m.name}() -> true")
                } catch (_: Throwable) {}
            }
        }

        // getProStatus() -> "ACTIVE"
        // getProPlanName() -> "Pro Active"
        for (m in proHelper.declaredMethods) {
            if (m.returnType == String::class.java && m.parameterTypes.isEmpty()) {
                try {
                    val result = if (m.name.contains("Status", true)) "ACTIVE" else "Pro Active"
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(result))
                    XposedBridge.log("$TAG: ${m.name}() -> $result")
                } catch (_: Throwable) {}
            }
        }

        // setForceFree(boolean) -> no-op (prevents license revocation)
        for (m in proHelper.declaredMethods) {
            if (m.returnType == Void.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE))) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    XposedBridge.log("$TAG: ${m.name}(boolean) -> no-op")
                } catch (_: Throwable) {}
            }
        }

        // getDecryptedConfig() -> fake config with all hooks
        for (m in proHelper.declaredMethods) {
            if (m.returnType == JSONObject::class.java && m.parameterTypes.isEmpty()) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any {
                            return buildFakeConfig()
                        }
                    })
                    XposedBridge.log("$TAG: getDecryptedConfig -> fake")
                } catch (_: Throwable) {}
            }
        }

        // getHookStringSafely(String) -> passthrough (never returns null)
        // This prevents "Disabled by Server" from being set
        for (m in proHelper.declaredMethods) {
            if (m.returnType == String::class.java &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any {
                            return param.args[0] as String
                        }
                    })
                    XposedBridge.log("$TAG: ${m.name}(String) -> passthrough")
                } catch (_: Throwable) {}
            }
        }

        // isProFeature(String) -> true (all features are Pro)
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    XposedBridge.log("$TAG: ${m.name}(String) -> true")
                } catch (_: Throwable) {}
            }
        }

        // isLimitedFreePreferenceEnabled(String) -> false
        // This prevents "Limited Free" badges from being added
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                // Already hooked to true above. We need to differentiate:
                // isProFeature -> true, isLimitedFreePreferenceEnabled -> false
                // Since both have the same signature, we hook by name
            }
        }

        // Try to hook isLimitedFreePreferenceEnabled specifically
        try {
            val m = proHelper.getDeclaredMethod("isLimitedFreePreferenceEnabled", String::class.java)
            XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false))
            XposedBridge.log("$TAG: isLimitedFreePreferenceEnabled -> false")
        } catch (_: Throwable) {}

        // Try to hook updatePreferences to be a no-op
        // This prevents all the "Disabled by Server" / "Plugin Required" logic
        try {
            for (m in proHelper.declaredMethods) {
                if (m.returnType == Void.TYPE && m.parameterTypes.size == 2) {
                    val params = m.parameterTypes
                    if (params[0].name.contains("Context") && params[1].name.contains("PreferenceGroup")) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                        XposedBridge.log("$TAG: ${m.name}(Context, PreferenceGroup) -> no-op (prevents all locking)")
                        break
                    }
                }
            }
        } catch (_: Throwable) {}

        // Hook LicenseManager.silentCheck -> no-op
        try {
            val lmClass = cl.loadClass("com.waenhancer.xposed.utils.LicenseManager")
            for (m in lmClass.declaredMethods) {
                if (m.name == "silentCheck" && m.parameterTypes.size == 2) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    XposedBridge.log("$TAG: LicenseManager.silentCheck -> no-op")
                    break
                }
            }
        } catch (_: Throwable) {}

        // Hook ProSwitchPreference.onClick -> bypass license redirect
        try {
            val pspClass = cl.loadClass("com.waenhancer.preference.ProSwitchPreference")
            for (m in pspClass.declaredMethods) {
                if (m.name == "onClick") {
                    XposedBridge.hookMethod(m, new XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                            // Call parent onClick instead of license redirect
                            return null
                        }
                    })
                    XposedBridge.log("$TAG: ProSwitchPreference.onClick -> bypassed")
                    break
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookSharedPrefsImpl() {
        // Hook SharedPreferencesImpl (concrete class, not interface)
        val spImpl = try {
            Class.forName("android.app.SharedPreferencesImpl")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: SharedPreferencesImpl not found: $t")
            return
        }

        // getBoolean: force is_pro_verified=true
        try {
            XposedHelpers.findAndHookMethod(spImpl, "getBoolean",
                String::class.java, java.lang.Boolean.TYPE,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val key = param.args[0] as String
                        if (key == "is_pro_verified") {
                            param.result = true
                        }
                    }
                })
            XposedBridge.log("$TAG: SharedPreferencesImpl.getBoolean hooked")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: getBoolean hook failed: $t")
        }

        // getString: inject license data
        try {
            XposedHelpers.findAndHookMethod(spImpl, "getString",
                String::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val key = param.args[0] as String
                        when (key) {
                            "license_key" -> param.result = "PATCHER-UNLOCK-0000"
                            "plan_name" -> param.result = "Pro Active"
                            "expires_at" -> param.result = (System.currentTimeMillis() + 315360000000L).toString()
                            "tg_username" -> param.result = "patcher"
                        }
                    }
                })
            XposedBridge.log("$TAG: SharedPreferencesImpl.getString hooked")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: getString hook failed: $t")
        }

        // EditorImpl.putBoolean: prevent is_pro_verified from being set to false
        val editorImpl = try {
            Class.forName("android.app.SharedPreferencesImpl\$EditorImpl")
        } catch (_: Throwable) { null }

        if (editorImpl != null) {
            try {
                XposedHelpers.findAndHookMethod(editorImpl, "putBoolean",
                    String::class.java, java.lang.Boolean.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val key = param.args[0] as String
                            val value = param.args[1] as Boolean
                            if (key == "is_pro_verified" && !value) {
                                param.result = param.thisObject
                            }
                        }
                    })
                XposedBridge.log("$TAG: EditorImpl.putBoolean hooked")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: putBoolean hook failed: $t")
            }
        }
    }
}
