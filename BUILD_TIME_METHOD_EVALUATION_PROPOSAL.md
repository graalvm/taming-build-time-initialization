# Computing (Reachability) Metadata in User Code

This document describes a mechanism for computing [reachability metadata](https://www.graalvm.org/22.2/reference-manual/native-image/metadata/) in user code at build time.
We focus on computing reachability metadata in the user code as it is easier to maintain compared to externally specified metadata (e.g., in JSON files).
Nonetheless, this mechanism can be used for precomputing arbitrary complex data structures such as ML-models and parsed configuration files.  

Before we continue, we state the following requirement that we call *safe composition*: Syntactically and semantically correct changes to a method's body must not break compilation or execution of any program that is using that method.

Currently, there are three ways to compute reachability metadata in user code:

1. Intrinsics for `java.lang.Class#forName` and methods such as `Class#getMethod`, and similar. Given constant arguments method invocations will be computed in user code at build time. For example, `Class.forName("Foo")` will be computed at build time and, if class `Foo` is valid, it will be marked as reachable.  

2. Inlining before analysis. This is the mechanism for inlining multiple levels of methods that the compiler can reduce to a constant.
The problem with this approach is that it is hard to specify what a compiler can reduce to a constant. Because of that safe composition is not possible. For example, adding a logging statement to the following function breaks any usage of it at runtime (without externally specified metadata): 
```java
public static Class<?> forSimpleName(String className) throws ClassNotFoundException {
    System.out.println("Class.forName call on org.graalvm.example.safecomp." + className); // prevents inlining and computation of reachability metadata
    return Class.forName("org.graalvm.example.safecomp." + className);
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

Note that some programs are dynamic in nature and the reachability metadata can't be computed at build time (see implementations of `java.util.Collections.CheckedCollection.checkedCopyOf`).

## Types and Terms for Precomputing Metadata

To allow precomputing metadata and making it available at runtime, it is necessary to introduce a restriction on types that can be precomputed. We introduce the following type definitions:
1. *Immutable* types are all primitive types, boxed primitive types, `java.lang.String`, and `java.lang.Class`.
2. *Effectively immutable* types are immutable types and `java.lang.reflect.Method` and `java.lang.reflect.Field`.
3. *Pure* types are all effectively immutable types, final classes annotated with `@Pure`, generic types annotated with `@Pure` and arrays of such types.

The `@Pure` annotation is defined as follows:
```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.TYPE_PARAMETER})
public @interface Pure {
}
```

Classes annotated with `@Pure` must follow these restrictions:
1. They do not have a static initializer.
2. All supertypes must be annotated with `@Pure`.
3. All fields of `@Pure` classes must be of pure type.

Note that `java.lang.Object` must be annotated with `@Pure`.

We define a *precomputed term* as one of the following:
1. A primitive literal or boxed primitive. For example, `1`, `new Integer(1)`, `1.0d`, etc. 
2. String literals, e.g., `"s-literal"`.
3. Class literals, e.g., `String.class`.
4. A `static final` field annotated with `@Precompute`.
5. A `static final` field of a class that is initialized at build time with `--initialize-at-build-time`. 
6. A function call to a `@Precompute`-annotated method that accepts only precomputed terms as arguments.
7. An `Object#getClass()` call on a term whose type is known at build time. For example, `new Exception("Hi").getClass()` is a precomputed term.
8. An array constructor whose inputs are only precoputed terms.
9. A `final` variable that is assigned one of the above terms and can not be re-assigned before usage.

## Storing (Reachability) Metadata in Data Structures

We propose an annotation that can be used on fields:

```java
import java.lang.annotation.ElementType;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Precompute {
}
```
 
When a field is annotated with `@Precompute` the following must apply:
1) The field must be `static final`.
2) The field must be of pure type.

The precomputed field is computed by analysing the dataflow of the `<clinit>` method and extracting the code required to execute for the computation of such field in a separate methods. 
These methods would be then executed at link-time and the result would be stored in the field. The transformation would be done by a bytecode-to-bytecode transformation.

