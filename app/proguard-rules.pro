# Rust-ядро через UniFFI/JNA: классы и поля ищутся по имени в рантайме.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class uniffi.chudo_core.** { *; }
-dontwarn java.awt.**

# Номера строк в отчётах о сбоях, без исходных имён файлов.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
