package org.graalvm.example;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SystemPropertyTest {

    public static final String EXPECTED_BUILDTIME_VALUE = "image-build-time";
    public static final String EXPECTED_RUNTIME_VALUE = "image-run-time";

    @BeforeAll
    public static void setupProperty() {
        System.setProperty(SystemPropertyAtBuildTime.MY_PROPERTY_KEY, EXPECTED_RUNTIME_VALUE);
    }

    @AfterAll
    public static void clearProperty() {
        System.clearProperty(SystemPropertyAtBuildTime.MY_PROPERTY_KEY);
    }

    @Test
    public void testSystemPropertyDifferent() {
        System.out.println("Starting the SystemPropertyAtBuildTimeTest.");
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            System.out.println("Testing the build time property value.");
            assertEquals(EXPECTED_BUILDTIME_VALUE, SystemPropertyAtBuildTime.buildTimePropertyValue);
        }
        assertEquals(EXPECTED_RUNTIME_VALUE, SystemPropertyAtBuildTime.runtimePropertyValue);
    }


}
