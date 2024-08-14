package lamport;

/*
 * Command set that may help instruct recipient Node on what to do next
 * Expandable
 */

public enum Command {
    WAIT,
    SEND_TO,
    TERMINATE
}
