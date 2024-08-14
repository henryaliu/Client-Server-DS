package lamport;

public interface LamportClock {

    /*
     * @param message: message received
     * @return current value in Lamport Clock
     */
    public int processEvent(Message message);

    /*
     * Updates internal clock for events excluding receive
     * @return current value in Lamport Clock
     */
    public int processEvent();

    /*
     * Gets the current time in Lamport Clock
     * @return current value in Lamport Clock
     */
    public int getTime();
}
