package lamport;

public class LamportClockImpl implements LamportClock {

    public LamportClockImpl() {
    }

    @Override
    public int processEvent(Message message) {
        return 0;
    }

    @Override
    public int processEvent() {
        return 0;
    }

    @Override
    public int getTime() {
        return 0;
    }
}
