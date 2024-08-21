import java.util.HashMap;

public interface ContentServer {

    // prompt and store command line params:
    // (serverName, port), fileLocation
    void getParameters() throws Exception;

    // retrieve the file, parse it to JSON
    HashMap<String, String> parseToJSON();

    // send PUT request
    void sendPUT(Integer port) throws Exception;
}
