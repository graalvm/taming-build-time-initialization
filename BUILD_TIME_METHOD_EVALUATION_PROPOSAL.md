# Computing (Reachability) Metadata in User Code

This document proposes a mechanism for computing [reachability metadata](https://www.graalvm.org/22.2/reference-manual/native-image/metadata/) in user code at build time.
We focus on computing reachability metadata in the user code as it is easier to maintain compared to externally specified metadata (e.g., in JSON files).

Before we continue, we state the following requirement that we call *safe composition*: Syntactically and semantically correct changes to a method's body must not break compilation or runtime of any program that is using that method.

Currently, there are three ways to compute reachability metadata in user code:

1. Intrinsics for `java.lang.Class#forName` and methods on `java.lang.reflect.Field` and `java.lang.reflect.Method`. Given constant arguments method invocations will be computed in user code at build time. For example, `Class.forName("Foo")` will be computed at build time and, if class `Foo` is valid, it will be marked as reachable.  

2. Inlining before analysis. This is the mechanism for inlining multiple levels of methods that the compiler can reduce to a constant.
The problem with this approach is that it is hard to specify what a compiler can reduce to a constant. Because of that safe composition is not possible. For example, adding a logging statement to the following function will break any usage of it at runtime: 
```java
public static Class<?> forSimpleName(String className) {
    log("Class.forName call on my.package." + className); // prevents computation of reachability metadata
    return Class.forName("my.package." + clazzName);
}
```

3. Initialization at build time via the command line flag `--initialize-at-build-time=<class_name>` executes the static initializer of `class_name` and stores the results in all static fields of `class_name` that are then embedded in the image heap.
The problem with this approach is that safe composition is not possible. In the following example, 
```java
public class SubType extends SuperType {
    // I am not specified as --initialize-at-build-time. How should I know someone is using me? 
};

// I don't know I am being used for computation at build time. 
public static SuperType create() { return new SubType(); };  // changed from return new SuperType();
```
changing `create` to return `new SubType();` (semantically valid change) would result in a failure when this value is stored in the image heap.

Another inconvenience with `--initialize-at-build-time` is that either all static fields of a class are included in the heap or none. 
It is not possible, to select only certain static fields of a complex class to be stored in the image heap. 

## Requirements for Computing Metadata in User Code

1. Safe composition. Changes to a method body that are semantically correct must not cause breakage to the users of that method.
2. If Java adopts a similar language feature, code changes would be minimal.
3. It is possible to reason about build-time computation on a single compilation unit.
4. (Good to have) Requires minimal code changes in the JVM ecosystem to compute reachability metadata in the user code.
5. (Good to have) Can be used for precomputing and snapshotting complex data structures such as ML models, parsed configuration, etc.

Note that some programs are dynamic in nature and the reachability metadata can't be computed at build time (see implementations of `java.util.Collections.CheckedCollection.checkedCopyOf`, or [Spies in Mockito](https://www.baeldung.com/mockito-spy`).

## Computing Reachability Metadata in Method Bodies
 
We propose an annotation that can be placed on methods:
```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD})
public @interface Constant {
}
```

Methods annotated with `@Constant` will be taken into account if and only if:
1. The method return type is: primitive, `java.lang.constant.Constable` types, `java.lang.reflect.Method`, `java.lang.reflect.Field`.
2. They are marked with `static` or `final`.

A method annotated with `@Constant` will be computed at build time and the result stored in the image heap if all of its arguments are constant including the receiver. An argument is constant when:
1. It is a literal, e.g., `1`, `"Foo"`, `{ 1, 2, 3, 4, 6, 12, 24 }`.
2. It is a `static final` field computed at build time and allowed to be stored to the image heap. 

In case a `@Constant` annotated method throws an exception at build time, the invocation is replaced with a `throw` statement that will rethrow the same exception at runtime.

Removal of the `@Constant` annotation is considered a breaking change for an API.

An example of a `@Constant`-annotated method that creates a new class whose `toString` returns a `message`:
```java
class ClassCreator {
    @Constant
    public static Class<?> createClass(String message) {
        return new ByteBuddy().subclass(Object.class)
                .method(ElementMatchers.named("toString")).intercept(FixedValue.value(message)).make()
                .load(ByteBuddyClassCreator.class.getClassLoader()).getLoaded();
    }
}
```

A call to this method with a constant argument generates a class at build-time and the program executes successfully. The generated class is included in the image without externally provided metadata: 
```java
class Greeter {
    public static final String HELLO_SECOND_CLASS_WORLD = "Hello, Second " + Class.class.getSimpleName() + " World!"; // initialized at build time

    public void printGreetings() throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        System.out.println(ClassCreator.createByteBuddyClass("Hello, First Class World!").getConstructor().newInstance());
        System.out.println(ClassCreator.createByteBuddyClass(HELLO_SECOND_CLASS_WORLD).getConstructor().newInstance());
    }
}
```

## Computing Reachability Metadata in Complex Methods

In the previous example, we had to duplicate the calls to `.getConstructor().newInstance()` as it is not possible to make abstractions over `@Constant`-annotated methods. In this case, the `@Constant`-annotated method can not return an instance of a generated class as it does not follow the return-type restrictions. To allow arbitrary abstractions over `@Constant`-annotated methods we propose the following annotation:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER})
public @interface Propagate {
}
```

This annotation can be placed only on method parameters, and it signifies that if a `@Propagate`-annotated parameter receives a constant argument:
1. Usages of that parameter by `@Constant`-annotated methods will be accounted as constants and computed at build time. 
2. Usages of that argument will be further propagated to `@Propagate`-annotated method parameters.
3. (Optional) All constants computed by the `@Propagate` parameter receivers are regarded as constants.

If a method has multiple constant parameters each will be treated separately, i.e., any subset of them can be constant for the transformation to apply.
If there is a recursive call that receives `@Propagate` parameters, the transformation is halted.

For the previous example we can now create abstractions that contain code that should not be executed at build time:
```java
class Greeter {
    public static final String HELLO_SECOND_CLASS_WORLD = "Hello, Second " + Class.class.getSimpleName() + " World!"; // initialized at build time

