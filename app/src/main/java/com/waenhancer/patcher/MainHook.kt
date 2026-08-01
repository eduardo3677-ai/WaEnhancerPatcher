package com.waenhancer.patcher

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import org.luckypray.dexkit.DexKitBridge

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

        private var dexKit: DexKitBridge? = null

        private fun initDexKit(apkPath: String): Boolean {
            return try {
                dexKit = DexKitBridge.createDexKit(apkPath)
                true
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: DexKit init failed: $t")
                false
            }
        }

        private fun findClassByStrings(cl: ClassLoader, vararg strings: String): Class<*>? {
            val b = dexKit ?: return null
            return try {
                val results = b.findClasses {
                    matcher = matcher {
                        strings.forEach { usingStrings(it) }
                    }
                }
                val name = results.firstOrNull()?.name ?: return null
                XposedBridge.log("$TAG: Found class via DexKit: $name")
                cl.loadClass(name)
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: findClass failed: $t")
                null
            }
        }

        private fun findMethodByStrings(cl: ClassLoader, vararg strings: String): java.lang.reflect.Method? {
            val b = dexKit ?: return null
            return try {
                val results = b.findMethods {
                    matcher = matcher {
                        strings.forEach { usingStrings(it) }
                    }
                }
                val data = results.firstOrNull() ?: return null
                XposedBridge.log("$TAG: Found method via DexKit: ${data.className}.${data.methodName}")
                val cls = cl.loadClass(data.className)
                for (m in cls.declaredMethods) {
                    if (m.name == data.methodName) return m
                }
                null
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: findMethod failed: $t")
                null
            }
        }

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
        val apkPath = getApkPath(cl)
        if (apkPath != null && initDexKit(apkPath)) {
            XposedBridge.log("$TAG: DexKit initialized")
        }

        hookProHelper(cl)
        hookSharedPrefs()
        hookTextView()
        hookLicenseManager(cl)
    }

    private fun getApkPath(cl: ClassLoader): String? {
        return try {
            cl.loadClass("com.waenhancer.App")
                .protectionDomain
                .codeSource
                .location
                .path
        } catch (t: Throwable) {
            try {
                val pm = android.app.AppGlobals.getInitialApplication().packageManager
                pm.getApplicationInfo(PKG, 0).sourceDir
            } catch (t2: Throwable) { null }
        }
    }

    private fun hookProHelper(cl: ClassLoader) {
        val proHelper = findClassByStrings(cl, "is_pro_verified", "encrypted_config", "Disabled by Server")
            ?: try {
                cl.loadClass("com.waenhancer.xposed.utils.ProHelper")
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: ProHelper not found: $t")
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

        // Hook getHookStringSafely(String) -> passthrough
        for (m in proHelper.declaredMethods) {
            if (m.returnType == String::class.java &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                m.returnType != java.lang.Boolean.TYPE) {
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

        // Hook isProFeature(String) -> true
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
        val method = findMethodByStrings(cl, "silentCheck")
        if (method != null) {
            try {
                XposedBridge.hookMethod(method, XC_MethodReplacement.DO_NOTHING)
                XposedBridge.log("$TAG: LicenseManager.silentCheck -> no-op")
            } catch (_: Throwable) {}
        }
    }
}
