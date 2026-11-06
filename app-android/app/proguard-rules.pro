# ============================================================
# FlyShare Release 混淆保留规则（feat_release_hardening）
# 原则：第三方库保守保留原样（它们本就开源，无需混淆）；
#       混淆主攻我们自己的 com.tvflyshare.app 业务代码，
#       既提高逆向门槛，又规避反射/序列化在运行时崩溃。
# ============================================================

# ---- 1. kotlinx.serialization：防止运行时 JSON 序列化崩溃 ----
# 项目大量使用 @Serializable 数据类做设备发现/HTTP/更新的 JSON 收发。
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class kotlin.Metadata { *; }

# 保留所有 @Serializable 数据类（含类名与成员），确保反序列化可定位
-keep @kotlinx.serialization.Serializable class * { *; }
# 保留编译器生成的 $$serializer 与 serializer() 入口
-keep,includedescriptorclasses class com.tvflyshare.app.**$$serializer { *; }
-keepclasseswithmembers class com.tvflyshare.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---- 2. 第三方核心库：保持原样，规避反射/认证运行时问题 ----
# NanoHTTPD（HTTP 传输核心，fi.iki.elonen 包）
-keep class fi.iki.elonen.** { *; }
# smbj（SMB 核心，含认证与加密反射）+ 其依赖 bouncycastle
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
# mbassador 事件总线（smbj 0.12.2 内部依赖 net.engio.mbassy）：其 ReflectiveHandlerInvocation
# 通过 getConstructor(SubscriptionContext.class) 反射实例化 handler；仅 -dontwarn 不足以保命，
# 必须 -keep 否则 R8 混淆后 NoSuchMethodException → SMBClient 构造即闪退（release 专属，debug 不混淆故不崩）
-keep class net.engio.mbassy.** { *; }
# ZXing（二维码生成/识别）
-keep class com.google.zxing.** { *; }
# commons-net（FTP 核心）：保守保留原样，规避 R8 优化对 FTP 路径的误伤。
# 其 FTP 核心路径无反射，可达类本会被 R8 保留（仅改名），此处按"第三方库保守保留"策略整库保名，
# 既对齐项目规范，也为未来 R8 优化/升级留缓冲（FTP 是核心功能，release 必须稳）。
-keep class org.apache.commons.net.** { *; }

# ---- 3. Compose 导航序列化路由 ----
# FileListScreen 等以 @Serializable 对象作为导航路由，
# 已由上方第 1 条 @Serializable 规则覆盖，无需额外处理。

# ---- 3.1 kotlinx.serialization 多态序列化前向预警 ----
# 当前所有 JSON 收发均用显式具体类型（decodeFromString<T>），按字段名+生成 serializer 工作，
# 类名被混淆改名无影响，故无需保类名。但若未来引入"多态/密封类序列化"（用类名作类型判别，
# 如 @Serializable sealed interface + classDiscriminator / serializersModule / PolymorphicSerializer），
# 则子类类名被 R8 改名会导致反序列化失败。届时必须补充（并真机复验）：
#   -keep class com.tvflyshare.app.** { *; }            # 或仅保涉及多态的包
#   -keep class kotlinx.serialization.SerializersKt { *; }
# 改之前先 grep Polymorphic/classDiscriminator/serializersModule 确认是否已引入多态。

# ---- 3.2 release 包裁剪调试日志（2026-11-01 审计 P3 修复）----
# 背景：全项目 183 处 android.util.Log 调用，正式包里照样打印局域网地址、文件路径、
#       传输进度；改代码逐个加 BuildConfig.DEBUG 判断要动 34 处源文件，风险高、易漏。
# 做法：交给 R8。开启优化后（本工程用 proguard-android-optimize.txt），
#       assumenosideeffects 会在 release 阶段把这几类调用连同它们的字符串拼接参数一起删掉，
#       源码一行不改，debug 包不受影响（debug 未开 minify）。
# 保留 w/e：线上排障仍需错误与警告；只裁 d/i/v 这些开发期信息。
# 注意：若将来出现「依赖 Log 返回值」的写法（如 Log.d(...).let{} / val x = Log.i(...)），
#       此规则会让返回值变成未初始化，需先 grep 确认无此类用法再保留本节。
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---- 4. R8 缺失类抑制（Android 运行时本就不包含这些可选依赖）----
# 来源：Tink(androidx.security.crypto) 的 errorprone 注解与 javax.annotation(JSR305) 注解、
#       bouncycastle 的 javax.naming(LDAP CRL/证书存储)、mbassy 事件总线的 EL、
#       smbj 的 Kerberos/SPNEGO(GSSAPI, 属 Java SE 标准库, Android 无)。
# 这些类在 Android 上不会走到对应代码路径(NTLM 认证替代 Kerberos/LDAP)，仅作注解/类型签名引用，
# 运行时不被加载，故仅抑制缺失告警（由 R8 生成的 missing_rules.txt 确认需 -dontwarn）。
# 注：移除 coil 等传递依赖后，原先间接带进来的 jsr305(javax.annotation) 会消失，
#     导致 Tink 引用的 javax.annotation.Nullable 变为 missing 而触发 R8 报错，必须显式 -dontwarn。
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.naming.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
