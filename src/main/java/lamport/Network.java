package lamport;

// Single object represents network, only 1 instance of network between nodes
// Nodes can access the singular Network object and add themselves to it, and then send messages
// to other nodes.

public interface Network {

    public enum SendStatus {
        SUCCESS,
        NO_SUCH_NODE,
        NODE_TERMINATED,
        NETWORK_FAILURE
    }

    // Returns singular network object
    public Network getNetwork();

    /* Adds node to the network
     * @return the node's unique identifier in the network
     * Messages can be sent based on this identifier, and come from this identifier when sent.
     * Throws IndexOutOfBoundsException if no space to add nodes
    */
    public int addNode(Node node) throws IndexOutOfBoundsException;

    /*
     * Send message to node by its identifier
     * message: message to send
     * identifier: node identifier
     * @return SUCCESS if message was sent, otherwise failure
     */
    public SendStatus sendTo(Message message, int identifier);
}
