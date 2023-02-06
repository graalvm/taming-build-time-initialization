#include "org_graalvm_example_JNIExampleJNI.h"
#include <iostream>


/*
 * Class:     org_graalvm_example_JNIExample
 * Method:    jniCallDoingReflection
 * Signature: ()I;
 */
JNIEXPORT jint JNICALL Java_org_graalvm_example_JNIExample_jniCallDoingReflection(JNIEnv *env){

    jclass jniExample = env->FindClass("org/graalvm/example/JNIExample");
	
    // Get UserData fields to set
    jfieldID v1Field = env->GetStaticFieldID(jniExample , "v1", "I");
    jmethodID v2Method = env->GetStaticMethodID(jniExample, "v2", "()I");
	
    // Set the values of the new object
    jint v1 = env->GetStaticIntField(jniExample, v1Field);
    jint v2 = env->CallStaticIntMethod(jniExample, v2Method);
    
    // Return the created object
    return v1 + v2;
  }
