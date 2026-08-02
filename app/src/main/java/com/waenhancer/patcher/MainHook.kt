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

        XposedBridge.log("$TAG: Starting")
        val cl = lpparam.classLoader

        // Hook App.onCreate to install ProHelper hooks after classes are loaded
        try {
            val appClass = cl.loadClass("com.waenhancer.App")
            XposedHelpers.findAndHookMethod(appClass, "onCreate",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        if (!proHelperHooked) {
                            hookProHelper(cl)
                            hookLicenseManager(cl)
                            hookDowngrade(cl)
                        }
                    }
                })
        } catch (_: Throwable) {
            hookProHelper(cl)
            hookLicenseManager(cl)
            hookDowngrade(cl)
        }
    }

    private fun findProHelper(cl: ClassLoader): Class<*>? {
        // Try direct name first
        try { return cl.loadClass("com.waenhancer.xposed.utils.ProHelper") } catch (_: Throwable) {}
        // Scan dex for obfuscated ProHelper
        return scanDexForProHelper(cl)
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

    private fun hookProHelper(cl: ClassLoader) {
        if (proHelperHooked) return
        proHelperHooked = true

        val proHelper = findProHelper(cl) ?: run {
            XposedBridge.log("$TAG: ProHelper not found")
            return
        }

        XposedBridge.log("$TAG: Hooking ProHelper: ${proHelper.name}")

        // Hook all no-arg boolean methods -> true
        // This covers: isProEnabled, isPillDesignProEnabled, isFilterItemsProEnabled
        // Does NOT affect isPluginInstalled(Context) which takes a Context param
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE && m.parameterTypes.isEmpty()) {
                try { XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true)) } catch (_: Throwable) {}
            }
        }

        // Hook all no-arg String methods -> ACTIVE / Pro Yearly
        // This covers: getProStatus -> "ACTIVE", getProPlanName -> "Pro Yearly"
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

        // String(String) methods -> passthrough (getHookStringSafely never returns null)
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

        // boolean(String) methods:
        // isProFeature -> true, isLimitedFreePreferenceEnabled/isLimitedFreeHookEnabled -> false
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                try {
                    if (m.name.contains("LimitedFree", true)) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(false))
                    } else {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    }
                } catch (_: Throwable) {}
            }
        }

        // updatePreferences(Context, PreferenceGroup) -> no-op
        for (m in proHelper.declaredMethods) {
            if (m.returnType == Void.TYPE && m.parameterTypes.size == 2) {
                val params = m.parameterTypes
                if (params[0].name.contains("Context") && params[1].name.contains("PreferenceGroup")) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    break
                }
            }
        }

        // Hook getPluginClassLoader to hook plugin classes after load
        try {
            for (m in proHelper.declaredMethods) {
                if (m.name == "getPluginClassLoader" && m.parameterTypes.size >= 1) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            val pluginLoader = param.result as? ClassLoader ?: return
                            hookPluginClasses(pluginLoader)
                        }
                    })
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

        XposedBridge.log("$TAG: ProHelper hooks installed")
    }

    private fun hookPluginClasses(pluginLoader: ClassLoader) {
        XposedBridge.log("$TAG: Hooking plugin classes")
        // ProConfig.getHookString -> passthrough
        try {
            val pc = pluginLoader.loadClass("com.waex.helper.utils.ProConfig")
            for (m in pc.declaredMethods) {
                if (m.name == "getHookString" && m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any {
                            return param.args[0] as String
                        }
                    })
                    break
                }
            }
        } catch (_: Throwable) {}

        // ProFeature.nl -> true
        try {
            val pf = pluginLoader.loadClass("com.waex.helper.ProFeature")
            val nl = pf.getDeclaredField("nl")
            nl.isAccessible = true
            nl.setBoolean(null, true)
        } catch (_: Throwable) {}

        // SecurityNative.getBaseUrl -> dummy
        try {
            val sn = pluginLoader.loadClass("com.waex.helper.utils.SecurityNative")
            for (m in sn.declaredMethods) {
                if (m.name == "getBaseUrl") {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant("https://waex.patcher.local"))
                    break
                }
            }
        } catch (_: Throwable) {}

        // ProConfig.loadConfig -> inject fake config
        try {
            val pc = pluginLoader.loadClass("com.waex.helper.utils.ProConfig")
            for (m in pc.declaredMethods) {
                if (m.returnType == Void.TYPE && m.parameterTypes.size == 1 &&
                    m.parameterTypes[0] == String::class.java) {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                            try {
                                val cf = pc.getDeclaredField("activeConfig")
                                cf.isAccessible = true
                                cf.set(null, buildFakeConfig())
                            } catch (_: Throwable) {}
                            return null
                        }
                    })
                    break
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookLicenseManager(cl: ClassLoader) {
        try {
            val lm = cl.loadClass("com.waenhancer.xposed.utils.LicenseManager")
            for (m in lm.declaredMethods) {
                if (m.name == "silentCheck" && m.parameterTypes.size == 2) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    break
                }
            }
        } catch (_: Throwable) {}
    }

    private fun hookDowngrade(cl: ClassLoader) {
        // Utils.handleSubscriptionDowngrade -> no-op
        try {
            val u = cl.loadClass("com.waenhancer.xposed.utils.Utils")
            for (m in u.declaredMethods) {
                if (m.name == "handleSubscriptionDowngrade" && m.parameterTypes.size == 2) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    break
                }
            }
        } catch (_: Throwable) {}

        // MainActivity.showDowngradeBottomSheet/showReversionBottomSheet -> no-op
        try {
            val ma = cl.loadClass("com.waenhancer.activities.MainActivity")
            for (m in ma.declaredMethods) {
                if (m.name == "showDowngradeBottomSheet" || m.name == "showReversionBottomSheet") {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                }
            }
        } catch (_: Throwable) {}
    }
}
