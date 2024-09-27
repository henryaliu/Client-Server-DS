package lamport;

import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;

public class LamportClock {

    private volatile AtomicInteger time; // for multi-threading access

    public LamportClock() {
        this.time = new AtomicInteger(0); // begin at 0
    }

    // Process the tie break given a received timestamp from another entity
    // when sent or a time is received, this counts as an event, so increment the time
    public int processEvent(int receivedTime) {
        // Accept the greater of the two times received
        return time.updateAndGet(current -> Math.max(current, receivedTime) + 1);
    }

    // increment the time, and then return the current time
    public int updateTime() {
        return time.incrementAndGet(); // increase time by 1 unit
    }

    // Get current time of the Lamport Clock
    public int getTime() {
        return time.get();
    }
}
