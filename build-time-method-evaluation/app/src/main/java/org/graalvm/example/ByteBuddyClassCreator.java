package org.graalvm.example;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.graalvm.example.nativeimage.meta.Constant;

import java.lang.reflect.InvocationTargetException;

public class ByteBuddyClassCreator {

    public static final String GREETER_MESSAGE = "Hello World!";

    @Constant
    private static Class<?> createByteBuddyClass() {
        return new ByteBuddy()
                .subclass(Object.class)
                .method(ElementMatchers.named("toString"))
                .intercept(FixedValue.value(GREETER_MESSAGE))
                .make()
                .load(ByteBuddyClassCreator.class.getClassLoader())
                .getLoaded();
    }

    public static Object createGreeter() throws InstantiationException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        return createByteBuddyClass().getConstructor().newInstance();
    }

    static {
        System.out.println("Hello from the ByteBuddy class creator!");
    }

}
