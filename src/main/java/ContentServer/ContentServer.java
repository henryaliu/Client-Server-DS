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
import java.util.HashMap;
import java.util.Scanner;

public class ContentServer implements Serializable {
    // Provides a universal serialisation ID across all servers/entities
    @Serial
    private static final long serialVersionUID = 4567L;

    // Local Lamport Clock
    private final LamportClock clock;

    // Unique instance stationID
    private final String stationID;

    private ObjectOutputStream outstream;
    private ObjectInputStream reader;

    private String serverName;
    private Integer port;
    private String HOST;

    public String inputFileLoc;
    public String outputFileLoc;
    public HashMap<String, String> fileData;

    private Socket csSocket;
    private JSONParser parser;

    private String fileFolder = "ContentServer/";

    // Gets the URL from the user
    // Extracts the server name and port number
    public void getParameters() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter server name and port number (in URL format): ");
        HOST = scanner.nextLine();

        if (HOST.isEmpty()) {
            System.out.println("\nNO URL WAS PROVIDED - PLEASE TRY AGAIN\n");
            getParameters();
            return;
        }

        // server name and port number URL format:
        // "https://servername.cia.gov:portnumber"

        try {
            String[] domain = HOST.split("//", 2); // Domain ("servername.cia.gov:portnumber")
            String[] sName = domain[1].split("\\.", 2);
            this.serverName = sName[0]; // servername
            String[] portInput = sName[1].split(":", 2); // portnumber
            this.port = Integer.parseInt(portInput[1]);
        } catch (ArrayIndexOutOfBoundsException e) { // Retry on invalid URL format
            System.out.println("\n*** ERROR: Invalid URL format! Please try again ***\n");
            getParameters();
            return;        }

