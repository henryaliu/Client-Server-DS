package lamport;

import static java.lang.Math.max;

public class LamportClockImpl implements LamportClock {

    // time of the local LamportClock instance
    private int time;

    public int processEvent(int receivedTime) {
        // Accept the greater of the two times received
        this.time = max(time, receivedTime);
        return this.time;
    }

//    public int sendEvent(String message) {
//    }

    public void updateTime() {
        this.time++;
    }

    public int getTime() {
        return this.time;
    }

}
