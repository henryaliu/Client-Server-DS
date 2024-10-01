package Client;

import JSONParser.JSONParser;
import lamport.LamportClock;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;

public class GETClient implements Serializable {
    // Provides a universal serialisation ID across all servers/entities
    @Serial
    private static final long serialVersionUID = 4567L;

    private LamportClock clock; // Local Lamport clock

    private String AS_URL;
    private String serverName;
    private Integer port;
    private String stationID;

    private ObjectOutputStream output;
    private ObjectInputStream input;

    private Socket clientSocket;

    private String JSON; // The latest data (in JSON format) received from the Aggregation Server
    private String receivedData; // The latest data received from the Aggregation Server

    public GETClient() {
        clock = new LamportClock();
    }

    // Gets the server name, port number, and stationID if there is one
    public void getInfo() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter server name and port number (in URL format): ");
        String param1 = scanner.nextLine(); // Blocked code: Waits until user has pressed enter
        if (param1.isEmpty()) { // Retries on invalid input
            System.out.println("\nNO URL WAS PROVIDED - PLEASE TRY AGAIN\n");
            getInfo();
            return;
        }
        this.AS_URL = param1; // Keep track of the whole URL as well

        // server name and port number URL format:
        // "https://servername.cia.gov:portnumber"

        try {
            String[] domain = param1.split("//", 2); // Splits the URL into servername, port
            String[] sName = domain[1].split("\\.", 2);
            this.serverName = sName[0];
            String[] portInput = sName[1].split(":", 2);
            this.port = Integer.parseInt(portInput[1]);
        } catch (ArrayIndexOutOfBoundsException e) { // Retry on invalid URL format
            System.out.println("\n*** ERROR: Invalid URL format! Please try again ***\n");
            getInfo();
            return;
        }

        // If user hasn't provided a stationID, the Client automatically flags itself for requesting the latest data
        System.out.println("Enter the stationID you need data from (press enter to receive the latest data): ");
        this.stationID = scanner.nextLine();
        JSONParser temp = new JSONParser(); // Using JSONParser class' isNumber checker function
        if (!temp.isNumber(stationID) && (!this.stationID.trim().isEmpty())) {
            System.out.println("\n*** ERROR: You have provided an invalid port, please make sure it is a number only and try again. ***\n");
            getInfo();
            return;
        }
        if ((this.stationID.isEmpty()) || (this.stationID.trim().isEmpty())) { // Checks for empty input by user
            this.stationID = "latest";
        }
    }

    // Sends the GET message to the Aggregation Server
    // No state change when GET message sent, so aggregation server does not update its local clock until it sends back
    // port: the port of the Aggregation Server to connect to
    public void sendGET(Integer port) {
        // Message format:
        // GET /AggregationServer/SERVER_DATA.txt HTTP/1.1
        // Host: AS_URL
        // User-Agent: ATOMClient/1/0
        // Accept: stationID/json

        // Construction of the GET message
        String GET = "GET /AggregationServer/SERVER_DATA.txt HTTP/1.1" + "\n";
        GET += "Host: " + this.AS_URL + "\n";
        GET += "User-Agent: ATOMClient/1/0" + "\n";
        GET += "Accept: " + stationID + "/json" + "\n";

        try {
            output.writeObject(GET);
            output.flush();
            clock.updateTime(); // Local time is updated after GET message has been sent
        } catch (IOException ie) {
            System.out.println("Failed to send GET message to Aggregation Server: " + ie.getMessage());
            System.out.println("Please retry\n"); // Lets the user decide if they wish to retry
            clock.updateTime(); // Update clock after exception caught
            return;
        }

        while (true) {  // Waiting to receive the requested data (JSON string) from the AS
            try {
                if (((JSON = (String) input.readObject()) != null) && !(JSON.isEmpty())) {
                    String[] lines = JSON.split(System.lineSeparator());
                    if ((lines[0] != null) && (lines[1] != null)) { // If both the timestamp and line after it aren't empty
                        if (lines[1].equals("204") || JSON.equals("204")) { // No file exists, or it was empty
                            System.out.println("Error: no request data was found");
                            return;
                        }
                        if (lines[1].equals("400") || JSON.equals("400")) { // Status 400 is not recognised
                            System.out.println("Error: This request was not recognised");
                            return;
                        }

                        JSON = "";
                        for (int i = 1; i < lines.length; ++i) { // Ignores first line as it is the timestamp
                            JSON += lines[i];
                            if (i != lines.length - 1) {
                                JSON += "\n";
                            }
                        }
                        JSONParser jp = new JSONParser();
                        String[] receivedData = jp.JSONtoString(JSON).split(System.lineSeparator());
                        System.out.println("********************************"); // Text decoration
                        System.out.println("Weather data (uploaded by Content Server " + stationID + "): ");
                        for (int i = 0; i < receivedData.length; ++i) { // Display the data one line at a time
                            System.out.println("     " + receivedData[i]);
                        }
                        System.out.println("********************************"); // Text decoration
                        clock.processEvent(Integer.parseInt(lines[0])); // lines[0] = Lamport timestamp from AS, tiebreak processed here
                        return;
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Failed to read data from Aggregation Server: " + e.getMessage());
                return;
            }
        }
    }

    // Main GETClient operations
    // If socket connection is successful, it stays in a loop to scan for GET or END input from user
    // GET executes GET request using the stationID specified before this function was called
    // END terminates the GETClient program, ending the socket connection
    // 10 retries on socket connection error or stream error
    public void beginOperation() {
        int attempts = 0;
        while (attempts != 11) { // Retries on connection or stream errors (Limit: 10 attempts)
            try {
                clientSocket = new Socket(serverName, port); // Send the socket
                if (this.stationID.equals("latest")) { // If requesting latest data, let the user know in terminal
                    System.out.println("****************\n" + "GETClient will read the latest data" + "\n****************\n");
                } else {
                    System.out.println("****************\n" + "GETClient will read from Content Server " + this.stationID + "\n****************\n");
                }

                output = new ObjectOutputStream(clientSocket.getOutputStream());
                input = new ObjectInputStream(clientSocket.getInputStream());
                clock.updateTime(); // *** All sockets instantiated = 1 event
                output.writeObject(clock.getTime() + "\n" + "GETClient" + this.stationID); // Sends the timestamp and stationID
                output.flush();
                System.out.println("GETClient: Connected to the weather server!");

                Scanner scanner = new Scanner(System.in);
                String currLine = "";
                while (true) { // Loop to scan either for GET message or to END the server
                    currLine = scanner.nextLine();
                    if (currLine.equals("GET")) {
                        sendGET(port);
                    } else if (currLine.equals("END")) {
                        clientSocket.close();
                        return;
                    } else {
                        // ignore any other inputs
                        continue;
                    }
                }
            } catch (IOException ie) { // Retries until 10 attempts
                System.out.println("Attempt #" + attempts + ": Connecting to Aggregation Server...");
                if (attempts == 10) {
                    System.out.println("Ten attempts have been made to connect to the server but to no avail. GETClient Aborted.\n");
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
    }

    public static void main(String[] args) {
        GETClient client = new GETClient();
        client.getInfo();
        client.beginOperation();
    }

}
