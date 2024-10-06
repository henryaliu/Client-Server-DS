package lamport;

import java.util.concurrent.atomic.AtomicInteger;

public class LamportClock {

    private volatile AtomicInteger time; // for multi-threading access

    // Initialises the clock with initial time value of 0
    public LamportClock() {
        this.time = new AtomicInteger(0); // begin at 0
    }

    // Updates the lamport clock based on local and received timestamps
    // when sent or a time is received, this counts as an event, so increment the time
    // Returns: time (post tie-break)
    public int processEvent(int receivedTime) {
        // Accept the greater of the two times received
        return time.updateAndGet(currentTime -> (Math.max(currentTime, receivedTime) + 1));
    }

    // Increments the time, and then returns the current time after the increment;
    public int updateTime() {
        return time.incrementAndGet(); // increase time by 1 unit
    }

    // Gets the current time of the Lamport Clock
    public int getTime() {
        return time.get();
    }
}
