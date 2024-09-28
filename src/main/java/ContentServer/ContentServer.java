package ContentServer;

import JSONParser.JSONParser;
import lamport.LamportClock;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;

import java.util.UUID;

public class ContentServer implements Serializable {
    // Provides a common serialisation ID across all servers/entities
    @Serial
    private static final long serialVersionUID = 4567L;

    // globally number each CS
    private final String stationID;

    private final LamportClock clock;

    private ObjectOutputStream outstream;
    private ObjectInputStream reader;

    private String serverName;
    private Integer port;

    public String inputFileLoc;
    public String outputFileLoc;
    public HashMap<String, String> fileData;

    private Socket csSocket;
    private JSONParser parser;

    public void getParameters() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter server name and port number (in URL format): ");
        String param1 = scanner.nextLine();

        // server name and port number URL format:
        // "https://servername.cia.gov:portnumber"

        // parsing process for the URL
        String[] domain = param1.split("//", 2);
        String[] sName = domain[1].split("\\.", 2);
        this.serverName = sName[0];
        String[] portInput = sName[1].split(":", 2);
        this.port = Integer.parseInt(portInput[1]);

        // parsing process for entry file location
        System.out.println("Enter location of the entry file ('filename.txt'): ");
        this.inputFileLoc = scanner.nextLine();
    }

    public ContentServer() {
        // give server unique ID based on the terminal info
        this.stationID = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

        clock = new LamportClock();
        fileData = new HashMap<String, String>();

        // Get server name, port and file entry
        getParameters();
        // Establish a connection to the AggregationServer.AggregationServer
        try {
            csSocket = new Socket(serverName, port); // send socket
            System.out.println("Content server " + this.stationID + ": Connected to the weather server!");

            outstream = new ObjectOutputStream(csSocket.getOutputStream());
            reader = new ObjectInputStream(csSocket.getInputStream()); // initialise inputstream as well here
            outstream.writeObject("CS" + this.stationID); // send "stationID" to AS
            outstream.flush();

            Scanner scanner = new Scanner(System.in); // scan terminal for user PUT requests
            String currLine = "";
            while (true) {
                currLine = scanner.nextLine();
                if (currLine.equals("PUT")) {
                    sendPUT(port);
                } else if (currLine.equals("END")) {
                    csSocket.shutdownInput();
                    csSocket.shutdownOutput();
                    csSocket.close();
                    return;
                } else {
                    continue;
                }
            }
        } catch (SocketException se) {
            System.out.println("Failed to connect to AS: " + se.getMessage());
        } catch (IOException ie) {
            System.out.println("Failed to send inform");
        }
    }

    public void sendPUT(Integer port) {
        // Read what the entry file content type is
        ArrayList<String> contentTypes = new ArrayList<String>();
        try {
            BufferedReader typeReader = new BufferedReader(new FileReader(inputFileLoc));
            String tempLine;
            String lineWords[];
            while ((tempLine = typeReader.readLine()) != null) {
                lineWords = tempLine.split(":", 2);
                contentTypes.add(lineWords[0]);
            }
        } catch (IOException ie) {
            System.out.println("Failed to send PUT request: " + ie.getMessage());
            return;
        }

        // parse to JSON using JSONParser.JSONParser
        JSONParser jp = new JSONParser();
        // Parse the file to new JSON file
        jp.textToJSON(inputFileLoc, "ContentServer/weather.json");
        Path path = Paths.get("ContentServer/weather.json");
        String PUT = "";
        try {
            if (Files.exists(path) && (Files.size(path) > 0)) {
                long length = ((Files.lines(Paths.get(path.toUri())).count()));
                // Send the HTTPS PUT message containing the JSON data to the AS
                PUT = "PUT /ContentServer/weather.json HTTP/1.1" + "\n"; // first line, doesn't change
                PUT += "User-Agent: ATOMClient/1/0" + "\n";
                PUT += "Content-Type: weather/json" + "\n"; // stationID
                PUT += "Content-Length: " + length + "\n" + " " + "\n";
                // copy the JSON file over
                PUT += (Files.readString(path));
            } else {
                // file doesn't exist
                System.out.println("Error - Failed to read JSON file into the PUT message: ");
                return;
            }
        } catch (IOException ie) {
            System.out.println("Couldn't read local JSON file: " + ie.getMessage());
            return;
        }

        // send the PUT message
        try {
            outstream.writeObject(PUT);
            outstream.flush();
        } catch (IOException ie) {
            System.out.println("Failed to send PUT message to Aggregation Server: " + ie.getMessage());
            return;
        }

        // Check for confirmation (thumbs up) from AS
        String status = "";
        while (true) {
            try {
                if (((status = (String) reader.readObject()) != null) && !(status.isEmpty())) {
                    if (status.equals("500")) {
                        System.out.println("500 - Internal server error" + "\n");
                        return; // unsuccessful put, so return
                    } else if (status.equals("204")) { // for potential errors?
                        System.out.println("204 - No content was received" + "\n");
                        return; // return because it wasn't successful
                    } else if (status.equals("400")) {
                        System.out.println("400");
                        return;
                    } else if (status.equals("201")) {
                        System.out.println("201 - HTTP_CREATED" + "\n");
                        return;
                    } else if (status.equals("200")) {
                        System.out.println("200 - Request successful" + "\n");
                        return;
                    } else {
                        // ignore other messages
                        System.out.println("Unidentifiable response from the aggregation server");
                        return;
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                if (e.getMessage().equals("invalid type code: AC")) { // if error suggests the stream is empty
                    System.out.println(e.getMessage());
                    continue;
                } else {
                    return;
                }
            }
        }
    }

    public static void main(String[] args) {
        ContentServer cs = new ContentServer();
    }

}
