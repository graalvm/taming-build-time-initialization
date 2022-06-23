# Proposal: New Approach for Class Initialization at Build Time vs. Run Time

# Current system
- The user can configure whether a class should be initialized at image build time or at image run time. Various equivalent ways for this exist: the `--initialize-at-build-time` option, the `org.graalvm.nativeimage.hosted.RuntimeClassInitialization` class then can be called from a Feature, ...
- If a class that is not explicitly marked for initialization at build time gets initialized by accident, the image build is aborted and an error is reported to the user.
- A special mode to "re-run initialization at run time" exists. Such classes are then initialized at image build time, and again at image run time (so the class initializer runs twice). This mode is not exposed in the supported public API. For a good reason: it can lead to strange side effects when a class initializer has a side effect on static fields of another class.
- Classes whose class initializer can be proven to not have side effects are automatically initialized at image build time. This is a performance optimization: it safes the initialization at run time and the initialization checks before all static member accesses for such classes. The first round of such initializations happens before static analysis. Another round happens after static analysis, when more virtual method calls can already be de-virtualized and therefore be proven allowed.
- When a class is initialized at image build time, the class initializer is executed by the Java HotSpot VM that runs the image builder. All object allocations, field modifications, ... directly happen in the HotSpot heap.

# Problems with the current system
- Debugging why a class marked for run-time initialization was initialized at image build time is quite difficult for users, even with the additional diagnostic options that we provide that try to collect a stack trace that triggered the initialization.
- Running initialization code at image build time is harder than it should be. We want people to, e.g., load configuration files at image build time. But that requires that the whole configuration file parser (like a JSON or YAML parser) is marked for initialization at build time. It is unrealistic to audit large existing code bases that they are unconditionally safe for such initialization at build time.
- Automatically initializing classes at image build time is not 100% safe. We analyze the Graal IR that we would put into the image - but due to intrinsifications, that can be different code than what really gets executed when the class initializer runs in the image generator.
- Some frameworks like Quarkus opt to initialize all classes at image build time. That is highly unsafe, but for them the only realistic option right now. For example, when Quarkus bootstraps Hibernate to find out which parts of Hibernate are necessary at image run time, most classes of Hibernate need to be initialized. Even though the outcome of this bootstrap is just a limited set of configuration objects.
- Some frameworks like Spring do not want to initialize classes at image build time at all because of the dangers of unintended side effects. For example configuration of logging code.
- For frameworks like Netty, we tried to push for "initialize as much as possible at image build time" using explicit configurations in Netty. That did not work out overly well, there were always unintended side effects like buffers being sized based on the Java heap size of the image generator.

# Proposed new architecture
- All classes are allowed to be used (and therefore be initialized) in the image generator, regardless of the class initialization configuration.
- For classes that are configured as "initialize at build time", the static fields of the image generator are preserved at image run time.
- Classes that are configured as "initialize at run time", the class appears as uninitialized again at run time. In particular if the class was already initialized at build time, static fields of the image generator are not preserved at image run time. This is equivalent to the current "re-run initialization at run time".
- Classes whose class initializer can be proven to not have side effects can no longer be "just initialized by the image builder VM", because we now need to distinguish the build-time initialization state used by build-time code from the "clean state" produced by just running the class initializer. Any other code that runs at image build time (outside of the "proven class initializer") can modify static fields of the proven class, and these modifications at image build time must not be visible at image run time. See Example 3 below for such a use case. So we need to simulate the execution of class initializer that are proven safe.
- In order to simulate execution of class initializer (and especially simulate the allocation of instances of classes that are no longer initialized by the image builder VM), we need to allow objects in the image heap for classes that are not initialized by the image builder VM.
- The image heap can contain instances of "simulated initialized" classes. Other than that, the current image heap restrictions remain: only classes that are marked for initialization at image build time are allowed in the image heap.

The new architecture does not lead to any breaking changes of existing user code and features. All code that is currently properly configured for build-time initialization will continue to work. But it will allow users to run more code at image build time, or reduce the number of classes explicitly marked for initialization at build time.

The new architecture does not lead to a reduce peak performance due to reduced automatic initialization of safe classes. All classes that are currently proven safe and initialized in the image builder VM can be simulated.

# Examples
To make the examples more readable, each class name has a suffix:

- `_InitAtBuildTime`: The class is explicitly marked as "initialize at build time" by the user
- `_ProvenSafe`: The class is not marked as initialization at build time, but the class initializer can be proven to have no side effects.
- `_InitAtRunTime`: The class is not marked as initialization at build time and therefore initialized at run time. This is the default.

We use the pseudo-field `$$initialized` to refer to the class initialization status of a class.

## Example 1
```java
class A_InitAtBuildTime {
  static int a = 42;
}

class B_InitAtRunTime {
  static int b = 123;

  static {
    A_InitAtBuildTime.a = A_InitAtBuildTime.a + 1;
  }
}
```

Assume that `B_InitAtRunTime` is not used by a Feature and therefore does not get initialized by the image builder VM. `A_InitAtBuildTime` gets initialized by the image builder VM because of its manual designation, regardless of how it is used at image build time. So in the image builder, the static field values are
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = 42

B_InitAtRunTime.$$initialized = false
B_InitAtRunTime.b = 0
```

The same values are written out into the image heap.

After the class `B_InitAtRunTime` gets initialized at run time, the static fields have the following values:
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = 43

B_InitAtRunTime.$$initialized = true
B_InitAtRunTime.b = 123
```

