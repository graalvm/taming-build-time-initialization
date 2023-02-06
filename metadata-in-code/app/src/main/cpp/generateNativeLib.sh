# Remember to set your JAVA_HOME env var
mkdir -p ../../../native/linux_x86_64
g++ -c -fPIC -I${JAVA_HOME}/include -I${JAVA_HOME}/include/linux org_graalvm_example_JNIExampleJNI.cpp -o org_graalvm_example_JNIExampleJNI.o
g++ -shared -fPIC -o ../../../native/linux_x86_64/libnative.so org_graalvm_example_JNIExampleJNI.o -lc
# Don't forget to set java.library.path to point to the folder where you have the libnative you're loading.
