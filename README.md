# Taming Build-Time Initalization in Native Image

## Why Build-Time Initialization?
### Better Peak Performance

By the semantics of the Java, access to classes, methods, or fields can cause class initialization. In just-in-time compilers (JIT) this doesn't introduce performance overheads: every class in the compiled code is initialized because the interpreter has already executed it. 

In ahead-of-time compilers such as GraalVM Native Image, class-initialization checks can not be removed as this would break Java semantics. For example, a simple sequence of field accesses will get translated into a check for class initialization and field access, i.e., 
```
Math.PI
```
will become
```
if (!Math.class.isInitialized) {
  initialize(Math.class)
}
Math.PI
```

The performance overhead of extra checks becomes particularly obvious in hot code (e.g., tight loops). If the class `Math` is initialized at build-time, the extra check is not necessary and the code will be as performant as when using the JIT compiler.

### Faster startup

Parse configuration as with Jackson JSON 

#### Context pre-initialization for JavaScript

## Rules of Build-Time Initialization

## Hidden Dangers of Class Initialization
### Security vulnerabilities: crypto keys, build environment, random seeds.
   (VJ)(Algradin) Examples. 
### Changing a class to run-time is a backwards incompatible change
   (VJ) History of a file in Netty
### Correctness: read a property from a host machine but use it in production. 
   (INet address)
### Cross-Library Boundaries
   (Netty Find a good issue or Spring)
### Initializing run-time classes at build time as a consequence of build-time initialization.
    JSON at build time problem from features and rerun.
