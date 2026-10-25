# P0 不混淆（isMinifyEnabled=false）。以后开混淆时，需要保住的只有：
#  - Frida agent 通信用的 JSON 模型（kotlinx.serialization 数据类）
#  - 无障碍服务 / 前台服务（清单里声明了，R8 默认保留四大组件，无需额外规则）
-dontwarn org.jetbrains.annotations.**
