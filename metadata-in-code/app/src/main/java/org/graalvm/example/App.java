package org.graalvm.example;

public class App {
    public static void main(String[] args) {
        System.out.println("JNI success: " + JNIExample.jniCallDoingReflection());
        System.out.println("Serialization success: " + SerializationAndDeserialization.serializeDeserialize());
        System.out.println("Resources success: " + ResourcesExample.resourcesSHA());
        System.out.println("Dynamic reflection example: " + DynamicReflectionExample.readChannels());
    }
}
