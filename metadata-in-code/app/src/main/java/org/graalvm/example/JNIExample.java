package org.graalvm.example;

import org.graalvm.example.stage.build.Reaches;

import java.lang.reflect.AnnotatedElement;
import java.util.function.Supplier;

public class JNIExample {

    private static final class JNIExampleReachability implements Supplier<AnnotatedElement[]> {
        @Override
        public AnnotatedElement[] get() {
            try {
                return new AnnotatedElement[] {
                        JNIExample.class.getField("v1"),
                        JNIExample.class.getMethod("v2")
                };
            } catch (NoSuchMethodException | NoSuchFieldException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final int v1 = 21;

    public static int v2() {
        return 21;
    }

    static {
        System.loadLibrary("native");
    }
    /**
     * Reflectively computes v1 + v2() from native code.
     */
    @Reaches(JNIExampleReachability.class)
    public static native int jniCallDoingReflection();
}
