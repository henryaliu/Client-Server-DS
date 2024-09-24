package lamport;

import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;

public class LamportClock {

    private volatile AtomicInteger time; // for multi-threading access

    public LamportClock() {
        this.time = new AtomicInteger(0);
    }

    // Process the tie break given a received timestamp from another entity
    public int processEvent(int receivedTime) {
        // Accept the greater of the two times received
        this.time = max(time, receivedTime);
        return this.time;
    }

    void updateTime() {
        this.time++;
    }

    // Get current time of the Lamport Clock
    public int getTime() {
        return this.time;
    }

}
