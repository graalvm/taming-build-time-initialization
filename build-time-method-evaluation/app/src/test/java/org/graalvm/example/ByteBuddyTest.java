package org.graalvm.example;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

public class ByteBuddyTest {

    public static final String HELLO_FIRST_CLASS_WORLD = "Hello, First Class World!";
    public static final String HELLO_SECOND_CLASS_WORLD = "Hello, Second Class World!";

    @Test
    public void testGreeting() throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        System.out.println("Created a greeter! The greeter says: " + ByteBuddyClassCreator.createByteBuddyClass(HELLO_FIRST_CLASS_WORLD).getConstructor().newInstance());
        System.out.println("Created another greeter! The greeter says: " + ByteBuddyClassCreator.createByteBuddyClass(HELLO_SECOND_CLASS_WORLD).getConstructor().newInstance());
        Assertions.assertEquals(ByteBuddyClassCreator.createByteBuddyClass(HELLO_FIRST_CLASS_WORLD).getConstructor().newInstance().toString(), HELLO_FIRST_CLASS_WORLD);
        Assertions.assertEquals(ByteBuddyClassCreator.createByteBuddyClass(HELLO_SECOND_CLASS_WORLD).getConstructor().newInstance().toString(), HELLO_SECOND_CLASS_WORLD);
    }

}
