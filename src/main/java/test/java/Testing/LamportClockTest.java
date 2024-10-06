package test.java.Testing;

import lamport.LamportClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LamportClockTest {
    LamportClock clock = new LamportClock();

    @Test
    void multiThreadTestLarge() { // Testing for synchronicity of clock across multiple threads after asynchronous usage
        int num_threads = 20;
        for (int i = 0; i < num_threads; ++i) {
            new Thread(() -> {
               clock.updateTime();
            }).start();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        }
        assertEquals(num_threads, clock.getTime());
    }

    @Test
    void multiThreadTestSmall() { // Testing for synchronicity of clock across multiple threads after asynchronous usage
        int num_threads = 0;
        for (int i = 0; i < num_threads; ++i) {
            new Thread(() -> {
                clock.updateTime();
            }).start();
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        }
        assertEquals(num_threads, clock.getTime());
    }
}
