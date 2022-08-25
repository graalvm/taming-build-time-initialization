# Proposal: handling of unreproducible runtime metadata exceptions in Native Image

## Description of the issue

Native Image, due to its closed-world assumption, currently simply ignores any reflective configuration element that results in an error at build-time. Any access to the problematic element at run-time is then handled by acting as if the element doesn't exist (through a `ClassNotFoundException`, `NoSuchMethodException` or similar).
This is of course not compliant with the Java specification, which expects the actual correct exception to be thrown at run-time. A significant number of use cases currently fail because of this issue. The wrong exception can be thrown on reflection calls, as well as resource handling and serialization calls.
As an example, the following snippet:


Main.java (compiled with Java 11)
```java
class Main {
  public static void main(String[] args) {
    Class.forName(args[0]);
  }
}
```

QueriedClass.java (compiled with Java 17)
```java
class QueriedClass {
}
```

is expected to throw an `UnsupportedClassVersionError` when run with `"QueriedClass"` as an argument. However, on Native Image the snippet results in a `ClassNotFoundException`. The image builder encounters the exception when registering the class for reflection, and simply ignores it.

Note: this issue has recently been fixed in Native Image, however it is both simple and symptomatic of the larger problem.

## Proposal

### New missing metadata exception

Elements that are queried at run-time without being registered in the metadata will now throw a Native Image-specific exception. This will ensure that a metadata issue does not get confused with a Java-level issue, like is the case today.
For example, a `ClassNotFoundException` being thrown at run-time can currently mean that either the class was indeed not on the classpath, or that it was simply omitted from the metadata. This will not be the case anymore, as a `ClassNotFoundException` will now only be thrown in cases where the JDK would also throw such an error.
There will be an exception for each concerned type of metadata, to enable differentiated catching of those exceptions. Thus, a `MissingReflectionMetadataException`, a `MissingResourceMetadataException` and a `MissingSerializationMetadataException` will be introduced.
Consequently, elements that should throw a correct exception at run-time should now be included in the corresponding metadata.
The Native Image agent will be modified to also register elements that cause an exception. This will enable the agent to not run every intercepted call a second time, which should improve agent run times.

### Missing metadata error messages

The new missing metadata exceptions will throw the corresponding error messages:

* **Missing reflection metadata:** `Method <qualified class name>#<methodName>(<signature>) has not been registered for reflection. To ensure this element is accessible at run-time, you need to add it to the reflection metadata.`
* **Missing resource metadata:** `Resource at path <resource path> has not been registered as reachable. To ensure this resource is available at run-time, you need to add it to the resource metadata.`
* **Missing serialization metadata:** `Class <qualified class name> has not been registered for serialization. To ensure this element is serializable at run-time, you need to add it to the serialization metadata.`

### Stack trace rewriting

The exception being rethrown at run-time will have a composite stack trace:
The part of the trace located before the problematic call will be the location where the exception happened at run-time. This ensures that users know where the exception happened, as is already the case;
The part of the trace located after the problematic call will be patched with the corresponding part of the stack trace of the exception caught at build-time. This ensures that users know why the exception happened, and is the main point of this proposal.
To ensure that the correct parts of the build-time stack trace are patched into the run-time stack trace, exceptions will be caught at build-time from the lowest possible levels, usually from JDK native methods. This also ensures that the image size impact of this proposal stays minimal.
In the case of our introductory example, the produced stack trace will look as follows:

