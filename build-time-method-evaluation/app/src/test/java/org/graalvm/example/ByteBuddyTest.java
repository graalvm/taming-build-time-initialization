package org.graalvm.example;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;

public class ByteBuddyTest {

    @Test
    public void testGreeting() throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        Object greeter = ByteBuddyClassCreator.createGreeter();
        System.out.println("Created a greeter! The greeter says: " + greeter.toString());
        Assertions.assertEquals(greeter.toString(), ByteBuddyClassCreator.GREETER_MESSAGE);
    }

}