An example of a `@Precompute`-e annotated fields 
```java
class Greeter {
    public static final String HELLO_FIRST_CLASS_WORLD = "Hello, First " + Class.class.getSimpleName() + " World!"; // computed at runtime 
    @Precompute public static final String HELLO_SECOND_CLASS_WORLD = "Hello, Second " + Class.class.getSimpleName() + " World!"; // computed at link time
}
```
Since `String` is an immutable type this is a correct usage of `@Precompute` on fields. 

However, if we mark the following field as `@Precompute`
```java
@Precompute public static final Set<String> models = new HashSet<>();
```
this would be incorrect as `Set` is not a final `@Pure`-annotated type. To correct that we must define a data structure annotated with `@Pure`:
```java
@Pure
final class ImageSet<@Pure T> implements Set<T> {
    private T[] entries;
    // ...
}
```
This structure can now be used for the precomputed set as follows:
```java
@Precompute public static final ImageSet<String> models = new ImageSet<>();
```
Program that uses the set with a type that is not pure would be again incorrect. For example,
```java
@Precompute public static final ImageSet<Object> models = new ImageSet<>();
```

Removing `@Precompute` from a field or `@Pure` from a type is considered an API-breaking change.

## Computing Reachability Metadata in Method Bodies

Methods annotated with `@Precompute` are valid if and only if:
1. The method return type is effectively immutable.
2. The method is marked with `static`, or `final`, or it is a constructor of a `@Pure`-annotated record.

A method annotated with `@Precompute` will be computed at link time and the result stored in the method body when all of its arguments (including the receiver) are precomputed terms.

In case a `@Precompute`-annotated method throws an exception at build time, the invocation is replaced with a `throw` statement that will rethrow the same exception at runtime.

An example of a `@Precompute`-annotated method that creates a new class whose `toString` returns a `message`:
```java
class ClassCreator {
    @Precompute
    public static Class<?> createClass(String message) {
        return new ByteBuddy().subclass(Object.class)
                .method(ElementMatchers.named("toString")).intercept(FixedValue.value(message)).make()
                .load(ByteBuddyClassCreator.class.getClassLoader()).getLoaded();
    }
}
```

A call to this method with a constant argument generates a class at build-time and the program executes successfully.
The generated class is included in the image without externally provided metadata: 
```java
class Greeter {
    public static final String HELLO_FIRST_CLASS_WORLD = "Hello, First " + Class.class.getSimpleName() + " World!"; // computed at runtime    
    @Precompute public static final String HELLO_SECOND_CLASS_WORLD = "Hello, Second " + Class.class.getSimpleName() + " World!";

    public void printGreetings() throws InstantiationException, IllegalAccessException, InvocationTargetException, NoSuchMethodException {
        System.out.println(ClassCreator.createByteBuddyClass("Hello, First Class World!").getConstructor().newInstance()); // can't use HELLO_FIRST_CLASS_WORLD 
        System.out.println(ClassCreator.createByteBuddyClass(HELLO_SECOND_CLASS_WORLD).getConstructor().newInstance());
    }
}
```

Removal of the `@Precompute` annotation is considered a breaking change for an API.

Some methods and constructs from the JDK should be annotated with `@Precompute`. The most important are:
1. All operators on immutable types.
2. Methods on constructs from reflection such as `java.lang.Class#forName` and methods on `java.lang.reflect.Field` and `java.lang.reflect.Method`.

## Computing Reachability Metadata in Complex Methods

In the previous example, we had to duplicate the calls to `.getConstructor().newInstance()` as it is not possible to make abstractions over `@Precompute`-annotated methods. In this case, the `@Precompute`-annotated method can not return an instance of a generated class as it does not follow the return-type restrictions. To allow arbitrary abstractions over `@Precompute`-annotated methods we propose the following annotation:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.METHOD})
public @interface Propagate {
}
```

This annotation can be placed only on method parameters and on a non-static method. A call to `@Propagate`-annotated method `m`:
```java
final R m(@Propagate T1 p1, T2 p2, @Propagate T3 p3) {
    // body of m
}

