package lamport;

public interface LamportClock {

    // Process the tie break given a received timestamp from another entity
    int processEvent(int receivedClock);

    // Sends the time/order of the Lamport Clock
//    public int sendEvent(String message) throws Exception;

    void updateTime() throws Exception;

    // Get current time of the Lamport Clock
    int getTime();

}
