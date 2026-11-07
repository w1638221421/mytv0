# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.lizongying.mytv0.data.** {
    <fields>;
}
# ---- 本次改造新增的规则 ----
# Gson 通过反射读取字段名，必须保留签名与数据类字段
-keepattributes Signature
-keepattributes *Annotation*

-keep class com.lizongying.mytv0.data.** { <fields>; }

# 播放源统计使用 TypeToken<Map<String, Stat>> 反序列化
-keep class com.lizongying.mytv0.playback.SourceStats$Stat { <fields>; }

# 播放源统计与线路记忆通过 Gson 反序列化，保留字段名
-keep class com.lizongying.mytv0.playback.SourceMemory { *; }
