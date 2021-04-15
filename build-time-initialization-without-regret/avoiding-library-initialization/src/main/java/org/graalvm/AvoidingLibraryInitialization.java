package org.graalvm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.NOPLogger;

public class AvoidingLibraryInitialization {

    static {
        getLogger().debug("The meaning of life is 42!");
    }

    public static void main(String[] args) {
        getLogger().info("Application started!");
    }

    private static Logger getLogger() {
        if ("buildtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"))) {
            return NOPLogger.NOP_LOGGER;
        } else {
            return LoggerFactory.getLogger(AvoidingLibraryInitialization.class);
        }
    }

}
