# Taming Build-Time Initalization in Native Image

## Why Build-Time Initialization?

### Better Peak Performance

By the semantics of the Java, access to classes, methods, or fields can cause class initialization. In just-in-time compilers (JIT) this doesn't introduce performance overheads: every class in the compiled code is initialized because the interpreter has already executed it.

In ahead-of-time compilers such as GraalVM Native Image, class-initialization checks can not be removed as this would break Java semantics. For example, a simple sequence of field accesses will get translated into a check for class initialization and field access, e.g.,
```
Math.PI
```
will become
```
if (!Math.class.isInitialized) { // hidden field in Native Image intrinsic
  initialize(Math.class)         // invocation of an intrinsic function
}
Math.PI
```

The performance overhead of extra checks becomes particularly obvious in hot code (e.g., tight loops). If the class `Math` is initialized at build-time, the extra check is not necessary and the code will be as performant as when using the JIT compiler.

The code example of a performance critical code where initialization is a problem can be found [here](why-build-time-initialization/hot-path-check).

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Smaller Binary and Less Configuration

Class initialzers can pull a lot of unnecessary code into the resulting native-image although they would be functional otherwise. The good example is [netty](https://github.com/netty/netty) where [certain classes](https://github.com/netty/netty/blob/4.1/buffer/src/main/java/io/netty/buffer/AbstractByteBufAllocator.java#L36) traverse all methods to just reach a single declaration and store it into the image.

Netty is currently initialized at *build time*. In the past this has caused many issues with cross-boundary initializations and initializing functionality at build time. To address this issue we [made a PR](https://github.com/vjovanov/netty/pull/2/files) to change default initialization of Netty to run time and the results were somewhat dissapointing: the Netty `"Hello, World!"` application grew from `16 MB` to `20 MB` in binary size. The extra necessary config grew by more than *5x*--most of the reflection configuration happens in static initializers.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Faster Startup via Heap Snapshotting

When a class is initialized at image build time its static fields are saved to the image heap in the generated executable. When the application starts up, this saved heap is mapped into memory.

#### Parse Configuration at Build Time

Heap snapshotting can be used to, for example, parse and load a configuration or static data at image build time.

In the [config-initialization](why-build-time-initialization/config-initialization) example, a big list of (fake!) employee accounts in the `JSON` format is parsed in the static initializer of `ConfigExample`. By initializing this class at build time, we avoid the overhead of parsing this configuration file at runtime.

Data in this sample was generated using https://www.json-generator.com/.

#### Context pre-initialization for GraalVM Languages

Another good place to use heap snapshotting is pre-initialization of language contexts. For example, in GraalVM JS the frist context is initialized and stored into the javascript image. This makes the `"Hello, World!"` in JS more than 55% less expensive. With context pre-intialized we have `5,367,730` instructions executed
```
$ valgrind --tool=callgrind ../jre/bin/js -e 'print("Hello, World!")'
...
==1729206==
==1729206== I   refs:      5,367,730
```

while without the context stored in the image we have `12,101,651`

```
$ valgrind --tool=callgrind ../jre/bin/js-no-context -e 'print("Hello, World!")'
...
==1729206==
==1729206== I   refs:      12,101,651
```

The results are even better for Ruby where we have a reduction from `56 ms` to `14 ms` with the pre-initialized context.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

## Rules of Build-Time Initialization and Heap Snapshotting

### Types of Classes in GraalVM Native Image
In GraalVM Native Image there are three possible initialization states for each class:
1. `BUILD_TIME` - marks that a class is initialized at build-time and all of static fields that are reachable are saved in the image heap.
2. `RUN_TIME`   - marks that a class is initialized at run-time and all static fields and the class initializer will be evaluted at run time.
3. `RERUN`      - internal state that means `BUILD_TIME` by accident. Static fields and class initializers will be evaluated at run time.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Properties of Build-Time Initialized Classes

1. All classes stored in the image heap must be initialized at build time. This is necessary as accessing an object through a virtual method could execute code in that object doesn't have consistent state--static initializer has not been executed.
2. All super classes, and super interfaces with default methods, of a build-time class must be build-time as well.
3. Code reached through the class initializer of a build time class, must be either marked as `BUILD_TIME` or `RERUN`. In the example of [JSON parsing at build time](https://github.com/vjovanov/taming-build-time-initalization/blob/main/why-build-time-initialization/config-initialization/src/main/java/org/graalvm/ConfigExample.java#L20), most of the `jackson` library is initialized at build time.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Proving a Class is Build-Time Initialized

The default for GraalVM Native Image is that classes are initialized at run time. However, for performance reasons, Native Image will prove certain classes safe to initialize and will still initialize them.

#### Proving Safe Initialization During Analysis and after Analysis
GraalVM Native Image can prove classes safe in two places:
1. During Analysis - all of the static fields will be folded during analysis and the resulting image size can be smaller. These proofs work on simple class initializers without recursion or cyclic dependencies.
2. After Analysis - the fields will not have an effect on static analysis.

The best place to see the types of classes that can be proven early is the [test for class initialization](https://github.com/oracle/graal/blob/master/substratevm/src/com.oracle.svm.test/src/com/oracle/svm/test/TestClassInitializationMustBeSafe.java).

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Limitations of Heap Snapshotting
Every object can't be stored in the image heap. The major categories of objects are the ones that keep the state from the build machine:
1. Objects containing build-system information, e.g., open files (`java.io.FileDescriptor`).
2. Objects containing host VM data, e.g., running threads and continuations.
3. Objects pointers to native memory (e.g., `java.nio.MappedByteBuffer`)
4. Known random seeds (impossible to prove no random seed ends up in the image)

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Properties for Run-Time Classes

1. All sub-classes of a run-time class (or interface with default methods) must also be a runtime class. Otherwise, initialization of that class would also initialize the run-time class. (Inverse rule from the rule of build-time initialization.)

2. Run-Time initialized classes must not end up in the image heap.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
## Hidden Dangers of Class Initialization

### Security Vulnerabilities: Cryptographic Keys, Random Seeds, etc.

Storing security-sensitive information such as private keys or having a PRNG in static fields of classes initialized at build time is a recipe for trouble. The keys in such classes would remain in the image executable, readily discoverable by Eve. PRNGs in static fields initialized with a random seed during the image build would always use the same seed, leading to the exact same sequence of numbers being generated in every application run.

In the [security-problems](hidden-build-time-initialization-dangers/security-problems) example, the following problematic fields are initialized at build time:
```java
public class SecurityProblems {
   ...
   // Will "bake" the private key found during the image build into the image!
   private static final PrivateKey runtimeSuppliedPrivateKey = loadPrivateKey();

   // Will always contain the same random seed at image runtime!
   private static final SimplePRNG randomNumberGenerator = new SimplePRNG(System.currentTimeMillis());
   ...
}
```

The bytes of the private key will be embedded in the image heap, and while it may take a bit of time to analyze the executable, it is possible to retrieve and compromise it. The simple random number generator will be initialized with a random seed (never use the current time as the random seed in a real app!) at image build time. This seed will not change between subsequent runs:
```bash
$ ./target/org.graalvm.securityproblems
An entirely random sequence: 2843 5686 3435
$ ./target/org.graalvm.securityproblems
An entirely random sequence: 2843 5686 3435
```

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Host Machine Data Leakage

Storing paths in static fields of classes initialized at build time can leak information about the machine used to build the image. A prime example of this is storing `System.getProperty("user.home")` in a static field. However, contents of any file or directory structure that is saved into the image heap can fall into this category.

In the [security-problems](hidden-build-time-initialization-dangers/security-problems) example, the following problematic field is initialized at image build time:
```java
public class SecurityProblems {
   ...
    // Will leak the user's home directory of the user building the image!
    private static final String USER_HOME = System.getProperty("user.home");
    ...
}
```
Regardless of where the final image is executed, `USER_HOME` will always contain the `user.home` path on the original machined used to build the image. A basic check for these directories in the image heap is provided and can be enabled with `-H:+DetectUserDirectoriesInImageHeap`.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Correctness

#### Read a Property from the Build Machine and Always use it in Production

Let us look at [INetAddress](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/net/InetAddress.java#L307) where the IP preference is determined in the class initializer:
```java
  static {
     String str = java.security.AccessController.doPrivileged(
                new GetPropertyAction("java.net.preferIPv6Addresses"));
        if (str == null) {
            preferIPv6Address = PREFER_IPV4_VALUE;
        } else if (str.equalsIgnoreCase("true")) {
            preferIPv6Address = PREFER_IPV6_VALUE;
        } else if (str.equalsIgnoreCase("false")) {
        ...
```

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

#### Simple Code Changes can Cause Unintended and Unknown Correctness Problems
   If anywhere in the code that is reachable from static initializers we introduce reading a system property.
   
   The writer of the code can't know if the property will be used in the static initializer. For example, the writer of [ReadPropertyHolder](why-build-time-initialization/config-initialization/src/main/java/org/graalvm/ReadPropertyHolder.java) does not know who could use this class in build-time initialization. 
   
   This especially doesn't play well when initialization is crossing the library boundaries.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

#### Crossing the Library Boundaries

Initializing classes at build time in one library can unintentionally ripple and wrongly initialize classes in a different library. The most widespread example of cross-library initialization victims are logging libraries.

Most Java frameworks have the following structure:
```java
public class MyBuildTimeInitClass {
   ...
   private static final Logger logger = MyFrameworkLogFactory.getLogger(MyBuildTimeInitClass.class);
   ...
}
```

If the underlying logging library is configurable by the user, buildtime initialization of the above class would wrongly initialize any of the selected logging library classes at build time.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
### Code Compatibility

#### Making a class intialized at Run Time Stored in the Image Heap
This can happen accross the library boundaries through values returned by regular functions.

#### Changing a Class from Build Time to Run Time is a Backwards Incompatible Change

1. Explicit changes in the configuration. See, for example, the [changes in Netty](https://github.com/netty/netty/blob/4.1/common/src/main/resources/META-INF/native-image/io.netty/common/native-image.properties) that occured over time. Each was a breaking change for the rest of the community.
2. Adding code that can't be initialized at build-time anymore: e.g. dissallowed heap objects to build-time classes.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
#### Explicit Changes in the Configuration
  Marking something as initialized at build-time is one-way change.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
### Initializing Run-Time Classes Unintentionally as a Consequence of Build-Time Initialization.

Parsing the configuration during build time comes with a major caveat: in the [config-initialization](why-build-time-initialization/config-initialization) example, the library used to parse the data, `jackson`, must not be referenced by any code at runtime. Doing so will result in class initialization configuration errors (classes from `jackson` that were supposed to be initialized at runtime got initialized at buildtime).
Another consequence is that the image would have to be rebuilt if the underlying data changes.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
### Image Bloating by Using Inadequate Data Structures

In the [config-initialization](why-build-time-initialization/config-initialization) example, the collections holding the parsed data will be written to the image heap in the executable. Such collections will introduce size overhead:
 - Size of the image with parsing the config at buildtime:    58 MB
 - Size of the image without parsing the config at buildtime: 30 MB
 - Size of the data file:                                     15 MB
-------------------------------------------------------------------
 - Total overhead:                                            13 MB

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
## Build-Time Class Initialization Without Regret

### Inspecting the Results of Build-Time Initialization
  To see how and where a class got initialized we introduce a flag `-H:+PrintClassInitialization`. This flag will output for each class where the decision is coming from and why it got initialized. An example of the output is a CSV file show when classes were proven: 
  ```
 Class Name, Initialization Kind, Reason for Initialization
boolean, BUILD_TIME, primitive types are initialized at build time
boolean[], BUILD_TIME, arrays are initialized at build time
...
com.oracle.graal.compiler.enterprise.BulkAllocationSnippetTemplates, BUILD_TIME, Native Image classes are always initialized at build time
...
com.oracle.svm.core.heap.Target_jdk_internal_ref_SoftCleanable, BUILD_TIME, substitutions are always initialized at build time
com.oracle.svm.core.heap.Target_jdk_internal_ref_WeakCleanable, BUILD_TIME, substitutions are always initialized at build time
...
io.netty.bootstrap.AbstractBootstrap, BUILD_TIME, from jar:file:///<path>/substratevm-netty-hello-world-1.0.0-SNAPSHOT.jar!/META-INF/native-image/io.netty/common/native-image.properties (with 'io.netty.util.AbstractReferenceCounted') and from jar:file:///<path>/substratevm-netty-hello-world-1.0.0-SNAPSHOT.jar!/META-INF/native-image/io.netty/codec-http/native-image.properties (with 'io.netty')
...
sun.util.calendar.ZoneInfoFile$Checksum, RERUN, from feature com.oracle.svm.core.jdk.LocalizationFeature.addBundleToCache with 'class sun.util.resources.cldr.CalendarData'
  ```
  
### Rewrite the Code so Native Image can Prove Critical Classes
 
 For this we will use our example with the inverse square root decision made by the property. With a few slight changes we will make it possible to make the `SlowMath` class fast.
 
### Hand-Pick Classes Important for Build-Time Initialization

Sometimes proofs are impossible (e.g., Netty [PlatformDependent0](https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/internal/PlatformDependent0.java#L77)) but we still need to initialize this class at build time. 

The soultion is simple, re-write the code of the class so it can be initialized at build-time. For that we can use the system properties injected by GraalVM Native Image. In the [avoiding-library-initialization](build-time-initialization-without-regret/avoiding-library-initialization) example, `AvoidingLibraryInitialization` could be initialized at build-time if it did not have a static logger.

To work around this, we refactor the logger creation to a utility method:
```java
    private static Logger getLogger() {
        if ("buildtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return NOPLogger.NOP_LOGGER;
        } else {
            return LoggerFactory.getLogger(AvoidingLibraryInitialization.class);
        }
    }
```

During image build-time, calls to `getLogger` will return a no-op logger and avoid initializing (and subesequently, configuring) logging at build-time. Native-image exposes the `org.graalvm.nativeimage.imagecode` system property that can contain:
 - `null`: code is executing on regular Java
 - `buildtime`: code is executing in the image builder
 - `runtime`: code is executing in the image, at runtime

In the example, logging is configured using `logback.xml`. Initializing the logger at build-time would also unintentionally initialize XML parsing at build-time, creating an issue if XML is used elsewhere in the code.

## Debugging Class Initialization

(Algradin) --trace-class-init-test --trace-object-instantation-test
