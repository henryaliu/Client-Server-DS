package lamport;

public class Node {

    public enum Status {
        SUCCESS,
        INPUT_BUFFER_FULL
    }

    /*
     * Start node, get it ready
     * @return true if successful node startup, false if failed
     */
    public boolean startup();

    /*
     * @return true if node in running state, false if terminated
     */
    public boolean isRunning();

    /*
     * @return identifier the node receives and sends messages with
     */
    public int getNodeId();

    /*
     * Send message to the node
     * @return status of the send process. Whether message was given to the node, nothing after.
     * Failure if not enough space to buffer queue a message
     */
    public Status sendTo(Message message);

}
