package lamport;

import static java.lang.Math.max;

public class LamportClock {

    private int time;

    // Process the tie break given a received timestamp from another entity
    public int processEvent(int receivedTime) {
        // Accept the greater of the two times received
        this.time = max(time, receivedTime);
        return this.time;
    }
    // Sends the time/order of the Lamport Clock
//    public int sendEvent(String message) throws Exception;

    void updateTime() {
        this.time++;
    }

    // Get current time of the Lamport Clock
    public int getTime() {
        return this.time;
    }

}
