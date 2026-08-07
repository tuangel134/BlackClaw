# ============================================================
# 通用配置
# ============================================================
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保留枚举
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}


# ============================================================
# BlackClaw app classes
# ------------------------------------------------------------
# These used to be three blanket `-keep class com.blackclaw.android.{agent,
# tool,channel}.** { *; }` rules, i.e. the entire core of the app (tool/ alone
# is ~142 files) excluded from shrinking AND obfuscation, which largely
# cancelled out isMinifyEnabled=true. Audit of the stated "reflection/SPI"
# justification:
#
#   * Tools are NOT constructed reflectively. ToolRegistry.registerAllTools()
#     calls register(SomeTool()) explicitly for every single tool, so no tool
#     class needs a keep to be instantiable.
#   * Tool identity does not depend on class names: every getName() returns a
#     literal string ("assistant_reminder", "run_routine", ...), and
#     LangChain4jToolBridge builds ToolSpecifications from getName() /
#     getDescription() / ToolParameter getters — never from reflection.
#   * Channel handlers are constructed explicitly in ChannelManager.init()
#     (DiscordChannelHandler(...), TelegramChannelHandler(...), ...). The
#     DingTalk/Lark SDK reflective callbacks that originally justified the
#     channel keep no longer exist — those SDKs have been removed.
#   * The only Class.forName()/Proxy use in the app targets android.media.*
#     framework classes (RecognizeSongTool), not app classes.
#   * No META-INF/services descriptors exist in this project, so nothing is
#     ServiceLoader/SPI-loaded.
#
# What genuinely still needs keeping is Gson field-name mapping. This project
# uses no @SerializedName anywhere, so any class Gson reflects over must keep
# its field names verbatim or the emitted/consumed JSON keys get renamed.

# ToolResult is Gson-serialized and handed to the LLM, and
# AgentContextCompressor.summarizeToolResult() reads the keys "isSuccess",
# "data" and "error" by literal name. Renaming these fields silently breaks
# tool-result compression in release only.
-keep class com.blackclaw.android.tool.ToolResult { *; }

# RepeatActionsTool.ActionStep is Gson-DESERIALIZED from LLM-supplied JSON
# ({"tool":...,"params":{...}}), so its field names are part of the wire format.
# The no-arg constructor is kept so Gson doesn't have to fall back to Unsafe.
-keep class com.blackclaw.android.tool.impl.RepeatActionsTool$ActionStep {
    <init>();
    <fields>;
}

# OkHttp bridge for LangChain4j's HttpClientBuilder. It is constructed
# explicitly in LlmClientFactory (not SPI-loaded), but it implements
# third-party interfaces and is only three classes, so keeping it verbatim
# costs almost nothing and removes a whole class of release-only risk.
-keep class com.blackclaw.android.agent.langchain.http.** { *; }

# Declared in AndroidManifest.xml. AGP normally generates keep rules for
# manifest components automatically; stated explicitly so the rule survives
# any change in that behaviour.
-keep class com.blackclaw.android.tool.impl.ClipboardReaderActivity { <init>(...); }

# ============================================================
# Gson
# ============================================================
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Gson 使用 TypeToken 泛型
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ============================================================
# OkHttp
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================
# Misc
# (Retrofit rules removed with the Retrofit dependency — it had zero imports.)
# ============================================================
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn kotlin.Unit

# ============================================================
# LangChain4j
# ============================================================
-dontwarn dev.langchain4j.**
-keep class dev.langchain4j.** { *; }
-keep interface dev.langchain4j.** { *; }

# ============================================================
# Jackson (LangChain4j 内部依赖，序列化需要保留构造器和字段)
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <init>(...);
}

# ============================================================
# Jackson（LangChain4j OpenAI 内部 JSON 序列化依赖）
# 缺少此规则会导致 R8 混淆 Jackson 内部类，运行时报
# "Class xxx has no default (no arg) constructor"
# ============================================================
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
# 保留带 Jackson 注解的类成员（字段/方法）
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
    @com.fasterxml.jackson.databind.annotation.* *;
}
# 保留 Jackson 需要通过反射创建的类的无参构造函数
-keepclassmembers,allowobfuscation class * {
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
}

# ============================================================
# MMKV
# ============================================================
-keep class com.tencent.mmkv.** { *; }

# ============================================================
# LiteRT-LM
# JNI bridge reflects back into the Java/Kotlin wrapper classes.
# If R8 obfuscates Engine / Conversation / Message / Contents, native method
# lookups like nativeCreateConversation can fail with "mid == null".
# Keep the whole wrapper package stable in release builds.
# ============================================================
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keepnames class com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================
# Glide
# ============================================================
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { <init>(...); }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}
-dontwarn com.bumptech.glide.**


# ============================================================
# The Lark (com.larksuite.oapi:oapi-sdk) and DingTalk
# (com.dingtalk.open:app-stream-client) server-side SDKs had zero imports in
# the app and were removed from the build, together with everything they
# dragged in transitively: Netty (+tcnative), Apache HttpClient/Commons,
# Log4j/Log4j2, Jetty ALPN/NPN and javax.naming. Their -keep/-dontwarn rules
# went with them — verified absent from releaseRuntimeClasspath.
# (-keepattributes Signature is already declared in the 通用配置 block above.)
# ============================================================
-dontwarn org.jetbrains.annotations.**

# ============================================================
# ZXing
# ============================================================
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# ============================================================
# BlankJ UtilCode
# ============================================================
-dontwarn com.blankj.**
-keep class com.blankj.utilcode.** { *; }
-keep public class com.blankj.utilcode.util.** { *; }

# ============================================================
# EasyFloat
# ============================================================
-dontwarn com.lzf.easyfloat.**
-keep class com.lzf.easyfloat.** { *; }

# ============================================================
# Kotlin / Coroutines
# ============================================================
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**

# ============================================================
# AndroidX
# ============================================================
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# ============================================================
# Vosk (offline speech recognition) + JNA
# Vosk's native code uses JNI to look up fields/classes BY NAME (notably
# com.sun.jna.Pointer.peer). R8 renaming them causes at runtime:
#   UnsatisfiedLinkError: Can't obtain peer field ID for class com.sun.jna.Pointer
# So JNA and Vosk must be kept verbatim (names + members).
# ============================================================
-dontwarn com.sun.jna.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn org.vosk.**
-keep class org.vosk.** { *; }
-keepclassmembers class org.vosk.** { *; }

# (glide-transformations / jp.wasabeef rules removed with the dependency —
#  it had zero imports.)
