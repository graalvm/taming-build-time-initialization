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

Netty is currently initialized at build time. In the past this has caused many issues with cross-boundary initializations and initializing functionality at build time. To address this issue we [made a PR](https://github.com/vjovanov/netty/pull/2/files) to change default initialization of Netty to run time and the results were somewhat dissapointing: the Netty "Hello, World!" application grew from `16 MB` to `20 MB` in binary size. The extra necessary config grew by a large factor--most of the reflection configuration happens in static initializers.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Faster Startup via Heap Snapshotting

When a class is initialized at image build time its static fields are saved to the image heap in the generated executable. When the application starts up, this saved heap is mapped into memory.

#### Parse Configuration at Build Time

Heap snapshotting can be used to, for example, parse and load a configuration or static data at image build time.

In the [config-initialization](why-build-time-initialization/config-initialization) example, a big list of (fake!) employee accounts in the `JSON` format is parsed in the static initializer of `ConfigExample`. By initializing this class at build time, we avoid the overhead of parsing this configuration file at runtime.

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

The results are even better for Ruby where we have a reduction from `56 ms` to `14 ms` with the pre-initialized context. 

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

## Rules of Build-Time Initialization and Heap Snapshotting

### Types of Classes in GraalVM Native Image
In GraalVM Native Image there are three possible initialization states for each class: 
1. `BUILD_TIME` - marks that a class is initialized at build-time and all of static fields will be reachable saved in the image heap.
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
Some classes are always safe to be executed during the 

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Limitations of Heap Snapshotting
Every object can't be stored in the image heap. The major categories of objects are the ones that keep the state from the build machine: 
1. Objects containing build-system information, e.g., open files (`java.io.FileDescriptor`).  
2. Objects containing host VM data, e.g., running threads and continuations. 
3. Objects pointers to native memory (e.g., `java.nio.MappedByteBuffer`)
4. Known random seeds (impossible to prove no random seed ends up in the image)

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Properties for Run-Time Classes

All sub-classes of a run-time class (or interface with default methods) must also be a runtime class. Otherwise, initialization of that class would also initialize the run-time class. (Inverse rule from the rule of build-time initialization.) 

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
## Hidden Dangers of Class Initialization

### Security vulnerabilities: private cryptographic keys, random seeds, etc.

Storing security-sensitive information such as private keys or having a PRNG in static fields of classes initialized at build time is a recipe for trouble. The keys in such classes would remain in the image executable, readily discoverable by Eve. PRNGs in static fields initialized with a random seed during the image build would always use the same seed, leading to the exact same sequence of numbers being generated in every application run. 


<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Host Machine Data Leakage

Storing paths in static fields of classes initialized at build time can leak information about the machine used to build the image. A prime example of this is storing `System.getProperty("user.home")` in a static field. However, contents of any file or directory structure that is saved into the image heap can fall into this category.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>

### Correctness

#### Read a property from a host machine but use it in production.
   (VJ) (INet address static initializer)

#### Simple code changes can cause unintended and unknown correctnes problems
   (Example)
   
<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
   
### Backwards Compatibility

#### Causing a class that was intialized at run-time to become build-time is a backwards incompatible change
   (VJ) JSON Example

#### Unintended Chages in the Code Reachable from the Class Initializer
   (VJ)(algradin) example  
   
#### Explicit Changes in the Configuration
  (Netty)(VJ) History of a file in Netty

#### Crossing the Library Boundaries
Initializing classes at build time in one library can unintentionally ripple and wrongly initialize classes in a different library. The most widespread example of cross-library initialization victims are logging libraries. A very common pattern in Java is to have a static final logging field. These loggers are created through factories, sometimes allowing users to configure which logging library to use. Should such a class be initialized at build time, any of the supported logging libraries could be initialized at build time, depending on the configuration.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
### Initializing run-time classes at build time as a consequence of build-time initialization.

Parsing the configuration during build time comes with a major caveat: in the `config-initialization` example, the library used to parse the data, `jackson`, must not be referenced by any code at runtime. Doing so will result in class initialization configuration errors (classes from `jackson` that were supposed to be initialized at runtime got initialized at buildtime).
Another consequence is that the image would have to be rebuilt if the underlying data changes.

<br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/><br/>
### Image Bloating by Using Inadequate Data Structures

(gradinac) load a concurrent hash map and initialize at build time with a contents of an array of Strings as keys and Integers as values. 


## Build-Time Class Initialization Without Regret
### Inspecting the Results of Build-Time Initialization
  (vjovanov) -H:+PrintClassInitialization
### Rewrite the Code so Native Image can Prove Critical Classes
 (vojin) math example from the beginning. 
 
### Hand-Pick Classes Important for Build-Time Initialization

(vojin) text and explanation based on PlatformDependent0. 
(vojin) is in image code example
(algradinac) example with Logger and how to rewrite.
All of the system properties we expose. 
After the change, the code should have equivalent semantics as the original and 

## Debugging Class Initialization

(Algradin) --trace-class-init-test --trace-object-instantation-test
