package org.graalvm;


class SlowMath {
    private static final boolean qSquareRoot = Boolean.getBoolean(System.getProperty("fast.root"));

    public static float rsqrt(float x) {
        if (qSquareRoot) {
            float xhalf = 0.5f * x;
            int i = Float.floatToIntBits(x);
            i = 0x5f3759df - (i >> 1); // evil floating point bit-level hacking. What the ***?
            x = Float.intBitsToFloat(i);
            x *= (1.5f - xhalf * x * x);
            return x;
        } else {
            return 1.0f / (float) Math.sqrt(x);
        }
    }

    static long add(long x, long y) {
        return x + y;
    }
}

class FastMath {
    static long add(long x, long y) {
        return x + y;
    }
}


public class HotPathChecks {

    private static final int ITERATIONS = 1000_000_000;

    public static void main(String[] args) {

        long startTime = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sum = SlowMath.add(sum, i);
        }
        System.out.println("With SlowMath:" + (System.nanoTime() - startTime));

        startTime = System.nanoTime();
        sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sum = FastMath.add(sum, i);
        }
        System.out.println("With FastMath:" + (System.nanoTime() - startTime));
    }
}