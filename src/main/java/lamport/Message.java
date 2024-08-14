package lamport;

public record Message (int to, int from, int time, Payload payload) {

}