```
Exception in thread "main" java.lang.UnsupportedClassVersionError: QueriedClass has been compiled by a more recent version of the Java Runtime (class file version 61.0), this version of the Java Runtime only recognizes class file versions up to 55.0
  at java.base/java.lang.ClassLoader.defineClass1(Native Method)                                              //  ^
  at java.base/java.lang.ClassLoader.defineClass(ClassLoader.java:1016)                                       //  |
  at java.base/java.security.SecureClassLoader.defineClass(SecureClassLoader.java:174)                        //  |
  at java.base/jdk.internal.loader.BuiltinClassLoader.defineClass(BuiltinClassLoader.java:800)                //  |
  at java.base/jdk.internal.loader.BuiltinClassLoader.findClassOnClassPathOrNull(BuiltinClassLoader.java:698) //  |
  at java.base/jdk.internal.loader.BuiltinClassLoader.loadClassOrNull(BuiltinClassLoader.java:621)            //  |
  at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:579)                  //  |
  at java.base/jdk.internal.loader.ClassLoaders$AppClassLoader.loadClass(ClassLoaders.java:178)               //  |
  at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:521)                                          // build-time
  at java.base/java.lang.Class.forName0(DynamicHub.java)                                                      // run-time
  at java.base/java.lang.Class.forName(Class.java:315)                                                        //  |
  at Main.main(Main.java:3)                                                                                   //  v
```
The part of the trace marked as `run-time` is the trace that would currently be part of the thrown exception, describing where the exception occurred.
The part of the trace marked as `build-time` is patched from the exception caught at build-time. This includes the exception type and its cause, which is itself not patched.

### Hard exit option

The option `ExitOnMissingMetadata` will be introduced to facilitate the debugging of code expecting the previous behavior in the case of missing metadata, and the discovery of metadata issues in code containing blanket `catch` blocks which may silence them.
This option will cause the program to exit without possibility of recovery instead of throwing one of the new missing metadata exceptions.
To facilitate diagnosis, the exception type, message, cause and stack trace will still be displayed before exiting.

## Implementation

### Build-time caching of exceptions

The problematic calls will be run at build-time on all registered reflection, resource and serialization metadata, and any unreproducible exception will be cached.
An unreproducible exception is an exception that cannot be reproduced at run-time by Native Image, such as a `ClassFormatError`, since the runtime has no concept of a class file.
Other exceptions, such as `IllegalAccessError`, do not suffer from this problem and as such, do not need to be cached. However, the problematic element will now be included in the image instead of being ignored by the Native Image builder.

### Reflection

An unreproducible error can occur during the following operations:

```
Class.forName0
Class.getDeclaringClass0
Class.getEnclosingMethod0
Class.getDeclaredFields0
Class.getDeclaredMethods0
Class.getDeclaredConstructors0
Class.getDeclaredClasses0
Class.getRecordComponents0 (JDK 17 and above)
Class.getPermittedSubclasses0 (JDK 17 and above)
```

`Class.forName0` is the only static method here. We will handle `Class.forName` errors by enhancing the currently used structure mapping class names to `DynamicHub` objects to be able to alternatively contain `Throwable` objects, which can then be thrown as needed at run-time.
All the other methods are instance methods whose results can be encoded in the existing reflection metadata byte arrays (most of them actually already are). We propose to enhance the current encoding to allow negative values for those results, which would mean that the call should throw instead of returning a result, and lookup the exception to throw in a new encoder, parallel to the existing class, string and object encoders.
This is possible for all methods, both those returning a single element (e.g. `getDeclaringClass0`) or an array of elements (e.g. `getEnclosingMethod0`), where the first element encoded is a class index, and the others returning an array, where the size of the array is encoded first. Both those values are expected to be positive integers, and therefore enable the use of the negative values for exceptions.
Care should be taken for the `NO_DATA` value, which is used to represent null values and is currently encoded as `-1`. Shifting the exception object index to avoid collisions with it should be enough to solve the problem.

### Resources

Querying resources can throw an `IOException` at build-time. This exception will be thrown at run-time when needed. This exception will be caught while calling `ClassLoader.get[System]Resource[s]` at build-time, and stored for `ResourcesHelper.nameToResource[s]` to re-throw when invoked with the matching name. We will use a simple map for that purpose, similar to the solution found for `Class.forName`.

### Serialization

Serialization exceptions can occur in three places: `SerializedLambda.readResolve`, `ObjectStreamClass.<init>` and `ReflectionFactory.newConstructorForSerialization`. In each case, the potential serialization-specific exceptions can be reproduced in Native Image, and don't require specific build-time support. The exceptions that require build-time support are reflection exceptions, which are already covered above.