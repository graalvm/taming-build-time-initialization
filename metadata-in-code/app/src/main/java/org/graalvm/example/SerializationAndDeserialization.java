package org.graalvm.example;

import sun.reflect.ReflectionFactory;

import java.io.*;
import java.lang.reflect.Constructor;

class Complex implements Serializable {
    public final double r;
    public final double i;

    Complex(double r, double i) {
        this.r = r;
        this.i = i;
    }

    public String toString() {
        return "Complex{" +
                "r=" + r +
                ", i=" + i +
                '}';
    }
}

public class SerializationAndDeserialization {

    public static Complex serializeDeserialize() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try {
            final Complex cn = new Complex(1, 1.41);
            new ObjectOutputStream(baos).writeObject(cn);
            System.out.println("Serialized: " + cn);
        } catch (IOException e) {
            throw new RuntimeException("Serialization failed.", e);
        }


        try {
            ReflectionFactory.getReflectionFactory()
                    .newConstructorForSerialization(SerializationAndDeserialization.class, Object.class.getConstructor());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }

        Complex obj;

        ObjectInputFilter inputFilter = ObjectInputFilter.Config.createFilter("org.graalvm.example.Complex;!*;");
        try {
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(baos.toByteArray()));
            ois.setObjectInputFilter(inputFilter);
            obj = (Complex) ois.readObject();
            System.out.println("Deserialized: " + obj);
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Deserialization failed.", e);
        }
        return obj;
    }
}
