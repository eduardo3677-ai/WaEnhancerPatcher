package com.waenhancer.patcher;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WAE-Patcher";
    private static final String PRO_HELPER = "com.waenhancer.xposed.utils.ProHelper";
    private static final String PKG = "com.waenhancer";
    private static final Set<String> HOOK_KEYS = new HashSet<>();

    static {
        HOOK_KEYS.add("message_bomber");
        HOOK_KEYS.add("delete_message_file");
        HOOK_KEYS.add("pro_status_splitter");
        HOOK_KEYS.add("customize_status_control_class");
        HOOK_KEYS.add("always_typing_global");
        HOOK_KEYS.add("send_audio_as_voice_status");
        HOOK_KEYS.add("file_size_spoofer");
        HOOK_KEYS.add("filter_group_members_messages");
        HOOK_KEYS.add("unlock_premium_customization");
        HOOK_KEYS.add("recover_deleted_media");
        HOOK_KEYS.add("license_verify");
        HOOK_KEYS.add("filter_items");
        HOOK_KEYS.add("voice_status_validator_str");
        HOOK_KEYS.add("voice_status_prefix");
    }

    private static String obfuscateKey(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((input + "WAE_PATCHER_SALT").getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(hash.length, 16); i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String getModuleSignature(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            PackageInfo pi = lpparam.packageInfo;
            if (pi == null) {
                android.content.Context ctx = (android.content.Context) XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
                        "currentApplication");
                if (ctx != null) {
                    pi = ctx.getPackageManager().getPackageInfo(PKG, PackageManager.GET_SIGNATURES);
                }
            }
            if (pi != null && pi.signatures != null && pi.signatures.length > 0) {
                Signature sig = pi.signatures[0];
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(sig.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02x", b));
                return sb.toString().substring(0, 16);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to get module signature: " + t);
        }
        return "unknown";
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) return;

        String moduleSig = getModuleSignature(lpparam);
        String obfTag = obfuscateKey("waenhancer_patcher:" + moduleSig);
        XposedBridge.log(TAG + ": [" + obfTag + "] Hooking WaEnhancer to unlock Pro");

        ClassLoader cl = lpparam.classLoader;

        hookProHelper(cl, obfTag);
        hookApp(cl);
        hookLicenseManager(cl);
        hookPreferences(cl);
        injectProConfig(cl, obfTag);
    }

    private void hookProHelper(ClassLoader cl, String obfTag) {
        try {
            Class<?> proHelper = XposedHelpers.findClass(PRO_HELPER, cl);

            hookReturnConstant(proHelper, "isProEnabled", true);
            XposedBridge.log(TAG + ": [" + obfTag + "] isProEnabled -> true");

            hookReturnConstant(proHelper, "getProStatus", "ACTIVE");
            XposedBridge.log(TAG + ": [" + obfTag + "] getProStatus -> ACTIVE");

            hookReturnConstant(proHelper, "getProPlanName", "Pro Active");
            hookReturnConstant(proHelper, "isProFeature", String.class, true);
            hookReturnConstant(proHelper, "isPillDesignProEnabled", true);
            hookReturnConstant(proHelper, "isFilterItemsProEnabled", true);

            XposedBridge.findAndHookMethod(proHelper, "setForceFree",
                    boolean.class, XC_MethodReplacement.DO_NOTHING);

            XposedBridge.findAndHookMethod(proHelper, "getHookStringSafely",
                    String.class, new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            String hookKey = (String) param.args[0];
                            if (hookKey == null) return null;
                            return hookKey + "_" + obfTag;
                        }
                    });

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ProHelper hook error: " + t);
        }
    }

    private void hookApp(ClassLoader cl) {
        try {
            Class<?> app = XposedHelpers.findClass("com.waenhancer.App", cl);
            XposedBridge.findAndHookMethod(app, "onCreate",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + ": App.onCreate - skipping license checks");
                            return null;
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": App hook error: " + t);
        }
    }

    private void hookLicenseManager(ClassLoader cl) {
        try {
            Class<?> licenseManager = XposedHelpers.findClass(
                    "com.waenhancer.xposed.utils.LicenseManager", cl);
            try {
                Class<?> listenerClass = Class.forName(
                        "com.waenhancer.xposed.utils.LicenseManager$SilentCheckListener", false, cl);
                XposedBridge.findAndHookMethod(licenseManager, "silentCheck",
                        android.content.Context.class, listenerClass,
                        XC_MethodReplacement.DO_NOTHING);
                XposedBridge.log(TAG + ": LicenseManager.silentCheck -> no-op");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": LicenseManager hook skipped: " + t.getMessage());
        }
    }

    private void hookPreferences(ClassLoader cl) {
        try {
            Class<?> proSwitch = XposedHelpers.findClass(
                    "com.waenhancer.preference.ProSwitchPreference", cl);
            XposedBridge.findAndHookMethod(proSwitch, "onClick",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.invokeOriginalMethod(
                                    (Method) param.method, param.thisObject, new Object[0]);
                            return null;
                        }
                    });
            XposedBridge.log(TAG + ": ProSwitchPreference.onClick -> bypassed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ProSwitchPreference hook skipped: " + t.getMessage());
        }
    }

    private void injectProConfig(ClassLoader cl, String obfTag) {
        try {
            Class<?> proHelper = XposedHelpers.findClass(PRO_HELPER, cl);
            XposedBridge.findAndHookMethod(proHelper, "getDecryptedConfig",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            org.json.JSONObject config = new org.json.JSONObject();
                            org.json.JSONObject hooks = new org.json.JSONObject();
                            for (String key : HOOK_KEYS) {
                                hooks.put(key, key + "_" + obfTag);
                            }
                            config.put("hooks", hooks);
                            config.put("pill_design_pro_enabled", true);
                            XposedBridge.log(TAG + ": Injected config with " + hooks.length() + " hooks");
                            return config;
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Config injection error: " + t);
        }
    }

    private void hookReturnConstant(Class<?> cls, String methodName, Object returnValue) {
        try {
            XposedBridge.findAndHookMethod(cls, methodName, XC_MethodReplacement.returnConstant(returnValue));
        } catch (Throwable ignored) {}
    }

    private void hookReturnConstant(Class<?> cls, String methodName, Class<?> paramType, Object returnValue) {
        try {
            XposedBridge.findAndHookMethod(cls, methodName, paramType, XC_MethodReplacement.returnConstant(returnValue));
        } catch (Throwable ignored) {}
    }
}
