package lamport;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class LamportClockImplTest {

    LamportClock clock = new LamportClockImpl();

    // Tests for expected behaviour of the clock

    @Test
    void test() {
        int time;
        time = clock.getTime();
    }

}