## Example 2
```java
class A_ProvenSafe {
  static int a = 42;
}

class B_InitAtRunTime {
  static int b = 123;

  static {
    A_ProvenSafe.a = A_ProvenSafe.a + 1;
  }
}
```
Assume that `B_InitAtRunTime` is not used by a Feature and therefore does not get initialized by the image builder VM. This means A_ProvenSafe is also not initialized. So in the image builder, the static field values are
```java
A_InitAtBuildTime.$$initialized = false
A_InitAtBuildTime.a = 0

B_InitAtRunTime.$$initialized = false
B_InitAtRunTime.b = 0
```
Because the initialization of `A_ProvenSafe` is simulated at image build time, the static field values in the image heap are
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = 42

B_InitAtRunTime.$$initialized = false
B_InitAtRunTime.b = 0
```
After the class `B_InitAtRunTime` gets initialized at run time, the static fields have the following values:
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = 43

B_InitAtRunTime.$$initialized = true
B_InitAtRunTime.b = 123
```
## Example 3
```java
class A_ProvenSafe {
  static int a = 42;
}

class B_InitAtRunTime {
  static int b = 123;

  static {
    A_ProvenSafe.a = A_ProvenSafe.a + 1;
  }
}
```
Assume that `B_InitAtRunTime` is used by a Feature and therefore gets initialized by the image builder VM. This transitively also initializes `A_ProvenSafe` in the image builder VM. So in the image builder, the static field values are
```java
A_ProvenSafe.$$initialized = true
A_ProvenSafe.a = 43

B_InitAtRunTime.$$initialized = true
B_InitAtRunTime.b = 123
```
Yet in the written out image, the following static field values are in the image heap based on the simulated initialization of `A_ProvenSafe`:
```java
A_ProvenSafe.$$initialized = true
A_ProvenSafe.a = 42

B_InitAtRunTime.$$initialized = false
B_InitAtRunTime.b = 0
```
After the class `B_InitAtRunTime` gets initialized at run time, the static fields have the following values:
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = 43

B_InitAtRunTime.$$initialized = true
B_InitAtRunTime.b = 123
```

**The usage of B_InitAtRunTime at image build time must not have a side effect on a class that is "proven safe" for initialization at build time.** Whether or not a class is "proven safe" depends on a lot of our internal implementation, i.e., how aggressively we are in the optimization. So with every release and with different optimization levels, more or less classes can be "proven safe". Any system where the static field has a different value (in our case the value of a would be 43 vs. 44 at run time) based on an optimization level is unusable in practice.
## Example 4
```java
class A_InitAtBuildTime {
  static int a = 42;
}

class B_InitAtRunTime {
  static int b = 123;

  static {
    A_InitAtBuildTime.a = A_InitAtBuildTime.a + 1;
  }
}
```

Assume that B_InitAtRunTime is used by a Feature and therefore gets initialized by the image builder VM. So in the image builder, the static field values are
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = 43

B_InitAtRunTime.$$initialized = true
B_InitAtRunTime.b = 123
```
In the written out image, the following static field values are in the image heap:
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = 43

B_InitAtRunTime.$$initialized = false
B_InitAtRunTime.b = 0
```
After the class B_InitAtRunTime gets initialized at run time, the static fields have the following values:
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = 44

B_InitAtRunTime.$$initialized = true
B_InitAtRunTime.b = 123
```
**The usage of B_InitAtRunTime at image build time has a bad side effect on A_InitAtBuildTime, whose field got incremented twice. But by explicitly marking A_InitAtBuildTime as initialize-at-buil-time, the user has acknowledged that such side effects are understood.**

Our recommendation for users should be: Only mark a class for initialization at build time if it does not have 1) any mutable static state (including any mutable data structures reachable from static final fields), and 2) a class initializer that accesses any mutable state of another class.
## Example 5
```java
class A_InitAtBuildTime {
  static Object a;
}

class MyFeature implements Feature {
  void beforeAnalysis(...) {
    A_InitAtBuildTime.a = new A_InitAtBuildTime();
  }
}
```
The feature code runs before static analysis and initializes the static field. So in the image builder, the static field values are
```java
A_InitAtBuildTime.$$initialized = true
A_InitAtBuildTime.a = <A_InitAtBuildTime instance>
```
This is a **correct use of build time initialization**: classes that store information computed a build time must be marked as "initialize at build time".
## Example 6
```java
class A_InitAtBuildTime {
  static Object a;
}

class B_InitAtRunTime {
}

class MyFeature implements Feature {
  void beforeAnalysis(...) {
    A_InitAtBuildTime.a = new B_InitAtRunTime();
  }
}
```
**Image build fails**: The image heap must not only contain instances of classes that are marked as "initialize at build time".
## Example 7
```java
class A_InitAtBuildTime {
  static Object a;
}

class B_ProvenSafe {
  static Object b = new B_ProvenSafe();
}

class MyFeature implements Feature {
  void beforeAnalysis(...) {
    A_InitAtBuildTime.a = new B_ProvenSafe();
  }
}
```

**Image build fails**: The field `A_InitAtBuildTime.a` must not reference an instance of `B_ProvenSafe` because `B_ProvenSafe` is not explicitly marked as "initialize at build time". Even though `B_ProvenSafe` already starts out as initialized at image run time due to simulation, and `B_ProvenSafe.b` is allowed to reference an instance of `B_ProvenSafe` whose allocation was simulated.

