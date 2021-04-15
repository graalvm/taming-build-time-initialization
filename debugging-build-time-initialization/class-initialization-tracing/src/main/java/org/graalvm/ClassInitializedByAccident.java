package org.graalvm;

public class ClassInitializedByAccident {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}

class A {
    static B b = new B();
}

class B {
}
