package org.graalvm;

import java.lang.Thread;

public class SneakyRunningThread {

    public static Thread sneakyThread = new Thread(SneakyRunningThread::doNothing);
    static {
        System.out.println("Starting a sneaky thread");
        sneakyThread.start();
    }

    private static void doNothing() {
        while(true) {
            try {
                System.out.println("Doing nothing!");
                Thread.sleep(5000);
            } catch (Exception e) {
                break;
            }
        }
    }

    public static void main(String[] args) {
        System.out.println("Hello, World!");
        System.out.println("Sneaky thread's id:" + sneakyThread.getId());
    }
}
