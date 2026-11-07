# 2026-11-4：开启 R8（isMinifyEnabled = true）。
# 需要保住的只有：
#  - Frida agent 通信用的 JSON 模型（kotlinx.serialization 数据类）
#  - 无障碍服务 / 前台服务（清单里声明了，R8 默认保留四大组件，无需额外规则）
# ★frida 脚本（assets/agent_base.js）不反射 App 自身的类（只操作游戏进程），所以包名可以混淆。

-dontwarn org.jetbrains.annotations.**

# ── kotlinx.serialization（官方推荐规则）──
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lmj.labyrinthauto.**$$serializer { *; }
-keepclassmembers class com.lmj.labyrinthauto.** {
    *** Companion;
}
-keepclasseswithmembers class com.lmj.labyrinthauto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