        System.out.println("Enter location of the entry file ('filename.txt'): ");
        if (((inputFileLoc = scanner.nextLine()).isEmpty()) || (inputFileLoc.trim().isEmpty())) { // Retry if empty file
            System.out.println("\nNO FILE WAS PROVIDED - PLEASE TRY AGAIN\n");
            getParameters();
            return;
        }
        Path path = Paths.get(inputFileLoc);
        if (!Files.exists(path)) {
            System.out.println("\nFILE DOESN'T EXIST - PLEASE TRY AGAIN\n");
            getParameters();
            return;
        }
        clock.updateTime(); // *** Update when server is allowed to start with valid URL data
    }

    // For testing purposes
    public Socket getCSSocket() {
        return csSocket;
    }

    // For testing purposes
    public String getID() {
        return stationID;
    }

    // For integration testing purposes to avoid needing terminal inputs
    public void setServer(String server, Integer port) {
        this.serverName = server;
        this.port = port;
    }

    // For testing purposes
    public void setEntryLoc(String location) {
        this.inputFileLoc = location;
    }

    // For testing purposes
    public void setFileFolder(String folder_location) {
        this.fileFolder = folder_location;
    }

    public void setHost(String inputHost) {
        this.HOST = inputHost;
    }

    // For testing purposes
    public ObjectOutputStream getOutputStream() {
        return this.outstream;
    }

    // For testing purposes
    public ObjectInputStream getInputStream() {
        return this.reader;
    }

    // Begins the Content Server operations:
    // Connects to the AggregationServer and then continuously listens for user prompts to END the server operations
    // Retries on server unavailable error or socket connection error (Limit: 10 automatic attempts)
    public ContentServer() {
        // Creates a stationID unique to the terminal, based on the port used
        this.stationID = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];

        clock = new LamportClock();
        fileData = new HashMap<String, String>();
    }

    public void beginOperation() {
        int attempts = 0;
        while (attempts != 11) { // Retry on error loop (Limit: 10 attempts)
            try {
                csSocket = new Socket(serverName, port); // Sends the socket
                System.out.println("Content server " + this.stationID + ": Connected to the weather server!");
                break;
            } catch (IOException ie) {
                System.out.println("Attempt #" + attempts + ": Connecting to Aggregation Server...");
                if (attempts == 10) {
                    System.out.println("Ten attempts have been made to connect to the server but to no avail. Content Server Aborted.\n");
                    return;
                }
                attempts++;
                try {
                    Thread.sleep(2000); // Allow enough time to pass before trying again.
                } catch (InterruptedException iee) {
                    System.out.println("Error: " + iee.getMessage());
                }
            }
        }

        try {
            outstream = new ObjectOutputStream(csSocket.getOutputStream());
            reader = new ObjectInputStream(csSocket.getInputStream()); // initialise inputstream as well here
            clock.updateTime(); // *** Internal state change: sockets updated (1 event)
            outstream.writeObject(clock.getTime() + "\n" + "CS" + this.stationID); // send timestamp and stationID
            outstream.flush();

            Scanner scanner = new Scanner(System.in); // scan terminal for user PUT requests
            String currLine = "";
            while (true) {
                currLine = scanner.nextLine();
                if (currLine.equals("PUT")) {
                    sendPUT();
                } else if (currLine.equals("END")) { // If the user types END, all live variables are shut down. Server ends.
                    csSocket.shutdownInput();
                    csSocket.shutdownOutput();
                    clock.updateTime();
                    csSocket.close();
                    return;
                } else {
                    continue;
                }
            }
        } catch (SocketException se) {
            System.out.println("Failed to connect to AS: " + se.getMessage());
            clock.updateTime();
            new ContentServer(); // Retry the server
            return;
        } catch (IOException ie) {
            System.out.println("Failed to send information: " + ie.getMessage());
            clock.updateTime();
            new ContentServer(); // Retry the server
            return;
        }
    }

    // Sends the PUT message to the socket the server is connected to (Aggregation Server)
    // Retrieves the entry file, parses it to JSON String, serialises it and sends to server,
    // and waits for confirmation that the data uploaded successfully.
    public void sendPUT() {
        JSONParser jp = new JSONParser(); // Custom JSON Parser (see JSONParser folder)
        jp.textToJSON(inputFileLoc,  fileFolder + "weather.json"); // Parse the entry file to local weather.json file
        Path path = Paths.get(fileFolder + "weather.json");
        String PUT = "";
        try {
            if (Files.exists(path) && (Files.size(path) > 0)) {
                long length = ((Files.lines(Paths.get(path.toUri())).count()));
                PUT = "PUT /" + fileFolder + "/weather.json HTTP/1.1" + "\n";
                PUT += "Host: " + HOST + "\n";
                PUT += "User-Agent: ATOMClient/1/0" + "\n";
                PUT += "Content-Type: weather/json" + "\n"; // stationID
                PUT += "Content-Length: " + length + "\n" + " " + "\n";
                PUT += (Files.readString(path)); // Copies the JSON file over
            } else {
                System.out.println("Error - local weather.json couldn't be retrieved.");
                clock.updateTime();
                return;
            }
        } catch (IOException ie) {
            System.out.println("Couldn't read local JSON file: " + ie.getMessage());
            clock.updateTime();
            return;
        }

        try {
            clock.updateTime();
            PUT = (clock.getTime() + "\n" + PUT); // Add the timestamp to top of the message
            outstream.writeObject(PUT); // Send entire PUT message through the stream (serialised)
            outstream.flush();
        } catch (IOException ie) {
            System.out.println("Failed to send PUT message to Aggregation Server: " + ie.getMessage());
            System.out.println("Please PUT again"); // Lets the user decide if they wish to retry
            return;
        }

        String received = "";
        while (true) { // While loop checks for confirmation that PUT succeeded
            try {
                if (((received = (String) reader.readObject()) != null) && !(received.isEmpty())) {
                    String[] status = received.split(System.lineSeparator());
                    int receivedTime = Integer.parseInt(status[0]); // receivedTime = Aggregation Server local time
                    if (status[1].equals("500")) { // Check status message received
                        System.out.println("500 - Internal server error" + "\n"); // Content doesn't make sense
                        clock.processEvent(receivedTime);
                        return;
                    } else if (status[1].equals("204")) { // 204 if this server sent empty content
                        System.out.println("204 - No content was received" + "\n");
                        clock.processEvent(receivedTime);
                        return;
                    } else if (status[1].equals("400")) { // Some other status
                        System.out.println("400");
                        clock.processEvent(receivedTime);
                        return;
                    } else if (status[1].equals("201")) { // New file was created
                        System.out.println("201 - HTTP_CREATED" + "\n");
                        clock.processEvent(receivedTime);
                        return;
                    } else if (status[1].equals("200")) { // Standard successful upload
                        System.out.println("200 - Request successful" + "\n");
                        clock.processEvent(receivedTime);
                        return;
                    } else { // Any other message sent back is not recognised
                        System.out.println("Unidentifiable response from the aggregation server");
                        clock.processEvent(receivedTime);
                        return;
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                if (e.getMessage().equals("invalid type code: AC")) { // For handling potential stream errors due to empty message
                    System.out.println(e.getMessage());
                    clock.updateTime();
                    continue;
                } else {
                    clock.updateTime();
                    return;
                }
            }
        }
    }

    public static void main(String[] args) {
        ContentServer cs = new ContentServer();
        cs.getParameters();
        cs.beginOperation();
    }

}
