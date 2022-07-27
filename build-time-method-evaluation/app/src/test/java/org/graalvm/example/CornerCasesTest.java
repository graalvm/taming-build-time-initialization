package org.graalvm.example;

import org.graalvm.example.nativeimage.meta.Constant;
import org.junit.jupiter.api.Test;

public class CornerCasesTest {

    @Constant
    public static IntegerHolder getHolder(int number) {
        return new IntegerHolder(number);
    }

    @Constant
    public static void incrementHolder(IntegerHolder holder) {
        holder.setNumber(holder.getNumber() + 1);
    }

    @Test
    public void testConstantInLoop() {
        for (int i = 0; i < 20; i++) {
            IntegerHolder holder = getHolder(50);
            // If getHolder is folded, this points to the same constant across all loop iterations
            holder.setNumber(holder.getNumber() + 1);
            // Expected to be 51 every iteration, but actually always gets incremented
            System.out.println("Holder number is: " + holder.getNumber());
        }
    }

    @Test
    public void testConstantMutation() {
        IntegerHolder holder = getHolder(50);
        // Expected to print 50, but will actually print 51 as it points to the same constant that was mutated with a call to incrementHolder
        System.out.println("Holder expected to be 50, but it's actually: " + holder.getNumber());
        incrementHolder(holder);
        System.out.println("It should really be 51 here: " + holder.getNumber());
    }
}

class IntegerHolder
{
    private int number;

    public IntegerHolder(int number) {
        this.number = number;
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }
}
