package com.waenhancer.patcher;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "WAE-Patcher";
    private static final String PRO_HELPER = "com.waenhancer.xposed.utils.ProHelper";
    private static final String PKG = "com.waenhancer";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!PKG.equals(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": Hooking WaEnhancer to unlock all Pro features");

        ClassLoader cl = lpparam.classLoader;

        hookProHelper(cl);
        hookApp(cl);
        hookLicenseManager(cl);
        hookPreferences(cl);
    }

    private void hookProHelper(ClassLoader cl) {
        try {
            Class<?> proHelper = XposedHelpers.findClass(PRO_HELPER, cl);

            // isProEnabled() → true
            XposedBridge.findAndHookMethod(proHelper, "isProEnabled",
                    XC_MethodReplacement.returnConstant(true));
            XposedBridge.log(TAG + ": Hooked isProEnabled → true");

            // getProStatus() → "ACTIVE"
            XposedBridge.findAndHookMethod(proHelper, "getProStatus",
                    XC_MethodReplacement.returnConstant("ACTIVE"));
            XposedBridge.log(TAG + ": Hooked getProStatus → ACTIVE");

            // getProPlanName() → "Pro Active"
            XposedBridge.findAndHookMethod(proHelper, "getProPlanName",
                    XC_MethodReplacement.returnConstant("Pro Active"));
            XposedBridge.log(TAG + ": Hooked getProPlanName → Pro Active");

            // isPluginInstalled(Context) → true
            XposedBridge.findAndHookMethod(proHelper, "isPluginInstalled",
                    android.content.Context.class,
                    XC_MethodReplacement.returnConstant(true));
            XposedBridge.log(TAG + ": Hooked isPluginInstalled → true");

            // isPluginPackageInstalled(Context) → true
            XposedBridge.findAndHookMethod(proHelper, "isPluginPackageInstalled",
                    android.content.Context.class,
                    XC_MethodReplacement.returnConstant(true));
            XposedBridge.log(TAG + ": Hooked isPluginPackageInstalled → true");

            // isProFeature(String) → true for all keys
            XposedBridge.findAndHookMethod(proHelper, "isProFeature",
                    String.class,
                    XC_MethodReplacement.returnConstant(true));
            XposedBridge.log(TAG + ": Hooked isProFeature → always true");

            // isPillDesignProEnabled() → true
            XposedBridge.findAndHookMethod(proHelper, "isPillDesignProEnabled",
                    XC_MethodReplacement.returnConstant(true));

            // isFilterItemsProEnabled() → true
            XposedBridge.findAndHookMethod(proHelper, "isFilterItemsProEnabled",
                    XC_MethodReplacement.returnConstant(true));

            // setForceFree(boolean) → no-op (prevent license revocation)
            XposedBridge.findAndHookMethod(proHelper, "setForceFree",
                    boolean.class,
                    XC_MethodReplacement.DO_NOTHING);

            // getDecryptedConfig() → inject a fake config with all hook keys
            XposedBridge.findAndHookMethod(proHelper, "getDecryptedConfig",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return createFakeConfig(cl);
                        }
                    });

            // getHookStringSafely(String) → return the hookKey itself as the class name
            XposedBridge.findAndHookMethod(proHelper, "getHookStringSafely",
                    String.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            String hookKey = (String) param.args[0];
                            if (hookKey == null) return null;
                            // Return a non-empty string so updatePreferences doesn't mark it "Disabled by Server"
                            return hookKey;
                        }
                    });

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook ProHelper: " + t);
        }
    }

    private void hookApp(ClassLoader cl) {
        try {
            Class<?> app = XposedHelpers.findClass("com.waenhancer.App", cl);

            // Hook onCreate to skip license verification and expiration check
            XposedBridge.findAndHookMethod(app, "onCreate",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            XposedBridge.log(TAG + ": App.onCreate intercepted, skipping license checks");
                            // Call the original onCreate but the ProHelper hooks above will prevent revocation
                            return null;
                        }
                    });

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to hook App: " + t);
        }
    }

    private void hookLicenseManager(ClassLoader cl) {
        try {
            Class<?> licenseManager = XposedHelpers.findClass(
                    "com.waenhancer.xposed.utils.LicenseManager", cl);

            // silentCheck → no-op
            XposedBridge.findAndHookMethod(licenseManager, "silentCheck",
                    android.content.Context.class,
                    Class.forName("com.waenhancer.xposed.utils.LicenseManager$SilentCheckListener", false, cl),
                    XC_MethodReplacement.DO_NOTHING);
            XposedBridge.log(TAG + ": Hooked LicenseManager.silentCheck → no-op");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": LicenseManager hook skipped: " + t.getMessage());
        }
    }

    private void hookPreferences(ClassLoader cl) {
        try {
            // ProSwitchPreference.onClick → allow toggle (don't redirect to LicenseActivity)
            Class<?> proSwitch = XposedHelpers.findClass(
                    "com.waenhancer.preference.ProSwitchPreference", cl);

            XposedBridge.findAndHookMethod(proSwitch, "onClick",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            // Call the parent onClick directly
                            XposedBridge.invokeOriginalMethod(
                                    ((Method) param.method), param.thisObject, new Object[0]);
                            return null;
                        }
                    });
            XposedBridge.log(TAG + ": Hooked ProSwitchPreference.onClick → bypass license check");

        } catch (Throwable t) {
            XposedBridge.log(TAG + ": ProSwitchPreference hook skipped: " + t.getMessage());
        }

        try {
            // ProPreferenceCategory.init → always show green badge
            Class<?> proCat = XposedHelpers.findClass(
                    "com.waenhancer.preference.ProPreferenceCategory", cl);

            XposedBridge.findAndHookMethod(proCat, "isPluginInstalled",
                    android.content.Context.class,
                    XC_MethodReplacement.returnConstant(true));

        } catch (Throwable t) {
            // ProPreferenceCategory may not have isPluginInstalled, try init
        }
    }

    private org.json.JSONObject createFakeConfig(ClassLoader cl) {
        try {
            org.json.JSONObject config = new org.json.JSONObject();
            org.json.JSONObject hooks = new org.json.JSONObject();

            // These are the hook keys that getHookStringSafely() looks up.
            // The values don't need to be real class names since the features
            // are implemented directly in the main module (not via plugin).
            // They just need to be non-empty so updatePreferences doesn't disable them.
            hooks.put("message_bomber", "com.waenhancer.xposed.features.others.MessageBomber");
            hooks.put("delete_message_file", "com.waenhancer.xposed.features.others.DeleteMessageFile");
            hooks.put("pro_status_splitter", "com.waenhancer.xposed.features.others.ProStatusSplitter");
            hooks.put("customize_status_control_class", "com.waenhancer.xposed.features.others.StatusCustomization");
            hooks.put("always_typing_global", "com.waenhancer.xposed.features.others.AlwaysTyping");
            hooks.put("send_audio_as_voice_status", "com.waenhancer.xposed.features.others.SendAudioAsVoiceStatus");
            hooks.put("file_size_spoofer", "com.waenhancer.xposed.features.media.FileSizeSpoofer");
            hooks.put("filter_group_members_messages", "com.waenhancer.xposed.features.others.FilterGroupMembersMessages");
            hooks.put("unlock_premium_customization", "com.waenhancer.xposed.features.others.UnlockPremiumCustomization");
            hooks.put("recover_deleted_media", "com.waenhancer.xposed.features.others.RecoverDeletedMedia");
            hooks.put("license_verify", "com.waenhancer.xposed.features.others.AlwaysTyping");
            hooks.put("filter_items", "com.waenhancer.xposed.features.others.FilterGroupMembersMessages");
            hooks.put("voice_status_validator_str", "com.waenhancer.xposed.features.others.SendAudioAsVoiceStatus");
            hooks.put("voice_status_prefix", "voice_status");

            config.put("hooks", hooks);
            config.put("pill_design_pro_enabled", true);

            XposedBridge.log(TAG + ": Created fake Pro config with " + hooks.length() + " hooks");
            return config;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to create fake config: " + t);
            return null;
        }
    }
}
