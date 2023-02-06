package org.graalvm.example;

import org.graalvm.example.stage.build.Requires;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Function;

interface Channel {
    byte[] read();
}

class Channel1 implements Channel {
    @Override
    public byte[] read() {
        return "hi".getBytes();
    }
}

class Channel2 implements Channel {
    @Override
    public byte[] read() {
        return "ho".getBytes();
    }
}


public class DynamicReflectionExample {
    private static String channelReflect(Channel c, @Requires(ChannelRequires.class) Class<? extends Channel> channelClass) {
        assert c.getClass() == channelClass;
        try {
            return new String((byte[]) c.getClass().getDeclaredMethod("read").invoke(c));
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException("Reading channel failed: " + e);
        }
    }

    public static class ChannelRequires implements Function<Class<?>, AnnotatedElement[]> {

        @Override
        public AnnotatedElement[] apply(Class<?> c) {
            try {
                return new AnnotatedElement[]{c, c.getDeclaredMethod("read")};
            } catch (NoSuchMethodException e) {
            }
            return new AnnotatedElement[]{c};
        }
    }

    public static String readChannels() {
        return channelReflect(new Channel1(), Channel1.class) + channelReflect(new Channel2(), Channel2.class);
    }
}
