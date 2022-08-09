## Build-Time Method Evaluation Samples
This repository contains a number of samples that use build-time method evaluation.

To test the repository used with the `vj/GR-39703-annotation-for-build-time-evaluation` branch the [GraalVM repo](https://github.com/oracle/graal/pull/4708). To build go to the `vm` folder and execute:
```bash
mx --env ni-ce build
export GRAALVM_HOME=`mx --env ni-ce graalvm-home`
```

### Running the Samples
To run the samples, execute the following in the **root of the project, where this README is contained**:
```
./gradlew :app:nativeTest
```
This will run all the build time initialization samples.
