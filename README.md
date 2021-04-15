# Taming Build-Time Initalization in Native Image

## How to run the examples in this repo

Make sure to install `maven`, GraalVM and native-image and to put native-image on the PATH.
Each example can then be compiled using `mvn clean package`. Some examples can also be tweaked and recompiled to show different scenarios.

## Why Build-Time Initialization?

### Better Peak Performance

By the semantics of the Java, access to classes, methods, or fields can cause class initialization. In just-in-time compilers (JIT) this doesn't introduce performance overheads: every class in the compiled code is initialized because the interpreter has already executed it.

In ahead-of-time compilers such as GraalVM Native Image, class-initialization checks can not be removed as this would break Java semantics. For example, a simple sequence of field accesses will get translated into a check for class initialization and field access, i.e.,
```
Math.PI
```
will become
```
if (!Math.class.isInitialized) { // Native Image intrinsic
  initialize(Math.class)         // invocation to an intrinsic function
}
Math.PI
```

The performance overhead of extra checks becomes particularly obvious in hot code (e.g., tight loops). If the class `Math` is initialized at build-time, the extra check is not necessary and the code will be as performant as when using the JIT compiler.

The code example of a performance critical code where initialization is a problem can be found [here](why-build-time-initialization/hot-path-check).

### Smaller Binary and Less Configuration

Class initialzers can pull a lot of unnecessary code into the resulting native-image although they would be functional otherwise. The good example is [netty](https://github.com/netty/netty) where [certain classes](https://github.com/netty/netty/blob/4.1/buffer/src/main/java/io/netty/buffer/AbstractByteBufAllocator.java#L36) traverse all methods to just reach a single declaration and store it into the image.

Netty is currently initialized at build time. In the past this has caused many issues with cross-boundary initializations and initializing functionality at build time. To address this issue we [made a PR](https://github.com/vjovanov/netty/pull/2/files) to change default initialization of Netty to run time and the results were somewhat dissapointing: the Netty "Hello, World!" application grew from `16 MB` to `20 MB` in binary size. The extra necessary config grew by a large factor--most of the reflection configuration happens in static initializers.

### Faster Startup via Heap Snapshotting

When a class is initialized at image build time its static fields are saved to the image heap in the generated executable. When the application starts up, this saved heap is mapped into memory. This can be (ab)used to, for example, parse and load a configuration or static data at image build time.
In the `config-initialization` example, a big list of (fake!) employee accounts in the `JSON` format is parsed in the static initializer of `ConfigExample`. By initializing this class at build time, we avoid the overhead of parsing this configuration file at runtime.

Data in this sample was generated using https://www.json-generator.com/.

#### Context pre-initialization for GraalVM Languages

Another good place to use heap snapshotting is pre-initialization of language contexts. For example, in GraalVM JS the frist context is initialized and stored into the javascript image. This makes the "Hello, World!" in JS more than 55% less expensive. With context pre-intialized we have `5,367,730` instructions executed
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

The results are even better for Ruby where we have a reduction from 56 ms to 14 ms with the pre-initialized context. 

## Rules of Build-Time Initialization

### Types of Classes in GraalVM Native Image

### All Classes of Objects Stored in the Image Heap Must be Build-Time Initialized

### Properties of Build-Time Classes


## Hidden Dangers of Class Initialization

### Security vulnerabilities: private cryptographic keys, random seeds, etc.

Storing security-sensitive information such as private keys or having a PRNG in static fields of classes initialized at build time is a recipe for trouble. The keys in such classes would remain in the image executable, readily discoverable by Eve. PRNGs in static fields initialized with a random seed during the image build would always use the same seed, leading to the exact same sequence of numbers being generated in every application run.

#### Read a property from a host machine but use it in production.
   (INet address)
   
### Host Machine Data Leakage

Storing paths in static fields of classes initialized at build time can leak information about the machine used to build the image. A prime example of this is storing `System.getProperty("user.home")` in a static field. However, contents of any file or directory structure that is saved into the image heap can fall into this category.

### Correctness:

#### Regular code changes can cause unintended and unknown correctnes problems

### Causing a class that was intialized at run-time to become build-time is a backwards incompatible change
#### Explicit changes in the config
  (Netty)(VJ) History of a file in Netty

#### Unintended Chages in the Code Reachable from the Class Initializer

### Storing Caches Accidentaly in the Image

### Cross-Library Boundaries

Initializing classes at build time in one library can unintentionally ripple and wrongly initialize classes in a different library. The most widespread example of cross-library initialization victims are logging libraries. A very common pattern in Java is to have a static final logging field. These loggers are created through factories, sometimes allowing users to configure which logging library to use. Should such a class be initialized at build time, any of the supported logging libraries could be initialized at build time, depending on the configuration.

### Initializing run-time classes at build time as a consequence of build-time initialization.

Parsing the configuration during build time comes with a major caveat: in the `config-initialization` example, the library used to parse the data, `jackson`, must not be referenced by any code at runtime. Doing so will result in class initialization configuration errors (classes from `jackson` that were supposed to be initialized at runtime got initialized at buildtime).
Another consequence is that the image would have to be rebuilt if the underlying data changes.

### Image Bloating by Using Inadequate Data Structures

## Build-Time Class Initialization Without Regret

### Rewrite the Code so Native Image can Prove Critical Classes

### Hand-Pick Classes Important for Build-Time Initialization

## Debugging Class Initialization