    private String createLogAndInstantiate(@Propagate String message) {
        log("Instantiating class for message: " + message); // not computed at build-time
        return createAndInstantiate(message);
    }

    private Object createAndInstantiate(@Propagate String message) {
        try {
            return ClassCreator.createByteBuddyClass(message).getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public void printGreetings() {
        System.out.println(createLogAndInstantiate("Hello, First Class World!"));
        System.out.println(createLogAndInstantiate(HELLO_SECOND_CLASS_WORLD));
    }
}
```

Behind the scenes the method `createAndInstantiate` will be transformed into:
```java
    private String createAndInstantiate(@Propagate String message) {
        String l1 = "Hello, First Class World!";
        String l2 = HELLO_SECOND_CLASS_WORLD;
        try {
            Class<?> c, cl1 = null, cl2 = null;
            if (message == l1) cl1 = c = ClassCreator.createByteBuddyClass(l1); // build-time
            else if (message == l2) cl2 = c = ClassCreator.createByteBuddyClass(l2); // build-time
            else c = ClassCreator.createByteBuddyClass(message);

            Constructor cc;
            if (c == cl1) cc = cl1.getConstructor(); // build-time
            else if (c == cl2) cc = cl2.getConstructor(); // build-time
            else cc = c.getConstructor();

            return cc.newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException |
                 NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
```

Note the implementation would be slightly more complicated as `@Constant`-annotated methods can have multiple parameters. 
For those we would need to do equality on a group constants that belongs to the same "invocation".

Performance of these methods should not be affected as hot methods are usually inlined and after inlining 
all of newly introduced conditionals are optimized away.

## Storing (Reachability) Metadata in Complex Structures

`@Constant` and `@Propagate` can be used in code for a restricted set of types. However, to compute complex data structures it is necessary to relax the set of types and store them in static fields.

### [Solution 1] `@InitializeAtBuildTime`: Sacrifice Safe Composition for Minimal Code Changes 

A simple solution would be to expose the existing `--initialize-at-build-time` flag via an annotation:
```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface InitializeAtBuildTime {
}
```

Then the fields that need to be computed at build time can be extracted in a static inner class. For example:
```java
class Greeter {
    // computed at runtime
    public static final String HELLO_FIRST_CLASS_WORLD = "Hello, First " + Class.class.getSimpleName() + " World!";
    
    @InitializeAtBuildTime
    public static Constants {
        public static final String HELLO_SECOND_CLASS_WORLD = "Hello, Second " + Class.class.getSimpleName() + " World!"; // initialized at build time
    }
}
```

This mechanism requires minimal boilerplate for the inner class, however it is still possible to break the safe composition of programs.  

### [Solution 2] `@AllowInImageHeap`: Sacrifice Minimal Code Changes for Safe Composition

Allow `@Constant` on static fields. When a field is annotated with `@Constant` the following must apply: 
1) Computation of such field depends only on other constants and constant fields.
2) The field must be `final`.
3) Type must be primitive, `Constable`, `Method`, `Field`, `AllowInImageHeap`-annotated, or an array or record of arbitrary depth containing only such types. 

Introduce an annotation on types:
```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.TYPE_PARAMETER})
public @interface AllowInImageHeap {
}
```

`AllowInImageHeap` is valid only on:
1. Generic type parameters. 
2. Types:
   * Whose all static fields are annotated with `@Constant`. (Optionally we can simply disallow static fields and static)
   * That do not have a `static` initializer block.
   * Whose all supertypes and superinterfaces are annotated with `@AllowInImageHeap`.
   * Whose all fields must be either primitive, `Constable`, `Method`, `Field`, `AllowInImageHeap`-annotated, or an array or record of arbitrary depth containing only such types.

Removing the `@Constant` from the field or `@AllowInImageHeap` is considered an API-breaking change.

The constant field is computed by analysing the dataflow of the `<clinit>` method and extracting the code required to execute for the computation of such field in a separate methods.
This would be done by a bytecode-to-bytecode transformation.

`@Constant` fields can be used as arguments to `@Constant`-annotated methods and `@Propagate`-annotated params. With `@Constant`, our static field initialized at build can be now written
```java
class Greeter {
    public static final String HELLO_FIRST_CLASS_WORLD = "Hello, First " + Class.class.getSimpleName() + " World!"; // computed at runtime 
    @Constant public static final String HELLO_SECOND_CLASS_WORLD = "Hello, Second " + Class.class.getSimpleName() + " World!"; // initialized at build time
}
```
Since string is a `Constable` type this is a correct usage of `@Constant` on fields. If we mark the following field as constant
```java
@Constant public static final Set<String> models = new HashSet<?>();
```
this would be incorrect as `Set` is not an `@AllowInImageHeap`-annotated type. However, if we define a type annotated with `@AllowInImageHeap` 
```java
@AllowInImageHeap
class EconomicSet<@AllowInImageHeap T> {
    private T[] entries;
    // ...
}
```
we could use it for our constant set
```java
@Constant public static final EconomicSet<String> models = new EconomicSet<?>();
```

## Ensuring Correctness

To make the verification easier for end users, we would implement a `javac` plugin for verifying that `@Constant` and `@AllowInImageHeap` satisfy all the requirements. This plugin 
would be added automatically by the native build tools to all Java computations.

## Future Work

Allow non-final methods to be `@Constant`. The plugin can verify that all overrides of an `@Constant`-annotated are also `@Constant`.

Write a JEP to make `Method`, `Field`, a true constant and that they implement `java.lang.constant.Constable`. Setting access can be done through a separate API.

Library with heap friendly data structures: `EconomicMap`, `EconomicSet`, etc. that are all `@AllowInImageHeap`.

Introduce annotations to the JDK for reflective operations: `Class.forName`, `Method.getDeclaredConstructors`, etc.

Port the whole JDK to the new scheme so initialization at build time and external reachability metadata are not necessary.
