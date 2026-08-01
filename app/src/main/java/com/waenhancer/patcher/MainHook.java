package com.waenhancer.patcher;

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) return;

        String obfTag = computeObfTag();
        XposedBridge.log(TAG + ": [" + obfTag + "] Hooking WaEnhancer to unlock Pro");

        ClassLoader cl = lpparam.classLoader;

        hookProHelper(cl, obfTag);
        hookApp(cl);
        hookLicenseManager(cl);
        hookPreferences(cl);
        injectProConfig(cl, obfTag);
    }

    private String computeObfTag() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(("waenhancer_patcher_salt").getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (Throwable t) {
            return "patcher_fallback";
        }
    }

    private void hookProHelper(ClassLoader cl, String obfTag) {
        try {
            Class<?> proHelper = XposedHelpers.findClass(PRO_HELPER, cl);

            try { XposedBridge.findAndHookMethod(proHelper, "isProEnabled", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { XposedBridge.findAndHookMethod(proHelper, "getProStatus", XC_MethodReplacement.returnConstant("ACTIVE")); } catch (Throwable ignored) {}
            try { XposedBridge.findAndHookMethod(proHelper, "getProPlanName", XC_MethodReplacement.returnConstant("Pro Active")); } catch (Throwable ignored) {}
            try { XposedBridge.findAndHookMethod(proHelper, "isProFeature", String.class, XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { XposedBridge.findAndHookMethod(proHelper, "isPillDesignProEnabled", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}
            try { XposedBridge.findAndHookMethod(proHelper, "isFilterItemsProEnabled", XC_MethodReplacement.returnConstant(true)); } catch (Throwable ignored) {}

            try {
                XposedBridge.findAndHookMethod(proHelper, "setForceFree",
                        boolean.class, XC_MethodReplacement.DO_NOTHING);
            } catch (Throwable ignored) {}

            try {
                XposedBridge.findAndHookMethod(proHelper, "getHookStringSafely",
                        String.class, new XC_MethodReplacement() {
                            @Override
                            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                                String hookKey = (String) param.args[0];
                                if (hookKey == null) return null;
                                return hookKey + "_" + obfTag;
                            }
                        });
            } catch (Throwable ignored) {}

            XposedBridge.log(TAG + ": ProHelper hooks installed");
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
                        Class.forName("android.content.Context"), listenerClass,
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
                            return config;
                        }
                    });
            XposedBridge.log(TAG + ": Config injection installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Config injection error: " + t);
        }
    }
}
