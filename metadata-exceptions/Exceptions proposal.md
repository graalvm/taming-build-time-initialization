# Proposal: Reporting of missing metadata in Native Image

## Description of the issue

Native Image is based on a closed-world assumption. This means that any element (`Class`, `Executable` or `Field`) accessed reflectively by the program has to be included in the reachability metadata ahead of time.
Native Image currently throws a `ClassNotFoundException`, `NoSuchMethodException` or similar when trying to query an element in the following cases:
* The element is included in the metadata and does indeed not exist. The error is correct in this case.
* The element is included in the metadata and caused a `LinkageError` at build time. The element was then dropped by Native Image, which means the `LinkageError` is lost and not thrown at run time. This is not compliant with the Java specification.
* The element is not included in the metadata and does indeed not exist. The error is also correct here, albeit only by chance, since Native Image considers any element absent from the metadata to not exist at run time.
* The element is not included in the metadata and either exists or would cause a run-time `LinkageError`. This is also not compliant with the Java specification.
The situation is similar with resource and serialization metadata.

This accumulation of very different situations under the same run-time error, two of which are not compliant with the Java specification and one of which is only by chance, are prone to create confusion.
Here is an example to illustrate the problem:

Main.java (compiled with Java 11)
```java
class Main {
  public static void main(String[] args) {
    try {
      Class.forName(args[0]);      
    } catch (LinkageError e) {
      Class.forName(args[1]);
    }
  }
}
```

QueriedClass.java (compiled with Java 17)
```java
class QueriedClass {
}
```

BackupClass.java (compiled with Java 11)
```java
class BackupClass {
}
```

On HotSpot, running `java Main "QueriedClass" "BackupClass"` results in an `UnsupportedClassVersionError` on the first `Class.forName` call, and the program then successfully queries `BackupClass` and exits.
After compiling the code with Native Image, running `main "QueriedClass" "BackupClass"` fails with a `ClassNotFoundException` on the first `Class.forName` call (either because `QueriedClass` is not included in the metadata or because it triggered the `UnsupportedClassVersionError` at build time and was therefore dropped from the image).
In this case, the Native Image behavior is not the same as the HotSpot behavior. It is also unclear, based on the thrown exception, whether `QueriedClass` indeed doesn't exist or was only omitted from the metadata.

*Note: the issue of incorrect exceptions being thrown by `Class.forName` has recently been patched in Native Image, however this example is both simple and symptomatic of the larger problem.*

## New missing metadata exception

Elements that are queried at run-time without being registered in the metadata will now throw a Native Image-specific exception. This will ensure that a metadata issue does not get confused with a Java-level issue, as is the case currently.
For example, a `ClassNotFoundException` will now only be thrown in cases where the JDK would also throw such an error.
There will be an exception for each concerned type of metadata, to enable differentiated catching of those exceptions. Thus, we will introduce a `MissingReflectionMetadataException`, a `MissingResourceMetadataException` and a `MissingSerializationMetadataException`.
In the example, omitting either `QueriedClass` or `BackupClass` from the metadata will now throw a `MissingReflectionMetadataException` when the class is queried, hinting at exactly what the problem is and how to fix it.

### Missing metadata error messages

The new missing metadata exceptions will throw the corresponding error messages:

* **Missing reflection metadata:** `Method <qualified class name>#<methodName>(<signature>) has not been registered for reflection. To ensure this element is accessible at run time, you need to add it to the reflection metadata.`
* **Missing resource metadata:** `Resource at path <resource path> has not been registered as reachable. To ensure this resource is available at run time, you need to add it to the resource metadata.`
* **Missing serialization metadata:** `Class <qualified class name> has not been registered for serialization. To ensure this element is serializable at run time, you need to add it to the serialization metadata.`

## Rethrowing of build-time linkage errors at run time

As part of our effort to be fully Java-compliant, reflection, resource and serialization queries will now throw the correct exception at run time, even when the element triggered an exception at build time (e.g. a `LinkageError`).
This behavior will only occur for elements present in the corresponding metadata. If that is not the case, Native Image will throw the appropriate `MissingMetadataException` instead.
This means that, assuming both `QueriedClass` and `BackupClass` are included in the reflection metadata, the introductory example will now have the same behavior on HotSpot and Native Image.
If the `--link-at-build-time` option is specified for the queried element, the `LinkageError` will instead be thrown immediately by the builder.

### Native Image agent modifications

The Native Image agent currently registers the arguments to reflection, resource or serialization queries only if these queries do not throw an exception.
In the example, the agent would currently only register `BackupClass` for reflection, since querying `QueriedClass` results in an `UnsupportedClassVersionError`.
The Native Image agent will be modified to also register elements that cause an exception when queried. As a result, no `MissingMetadataException` should be thrown when the agent is (correctly) used.
The agent currently has to run each intercepted call itself to check whether its execution triggers an exception, and drop the element from the metadata if it is the case. As every intercepted call will now create a metadata entry regardless of its result, this repeated call will not be necessary anymore, which should improve agent run times.

