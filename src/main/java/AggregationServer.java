import java.io.IOException;

public interface AggregationServer {

    void getPort() throws Exception;

    void beginOperation(Integer inputPort) throws IOException;

    void processGETRequest() throws Exception;

    void processPUTRequest() throws Exception;

    boolean restorePreviousState() throws Exception;

    void discardJSON() throws Exception;

    // To run the server
    public static void main(String[] args) throws IOException {
        AggregationServerImpl aggr = new AggregationServerImpl();
        aggr.beginOperation();
    }
}
