package lamport;

import java.util.concurrent.Executor;

public interface Processor {

    /*
     * Start the processor, ready to accept messages and action them
     * @return true if start successful, false if failed to start
     */
    public boolean start();

    /*
     * Send message to another node
     * message: the message to send
     * to: identifier of the node which the message is to be sent to
     */
    public void send(Message message, int to);

    // Terminate the processor. Messages no longer accepted, no longer sent.
    public void terminate();

    /*
     * Declare the type of execution thread to be run on the processor
     * If not set, default is a simple thread creation
     * @executor which executor to use
     */
    void setExecutor(Executor executor);
}
