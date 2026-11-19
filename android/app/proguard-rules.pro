# GlideDrop ProGuard / R8 keep rules
-keep public class io.github.jakharamit44.glidedrop.MainActivity { *; }
-keep public class io.github.jakharamit44.glidedrop.GlideDropService { *; }
-keep class io.github.jakharamit44.glidedrop.GlideDropViewModel { *; }
-keepattributes SourceFile,LineNumberTable
-dontwarn org.bouncycastle.**
