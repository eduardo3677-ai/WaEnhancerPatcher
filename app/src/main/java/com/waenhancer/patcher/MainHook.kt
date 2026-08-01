package com.waenhancer.patcher

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.lang.reflect.Field

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

        // Search for ProHelper by scanning dex entries
        val proHelper = findProHelper(cl)
        if (proHelper != null) {
            hookProHelper(proHelper)
        } else {
            XposedBridge.log("$TAG: ProHelper not found, framework hooks only")
        }

        hookSharedPrefsImpl()
        hookTextView()
    }

    private fun findProHelper(cl: ClassLoader): Class<*>? {
        // Try direct name first (works on debug builds)
        try {
            return cl.loadClass("com.waenhancer.xposed.utils.ProHelper")
        } catch (_: Throwable) {}

        // For obfuscated release/beta builds: scan all dex entries
        XposedBridge.log("$TAG: ProHelper not found by name, scanning dex...")
        return scanDexForProHelper(cl)
    }

    private fun scanDexForProHelper(cl: ClassLoader): Class<*>? {
        try {
            // Get pathList from BaseDexClassLoader
            val clClass = cl.javaClass
            var pathList: Any? = null
            var cls: Class<*>? = clClass
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

            // Get dexElements array
            val dexElementsField = pathList!!.javaClass.getDeclaredField("dexElements")
            dexElementsField.isAccessible = true
            val dexElements = dexElementsField.get(pathList) as Array<*>
            if (dexElements.isEmpty()) return null

            // Iterate all dex entries and look for ProHelper
            for (element in dexElements) {
                val dexFile = getFieldValue(element!!,javaClass, element, "dexFile") ?: continue
                val entriesMethod = dexFile.javaClass.getMethod("entries")
                val entries = entriesMethod.invoke(dexFile) as java.util.Enumeration<String>

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    // Skip common framework packages
                    if (entry.startsWith("android.") || entry.startsWith("java.") ||
                        entry.startsWith("kotlin.") || entry.startsWith("androidx.") ||
                        entry.startsWith("com.google.") || entry.startsWith("de.robv.")) continue

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
        // ProHelper uniquely contains a method returning JSONObject with no args
        // AND methods with String args returning String/boolean
        // AND references to "is_pro_verified" or "encrypted_config" or "Disabled by Server"
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

            // ProHelper has: getDecryptedConfig() -> JSONObject, isProFeature(String) -> boolean,
            // getHookStringSafely(String) -> String
            if (hasJsonReturn && hasBoolStringMethod && hasStringStringMethod) {
                XposedBridge.log("$TAG: Candidate ProHelper: ${cls.name} (json=$hasJsonReturn bool=$hasBoolStringMethod str=$hasStringStringMethod)")
                return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun getFieldValue(cls: Class<*>, obj: Any, fieldName: String): Any? {
        return try {
            val f = cls.getDeclaredField(fieldName)
            f.isAccessible = true
            f.get(obj)
        } catch (_: Throwable) { null }
    }

    private fun hookProHelper(proHelper: Class<*>) {
        XposedBridge.log("$TAG: Hooking ProHelper: ${proHelper.name}")

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
                    val result = if (m.name.contains("Status", true) || m.name.contains("status", true)) "ACTIVE" else "Pro Active"
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
                    XposedBridge.log("$TAG: ${m.name}(boolean) -> no-op")
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

    private fun hookSharedPrefsImpl() {
        // SharedPreferences is an interface — hook the concrete implementation
        val spImpl = try {
            Class.forName("android.app.SharedPreferencesImpl")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: SharedPreferencesImpl not found: $t")
            return
        }

        // Hook getBoolean
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

        // Hook getString
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
                        }
                    }
                })
            XposedBridge.log("$TAG: SharedPreferencesImpl.getString hooked")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: getString hook failed: $t")
        }

        // Hook putBoolean on Editor to prevent is_pro_verified from being set to false
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

    private fun hookTextView() {
        try {
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
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: TextView hook failed: $t")
        }
    }
}
