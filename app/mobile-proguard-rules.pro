# ONNX JNI constructs TensorInfo/OnnxJavaType and output wrappers by class name.
# Native method retention alone does not preserve these constructors or names.
# Official ONNX Android keep rule; attached-phone minified inference verifies it.
-keep class ai.onnxruntime.** { *; }
