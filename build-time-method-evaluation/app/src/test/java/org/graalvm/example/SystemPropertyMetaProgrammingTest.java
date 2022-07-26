package org.graalvm.example;

import org.graalvm.example.nativeimage.meta.Constant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SystemPropertyMetaProgrammingTest {

    @Constant
    public static Boolean getBooleanPropertyAtBuildTime(String property) {
        return Boolean.getBoolean(property);
    }

    @BeforeAll
    public static void setupProperty() {
        System.setProperty("remove.UnreachableClass", "true");
    }

    @AfterAll
    public static void clearProperty() {
        System.clearProperty("remove.UnreachableClass");
    }

    static final boolean REMOVE_UNREACHABLE_CLASS = getBooleanPropertyAtBuildTime("remove.UnreachableClass");

    @Constant
    static boolean getRemoveUnreachableClass() {
        return REMOVE_UNREACHABLE_CLASS;
    }

    @Test
    public void testRemoveCodeAtBuildTime() {
        System.out.println("Starting the SystemPropertyAtBuildTimeTest.");
        if (!getBooleanPropertyAtBuildTime("remove.UnreachableClass")) {
            UnreachableClass.init();
        }

//        This will break as constants don't get inlined? Should we allow @Constant to the field.
//        if (!REMOVE_UNREACHABLE_CLASS) {
//            if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
//                UnreachableClass.init();
//            }
//        }
//        This will however work as expected:
        if (!getRemoveUnreachableClass()) {
            if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
                UnreachableClass.init();
            }
        }
    }

}


class UnreachableClass {
    static {
        System.err.println("This class must not be reached!");
        System.exit(-1);
    }

    public static void init() {
    }
}
