# GCode release rules. Library-specific rules are added as libraries arrive
# (kotlinx.serialization, JGit, BouncyCastle) in later phases.

# Keep source file names and line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Navigation 3 saves the back stack with kotlinx.serialization; keep route classes and their serializers
# stable so saved state restores after process death in minified builds.
-keep class dev.gcode.app.ui.navigation.** { *; }
-keep class dev.gcode.app.ui.licenses.BundledLicense { *; }

# JGit 5.13: optional integrations that are not on Android (JMX monitoring, SSH/GPG modules, servlet).
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn org.eclipse.jgit.internal.transport.ssh.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.servlet.**
-dontwarn org.slf4j.impl.**
# JGit loads some classes by name (transports, merge strategies, text encodings).
-keep class org.eclipse.jgit.transport.** { *; }
-keep class org.eclipse.jgit.merge.** { *; }
-keep class org.eclipse.jgit.lib.** { *; }
-keep class org.eclipse.jgit.internal.JGitText { *; }
-keepclassmembers class * extends org.eclipse.jgit.nls.TranslationBundle { *; }

# Sora Editor TextMate support: tm4e builds grammar and theme models from JSON at runtime.
-keep class org.eclipse.tm4e.** { *; }
-keep class io.github.rosemoe.sora.langs.textmate.registry.model.** { *; }
-dontwarn org.eclipse.tm4e.**

# jsoup (web_fetch tool): ships -dontwarn for its optional re2j engine; JSpecify nullness annotations are compile-only.
-dontwarn org.jspecify.annotations.**
