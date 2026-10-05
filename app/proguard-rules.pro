# Keep the Xposed entry class (it is referenced only from assets/xposed_init).
-keep class com.qoder.sogousym.MainHook { *; }
-keep class com.qoder.sogousym.Mapping { *; }
-keep class com.qoder.sogousym.Prefs { *; }
-keep class com.qoder.sogousym.ConfigProvider { *; }
