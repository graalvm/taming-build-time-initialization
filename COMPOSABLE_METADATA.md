# Safe Composition of Metadata 

## TL;DR 

To make usage of third-party reachability metadata safe we must guarantee that metadata addition can't break existing programs.
This is currently not the case:

1. **For reflective methods** such as `java.lang.Class#getDeclaredMethods` that return values based on reachability of other reflective elements. 
Because of this adding new metadata makes more elements reachable and can change program functionality in undesirable ways.

   We must require that all reflective methods on `java.lang.Class` require a metadata entry and that they: either return all elements when the metadata entry is present, or throw a missing-metadata exception when there is no metadata entry.

2. *For resource metadata* that can currently contain exclude patterns. An added exclude pattern can accidentally remove resources from the image and break the functionality of a working program.
    We must require that exclude patterns are regarded only within the scope of a single `resource-config.json` file.


## Safe Composition of Reflection Metadata

In Native Image, adding new metadata can break user programs because methods such as `java.lang.Class#getDeclaredMethods` return the set of currently reachable elements. 
In the following snippet
```java
if (AClass.class.getMethods().size > 5) {
    System.exit(1);
}
```
adding a metadata entry for a method from `AClass` can cause the `System.exit(1)` to be reached. 
Furthermore, this can happen by simply using methods from `AClass` anywhere in the code as this makes new methods reachable.

To allow safe composition of metadata we need to make sure that every reflective call on `java.lang.Class` requires a metadata entry. 
The following tables show metadata entries for all of those methods:

| Method                                  | Existing Metadata Entry              |
|-----------------------------------------|--------------------------------------|
|java.lang.Class#getDeclaredMethods       | "queryAllDeclaredMethods": true      |
|java.lang.Class#getDeclaredConstructors  | "queryAllDeclaredConstructors": true |
|java.lang.Class#getMethods               | "queryAllPublicMethods": true        |
|java.lang.Class#getConstructors          | "queryAllPublicConstructors": true   |
|java.lang.Class#getFields                | "allPublicFields": true              |
|java.lang.Class#getDeclaredFields        | "allDeclaredFields": true            |

| Newly Tracked Method                    | New Metadata Entry                   |
|-----------------------------------------|--------------------------------------|
|java.lang.Class#getClasses               | "queryAllPublicClasses": true        |
|java.lang.Class#getDeclaredClasses       | "queryAllDeclaredClasses": true      |
|java.lang.Class#getPermittedSubclasses   | "queryAllPermittedSubclasses": true  |
|java.lang.Class#getNestMembers           | "queryAllNestMembers": true          |


All methods above would return *all elements* when metadata entry is present, or *throw a missing-metadata exception* if the metadata entry is not present. 
All methods above and corresponding metadata  entries would be tracked by the Native Image agent. 

## Safe Composition of Resource Metadata

Resource metadata allows for `include` patterns such as
```json
{
  "resources": {
    "includes":[{
      "pattern": "assets/.*"
    }]
  }
}
```
and exclude patterns
```json
{
  "resources": {
    "excludes":[{
      "pattern": "assets/.*.png"
    }]
  }
}
```

This allows metadata from third-party jars to remove a resource by accident. The second example above removes all `png` files from assets of another library.

To allow safe composition, exclude patterns must apply only to resources that were matched by the include patterns in the same file. With this change the above 
example would still include all files from assets as the exclude pattern doesn't have a corresponding include pattern. 
To remove `png` files from assets the file would have to be specified as:
```json
{
  "resources": {
    "includes":[{
      "pattern": "assets/.*"
    }],
    "excludes":[{
      "pattern": "assets/.*.png"
    }]
  }
}
```

Note that composing all examples above would still include all resources from `assets` as the first file doesn't have an exclude pattern.
