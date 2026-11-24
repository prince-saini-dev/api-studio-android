# The interceptor is referenced by apps; OkHttp is optional at runtime.
-keep class cloud.apistudio.** { public *; }
-dontwarn okhttp3.**
-dontwarn okio.**
