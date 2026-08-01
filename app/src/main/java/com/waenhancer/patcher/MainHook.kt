package com.waenhancer.patcher

import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.core.YukiMember
import com.highcapable.yukihookapi.hook.factory.config
import com.highcapable.yukihookapi.hook.core.api.compat.YukiHookHelper
import com.highcapable.yukihookapi.hook.param.HookParam
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.lang.reflect.Method

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "WAE-Patcher"
        private const val PKG = "com.waenhancer"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != PKG) return

        XposedBridge.log("$TAG: Starting hooks")

        val cl = lpparam.classLoader
        val apkPath = getApkPath(cl)
        if (apkPath != null && DexKitFinder.init(apkPath)) {
            XposedBridge.log("$TAG: DexKit initialized at $apkPath")
        } else {
            XposedBridge.log("$TAG: DexKit failed, falling back to direct hooks")
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
                val info = pm.getApplicationInfo(PKG, 0)
                info.sourceDir
            } catch (t2: Throwable) { null }
        }
    }

    private fun hookProHelper(cl: ClassLoader) {
        // Try to find ProHelper via DexKit using its unique strings
        val className = DexKitFinder.findClassByStrings(
            "is_pro_verified", "encrypted_config", "Disabled by Server"
        )

        val proHelper = try {
            if (className != null) {
                XposedBridge.log("$TAG: ProHelper found via DexKit: $className")
                cl.loadClass(className)
            } else {
                XposedBridge.log("$TAG: ProHelper not found via DexKit, trying direct name")
                cl.loadClass("com.waenhancer.xposed.utils.ProHelper")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: ProHelper class not found: $t")
            return
        }

        // Hook all no-arg boolean methods -> true (isProEnabled, isPillDesignProEnabled, etc)
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE && m.parameterTypes.isEmpty()) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    XposedBridge.log("$TAG: Hooked ${m.name}() -> true")
                } catch (_: Throwable) {}
            }
        }

        // Hook all no-arg String methods that could be getProStatus/getProPlanName
        for (m in proHelper.declaredMethods) {
            if (m.returnType == String::class.java && m.parameterTypes.isEmpty()) {
                try {
                    val name = m.name
                    val result = if (name.contains("Status") || name.contains("status")) "ACTIVE"
                                 else "Pro Active"
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(result))
                    XposedBridge.log("$TAG: Hooked ${m.name}() -> $result")
                } catch (_: Throwable) {}
            }
        }

        // Hook setForceFree(boolean) -> no-op
        for (m in proHelper.declaredMethods) {
            if (m.returnType == Void.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(java.lang.Boolean.TYPE)) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                    XposedBridge.log("$TAG: Hooked ${m.name}(boolean) -> no-op")
                } catch (_: Throwable) {}
            }
        }

        // Hook getDecryptedConfig() -> fake config
        for (m in proHelper.declaredMethods) {
            if (m.returnType == JSONObject::class.java && m.parameterTypes.isEmpty()) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any {
                            return ProConfig.build()
                        }
                    })
                    XposedBridge.log("$TAG: Hooked ${m.name}() -> fake config")
                } catch (_: Throwable) {}
            }
        }

        // Hook getHookStringSafely(String) -> return key
        for (m in proHelper.declaredMethods) {
            if (m.returnType == String::class.java &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                m.name != "isProFeature" &&
                !m.name.contains("plan")) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodReplacement() {
                        override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any {
                            return param.args[0] as String
                        }
                    })
                    XposedBridge.log("$TAG: Hooked ${m.name}(String) -> passthrough")
                } catch (_: Throwable) {}
            }
        }

        // Hook isProFeature(String) -> true
        for (m in proHelper.declaredMethods) {
            if (m.returnType == java.lang.Boolean.TYPE &&
                m.parameterTypes.contentEquals(arrayOf(String::class.java))) {
                try {
                    XposedBridge.hookMethod(m, XC_MethodReplacement.returnConstant(true))
                    XposedBridge.log("$TAG: Hooked ${m.name}(String) -> true")
                } catch (_: Throwable) {}
            }
        }
    }

    private fun hookSharedPrefs() {
        // Force is_pro_verified=true
        XposedBridge.hookAllMethods(android.content.SharedPreferences::class.java, "getBoolean",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val key = param.args[0] as String
                    if (key == "is_pro_verified") param.result = true
                }
            })

        // Inject license data
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

        // Prevent is_pro_verified from being set to false
        XposedBridge.hookAllMethods(
            Class.forName("android.app.SharedPreferencesImpl\$EditorImpl"),
            "putBoolean",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val key = param.args[0] as String
                    val value = param.args[1] as Boolean
                    if (key == "is_pro_verified" && !value) {
                        param.result = param.thisObject
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
        // Find via DexKit: "silentCheck" or license-related strings
        val result = DexKitFinder.findMethodByStrings("silentCheck")
        if (result != null) {
            try {
                val (className, methodName) = result
                val cls = cl.loadClass(className)
                for (m in cls.declaredMethods) {
                    if (m.name == methodName && m.parameterTypes.size == 2) {
                        XposedBridge.hookMethod(m, XC_MethodReplacement.DO_NOTHING)
                        XposedBridge.log("$TAG: Hooked $className.$methodName -> no-op")
                        return
                    }
                }
            } catch (_: Throwable) {}
        }
        XposedBridge.log("$TAG: LicenseManager hook skipped (not found)")
    }
}
