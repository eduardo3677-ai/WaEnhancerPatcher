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
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG) return

        XposedBridge.log("$TAG: Starting hooks")
        val cl = lpparam.classLoader

        hookProHelper(cl)
        hookSharedPrefs()
        hookTextView()
        hookLicenseManager(cl)
    }

    private fun hookProHelper(cl: ClassLoader) {
        val proHelper = try {
            cl.loadClass("com.waenhancer.xposed.utils.ProHelper")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: ProHelper not found by name, searching...")
            searchProHelper(cl)
        }

        if (proHelper == null) {
            XposedBridge.log("$TAG: ProHelper not found, framework hooks only")
            return
        }

        XposedBridge.log("$TAG: ProHelper: ${proHelper.name}")

        // Hook all no-arg boolean methods -> true
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE && m.parameterTypes.isEmpty()) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    XposedBridge.log("$TAG: ${m.name}() -> true")
                } catch (_: Throwable) {}
            }
        }

        // Hook all no-arg String methods
        for (m in proHelper.declaredMethods) {
            if (m.returnType == String::class.java && m.parameterTypes.isEmpty()) {
                try {
                    val result = if (m.name.contains("Status") || m.name.contains("status")) "ACTIVE" else "Pro Active"
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(result))
                } catch (_: Throwable) {}
            }
        }

        // Hook setForceFree(boolean) -> no-op
        for (m in proHelper.declaredMethods) {
            if (m.returnType == Void.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE))) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                } catch (_: Throwable) {}
            }
        }

        // Hook getDecryptedConfig() -> fake config
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

        // Hook String(String) methods -> passthrough (getHookStringSafely)
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

        // Hook boolean(String) methods -> true (isProFeature)
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    XposedBridge.log("$TAG: ${m.name}(String) -> true")
                } catch (_: Throwable) {}
            }
        }
    }

    private fun searchProHelper(cl: ClassLoader): Class<*>? {
        // Try common obfuscated package names
        val packages = arrayOf("com.waenhancer.xposed.utils", "Z", "z", "a", "b")
        for (pkg in packages) {
            try {
                // Scan dex entries via reflection on DexFile
                val dexPathList = getFieldObject(cl.javaClass.superclass, cl, "pathList")
                    ?: getFieldObject(cl.javaClass, cl, "pathList")
                    ?: continue
                val dexElements = getFieldObject(dexPathList.javaClass, dexPathList, "dexElements") as? Array<*>
                    ?: continue
                for (element in dexElements) {
                    val dexFile = getFieldObject(element!!.javaClass, element, "dexFile") ?: continue
                    val entries = dexFile.javaClass.getMethod("entries").invoke(dexFile) as java.util.Enumeration<String>
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (!entry.startsWith("$pkg.")) continue
                        try {
                            val cls = cl.loadClass(entry)
                            if (isProHelper(cls)) return cls
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun isProHelper(cls: Class<*>): Boolean {
        try {
            for (m in cls.declaredMethods) {
                val name = m.name
                if (name == "isProEnabled" || name == "getProStatus" || name == "getProPlanName" ||
                    name == "isProFeature" || name == "setForceFree" || name == "getDecryptedConfig") {
                    return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun getFieldObject(cls: Class<*>, obj: Any, fieldName: String): Any? {
        return try {
            val field = cls.getDeclaredField(fieldName)
            field.isAccessible = true
            field.get(obj)
        } catch (_: Throwable) {
            try {
                val field = obj.javaClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.get(obj)
            } catch (_: Throwable) { null }
        }
    }

    private fun hookSharedPrefs() {
        XposedBridge.hookAllMethods(android.content.SharedPreferences::class.java, "getBoolean",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val key = param.args[0] as String
                    if (key == "is_pro_verified") param.result = true
                }
            })

        XposedBridge.hookAllMethods(android.content.SharedPreferences::class.java, "getString",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val key = param.args[0] as String
                    when (key) {
                        "license_key" -> param.result = "PATCHER-UNLOCK-0000"
                        "plan_name" -> param.result = "Pro Active"
                        "expires_at" -> param.result = (System.currentTimeMillis() + 315360000000L).toString()
                    }
                }
            })

        XposedBridge.log("$TAG: SharedPreferences hooks installed")
    }

    private fun hookTextView() {
        XposedHelpers.findAndHookMethod(android.widget.TextView::class.java, "setText",
            CharSequence::class.java, android.widget.TextView.BufferType::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val text = param.args[0] as? CharSequence ?: return
                    val s = text.toString()
                    var modified = s
                    modified = modified.replace("Disabled by Server", "").trim()
                    modified = modified.replace("Plugin Required", "Pro Active")
                    modified = modified.replace("[Pro — plugin missing]", "[Pro]")
                    modified = modified.replace("Activate Pro First", "")
                    modified = modified.replace("Tap here to verify license key & unlock", "Pro Active")
                    if (modified != s) param.args[0] = modified
                }
            })
        XposedBridge.log("$TAG: TextView hook installed")
    }

    private fun hookLicenseManager(cl: ClassLoader) {
        try {
            val cls = cl.loadClass("com.waenhancer.xposed.utils.LicenseManager")
            for (m in cls.declaredMethods) {
                if (m.name == "silentCheck" && m.parameterTypes.size == 2) {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    XposedBridge.log("$TAG: LicenseManager.silentCheck -> no-op")
                    return
                }
            }
        } catch (_: Throwable) {
            XposedBridge.log("$TAG: LicenseManager not found by name")
        }
    }
}