m(a1, a2, a3)
```
is transformed as follows
```java
final R m'(T2 p2) {
        var p1 = a1;
        var p3 = a3;
    // body of m
}
m'(a2)
```

Parameters that are `@Propagate`-annotated in a method of the super type must also be annotated in the overridden method of a subtype. And in case of virtual calls the transformation 
will be applied to all overridden methods of the method that has `@Propagate`-annotated parameters. The callee will still be determined at runtime. 

The transformation allows that:
1. Usages of a precomputed parameter in `@Precompute`-annotated methods will be accounted as precomputed where the value is computed at the call site. 
2. Usages of precomputed `@Precompute`-annotated argument will be further propagated to `@Propagate`-annotated method parameters.  

If a method has multiple `@Propagate`-annotated parameters each will be treated separately, i.e., any subset of them can be precomputed for the transformation to apply.

If there is a recursive call that receives `@Propagate`-annotated parameters, the transformation is halted.

For the previous example we can now create abstractions that contain code that will not be executed at link time:
```java
class Greeter {
    @Precompute public static final String HELLO_SECOND_CLASS_WORLD = "Hello, Second " + Class.class.getSimpleName() + " World!"; // initialized at build time

    private String createLogAndInstantiate(@Propagate String message) {
        log("Instantiating class for message: " + message); // not computed at build-time
        return createAndInstantiate(message).toString();
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

### `@Propagate` with Terms of Exact Types

Propagate can be used for calls where the exact value of an argument is not known, but the type is determined.
To demonstrate this we can use an example from Mockito for spying on objects:
```java
 public static <T> T spy(@Propagate T object) {
        return CORE.mock((Class<T>) object.getClass(), withSettings().spiedInstance(object).defaultAnswer(CALLS_REAL_METHODS));
}
```

Now if we use this object as follows:
```java
List list = new LinkedList();
List spy = spy(list);
```

the method `CORE.mock` will be able to generate the wrapper class for `LinkedList` as the term `new LinkedList().getClass()` that is generated after propagation of object is considered as a precomputed term with the value `LinkedList.class`.

### Implementation of `@Propagate`

To prevent code explosion during inline expansion, behind the scenes the method `Greeter.createAndInstantiate(String)` would be transformed into:
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

Note that the final implementation would be slightly more complicated as `@Precompute`-annotated methods can have multiple parameters. 
For those we would need to do equality on a group constants that belongs to the same "invocation".

Performance of these methods should not be affected as hot methods are usually inlined and after inlining 
all of newly introduced conditionals are trivially optimized away.

## Modifications to the Current Native Image Build-Time Computation

The new proposal would leave the functionality of `--initialize-at-build-time` in Native Image. 
This feature should be used for legacy libraries and libraries that should do not want to adopt the change for the foreseeable future.

The inlining before analysis would remain as is, however it would not be allowed to constant-fold reflective calls for purposes of computing reachability metadata. 
All the metadata computation must be complete before inlining before analysis is done.

All intrinsics for `Class#getField`, `Class#getMethod`, etc., would be implemented in terms of `@Propagate` and `@Precompute`.

## Ensuring Correctness During Compilation 

To make the verification easier for end users, we would implement a `javac` plugin for verifying that `@Precompute` and `@Pure` satisfy all the requirements. This plugin 
would be added automatically by the native build tools to all Java computations.

## Future Work

Allow non-final methods to be `@Precompute`. The plugin can verify that all overrides of an `@Precompute`-annotated are also `@Precompute`.

Try and make `Method`, `Field`, a true immutable type. Enabling or disabling access can be done through an API on another class.

Library with heap friendly data structures: `EconomicMap`, `EconomicSet`, etc. that are all `@Pure`.

Introduce annotations to the JDK for reflective operations: `Class.forName`, `Class.getMethod()`, etc.

Port the JDK to the new scheme so initialization at build time and external reachability metadata are not necessary at all.