### Stack trace rewriting

The exception being rethrown at run time will have a composite stack trace:
The part of the trace located before the problematic call will be the location where the exception happened at run time. This is already the case, and ensures that users know **where** the exception happened;
The part of the trace located after the problematic call will be patched with the corresponding part of the stack trace of the exception caught at build time. This ensures that users know **why** the exception happened.
To ensure that the correct parts of the build-time stack trace are patched into the run-time stack trace, exceptions will be caught at build-time from the lowest possible level, usually from JDK native methods. This also ensures that the image size impact of this change stays minimal.
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
  at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:521)                                          // patched
  at java.base/java.lang.Class.forName0(DynamicHub.java)                                                      // base
  at java.base/java.lang.Class.forName(Class.java:315)                                                        //  |
  at Main.main(Main.java:3)                                                                                   //  v
```
The part of the trace marked as `base` is the trace that would currently be part of the thrown exception, describing where the exception occurred.
The part of the trace marked as `patched` is extracted from the exception caught at build time. This includes the exception type and its cause, which is itself not patched.

## Impact on existing applications

### Manual metadata

For projects using manual (or programmatically computed) metadata files, the presented changes mean that more elements need to be added to the metadata to ensure a correct execution.
While this one-time update is certainly time-consuming, we believe that the benefits of this approach in terms of understandability (any element queried should be included regardless of the result of that query) and precision (it eliminates ambiguous error reporting and accidental specification compliance) justify it.
In practical terms, it will be possible to simply follow the instructions given by `MissingMetadataException` error messages to patch the metadata.

### Agent-computed metadata

Projects using agent-computed metadata will not have to worry about changes to their metadata generation.
These projects may still encounter issues linked with the behavior changes described in this proposal. However, these changes are fixing cases where Native Image does not conform to Java specifications, and as such cannot be avoided. As a result, more programs will be able to work on both HotSpot and Native Image without modification.
The new missing metadata exceptions may hinder efforts to fix those issues. The next section introduces an option that will help separate metadata issues from wrong program behavior.

### Hard exit option

The current discrepancies between HotSpot and Native Image behaviors may have triggered users to modify the introductory example in the following way, replacing the specific `LinkageError` with a blanket `Throwable` catch to ensure both results are the same:

```java
class Main {
  public static void main(String[] args) {
    try {
      Class.forName(args[0]);      
    } catch (Throwable e) {
      Class.forName(args[1]);
    }
  }
}
```

The changes presented in this proposal make this change unnecessary, however this `Throwable` catch will now also catch any `MissingReflectionMetadataException` that is thrown if `QueriedClass` is absent from the metadata.
To enable debugging this type of problem, and to ensure that a metadata problem can always be distinguished from another exception, the option `--exit-on-missing-metadata` will cause the program to exit without possibility of recovery instead of throwing one of the new missing metadata exceptions.
This will facilitate the debugging of code expecting the previous behavior in the case of missing metadata, and the discovery of metadata issues in code containing blanket `catch` blocks which may silence them.
To facilitate diagnosis, the exception type, message, cause and stack trace will still be displayed before exiting.

## Implementation

### Build-time caching of exceptions

An unreproducible exception is an exception that cannot be reproduced at run time by Native Image, such as a `ClassFormatError`, since it has no concept of a class file.
The Native Image builder will run the problematic calls at build-time on all registered reflection, resource and serialization metadata, and cache any unreproducible exception.
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
All the other methods are instance methods whose results can be encoded in the existing reflection metadata byte arrays (most of them actually already are). We will enhance the current encoding to allow negative values for those results, which would mean that the call should throw instead of returning a result, and lookup the exception to throw in a new encoder, parallel to the existing class, string and object encoders.
This is possible for all methods, both those returning a single element (e.g. `getDeclaringClass0`) or a set number of elements (e.g. `getEnclosingMethod0`), where the first element encoded is a class index, and the others returning an array, where the size of the array is encoded first. Both those values are expected to be positive integers, and therefore enable the use of the negative values for exceptions.
Care should be taken for the `NO_DATA` value, which is used to represent null values and is currently encoded as `-1`. Shifting the exception object index to avoid collisions with it should be enough to solve the problem.

### Resources

Querying resources can throw an `IOException` at build-time. This exception will be thrown at run time when needed. This exception will be caught while calling `ClassLoader.get[System]Resource[s]` at build-time, and stored for `ResourcesHelper.nameToResource[s]` to re-throw when invoked with the matching name. We will use a simple map for that purpose, similar to the solution found for `Class.forName`.

### Serialization

Serialization exceptions can occur in three places: `SerializedLambda.readResolve`, `ObjectStreamClass.<init>` and `ReflectionFactory.newConstructorForSerialization`. In each case, the potential serialization-specific exceptions can be reproduced in Native Image, and don't require specific build-time support. The exceptions that require build-time support are reflection exceptions, which are already covered above.
