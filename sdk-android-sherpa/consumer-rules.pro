# Keep sherpa-onnx public Kotlin API surface — the host app calls into these
# via SDK reflection-free wiring.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.medtroniclabs.microcoaching.sherpa.** { *; }
