package org.graalvm;

public class ReadPropertyHolder {

  public static boolean useFastInverseSquareRoot() {
    // return false;
    return Boolean.getBoolean(System.getProperty("org.graalvm.fast.isqrt"));
  } 

}