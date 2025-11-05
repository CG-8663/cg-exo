# Add project specific ProGuard rules here.
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.protobuf.**

# Keep gRPC classes
-keep class io.grpc.** { *; }
-keep class com.google.protobuf.** { *; }

# Keep generated proto classes
-keep class node_service.** { *; }

# Keep TensorFlow Lite classes
-keep class org.tensorflow.lite.** { *; }
