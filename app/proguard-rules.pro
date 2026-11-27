# GCode release rules. Library-specific rules are added as libraries arrive
# (kotlinx.serialization, JGit, BouncyCastle) in later phases.

# Keep source file names and line numbers for readable crash stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Navigation 3 saves the back stack with kotlinx.serialization; keep route classes and their serializers
# stable so saved state restores after process death in minified builds.
-keep class dev.gcode.app.ui.navigation.** { *; }
-keep class dev.gcode.app.ui.licenses.BundledLicense { *; }
