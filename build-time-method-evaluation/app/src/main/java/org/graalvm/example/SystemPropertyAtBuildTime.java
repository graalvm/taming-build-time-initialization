package org.graalvm.example;

import org.graalvm.example.nativeimage.meta.Constant;

public class SystemPropertyAtBuildTime {

    public static final String MY_PROPERTY_KEY = "org.graalvm.example.property";

    public static String runtimePropertyValue = System.getProperty(MY_PROPERTY_KEY);
    public static String buildTimePropertyValue = getSystemPropertyAtBuildTime(MY_PROPERTY_KEY);

    @Constant
    public static String getSystemPropertyAtBuildTime(String property) {
        return System.getProperty(property);
    }

    static {
        System.out.println("Hello from the class initializer of " + SystemPropertyAtBuildTime.class.getName());
    }

}
