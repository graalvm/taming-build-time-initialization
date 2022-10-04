package org.graalvm.example.safecomp;

class Unreachable {

}

class SuperType {
}
class SubType extends SuperType {
    // I am not specified as --initialize-at-build-time. How should I know someone is using me?
};

record Employee(int id, String firstName, String lastName) {
}
public class SafeComposition {

    // I don't know I am being used for computation at build time.
    public static SuperType create() { return new SubType(); };  // changed from return new SuperType();

    public static Class<?> forSimpleName(String className) throws ClassNotFoundException {
        System.out.println("Class.forName call on org.graalvm.example.safecomp." + className); // prevents inlining and computation of reachability metadata
        return Class.forName("org.graalvm.example.safecomp." + className);
    }

    static final SuperType f = create();

    public static String computeString() {
        String className = "Unreachable";
        return "org.graalvm.example.safecomp." + className;
    }

    public static void main(String[] args) {
        System.out.println("Employee(1,\"\", \"\") = " + new Employee(1,"", ""));
        try {
//            System.out.println(forSimpleName("Unreachable"));
            String className = "Unreachable";
            Class.forName(computeString());
            throw new RuntimeException("Safe composition issue.");
        } catch (ClassNotFoundException e) {
            System.out.println("failure as expected");
        }
        System.out.println(f);
    }
}
