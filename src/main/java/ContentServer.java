import java.io.IOException;
import java.util.HashMap;

public interface ContentServer {

    // prompt and store command line params:
    // (serverName, port), fileLocation
    void getParameters() throws Exception;

    // retrieve file, parse to JSON, send PUT request
    void sendPUT(Integer port) throws IOException;

    public static void main(String[] args) {
        ContentServerImpl c = new ContentServerImpl();
    }

}
