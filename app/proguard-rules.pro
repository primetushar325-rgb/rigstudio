# RigStudio ships no reflection-heavy libraries: the engine is plain Kotlin data and maths,
# Compose generates its own keep rules, and MediaCodec/MediaMuxer are framework classes.
# Shrinking is therefore safe with the defaults; these lines only keep crash reports readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
