# 保持默认即可。翻译引擎是纯 Java，无需额外规则。
# 若启用 minify，注意保留 A32CPU 的公共方法名（当前未用反射，可全部混淆）。
-keep class com.example.elf32runner.engine.** { *; }
