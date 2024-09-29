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

    private LamportClock clock; // local time clock

    private String AS_URL;
    private String serverName;
    private Integer port;
    private String stationID; // data from the AS will be from last updated by this stationID

    private ObjectOutputStream output;
    private ObjectInputStream input;

    private Socket clientSocket;

    private String JSON;
    private String receivedData;

    public GETClient() {
        clock = new LamportClock();
    }

    // Get server name, port number, and stationID if there is one
    public void getInfo() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter server name and port number (in URL format): ");
        String param1 = scanner.nextLine();
        this.AS_URL = param1; // record the whole URL

        // server name and port number URL format:
        // "https://servername.cia.gov:portnumber"

        // parsing process for the URL
        String[] domain = param1.split("//", 2);
        String[] sName = domain[1].split("\\.", 2);
        this.serverName = sName[0];
        String[] portInput = sName[1].split(":", 2);
        this.port = Integer.parseInt(portInput[1]);

        // parsing process for entry file location
        System.out.println("Enter the stationID you need data from (press enter to skip - you will receive all data): ");
        this.stationID = scanner.nextLine();
        if (this.stationID.isEmpty()) {
            this.stationID = "latest";
        }
    }

    // No state change when GET message sent, so aggregation server does not update lamport clock until it sends back
    public void sendGET(Integer port) {
        // get message format:
        // GET /AggregationServer/SERVER_DATA.txt HTTP/1.1
        // Host: AS_URL
        // User-Agent: ATOMClient/1/0
        // Accept: stationID/json

        // Create the GET message
        String GET = "GET /AggregationServer/SERVER_DATA.txt HTTP/1.1" + "\n";
        GET += "Host: " + this.AS_URL + "\n";
        GET += "User-Agent: ATOMClient/1/0" + "\n";
        GET += "Accept: " + stationID + "/json" + "\n";

        // send the GET message
        try {
            output.writeObject(GET);
            output.flush();
            clock.updateTime(); // Update after message sent to aggregation server
        } catch (IOException ie) {
            System.out.println("Failed to send GET message to Aggregation Server: " + ie.getMessage());
            clock.updateTime(); // Update clock after exception caught
            return;
        }

        // Wait to receive the requested data (JSON string) from the AS
        while (true) {
            try {
                JSON = (String) input.readObject();
                String[] lines = JSON.split(System.lineSeparator());
                if ((lines[0] != null) && !(JSON.isEmpty()) && (lines[1] != null)) {
                    if (lines[1].equals("204") || JSON.equals("204")) {
                        System.out.println("Error: no request data was found");
                        return;
                    }
                    if (lines[1].equals("400") || JSON.equals("400")) {
                        System.out.println("Error: This request was not recognised");
                        return;
                    }

                    // check if we received EMPTY_GET status
                    if (lines[1].equals("EMPTY_GET") || JSON.equals("EMPTY_GET")) {
                        System.out.println("Error: The server's weather file is empty");
                        return;
                    }

                    // remove first line from JSON
                    JSON = "";

                    for (int i = 1; i < lines.length; ++i) {
                        JSON += lines[i];
                        if (i != lines.length - 1) {
                            JSON += "\n";
                        }
                    }
                    // convert the JSON string to regular entry file format string
                    JSONParser jp = new JSONParser();
                    String[] receivedData = jp.JSONtoString(JSON).split(System.lineSeparator());
                    // display (already one line at a time)
                    System.out.println("********************************");
                    System.out.println("Weather data (uploaded by Content Server " + stationID + "): ");
                    for (int i = 0; i < receivedData.length; ++i) {
                        System.out.println("     " + receivedData[i]);
                    }
                    System.out.println("********************************");
                    clock.processEvent(Integer.parseInt(lines[0])); // lines[0] = lamport timestamp from AS
                    System.out.println(clock.getTime());
                    return;
                }
            } catch (IOException | ClassNotFoundException e) {
                System.out.println("Failed to read data from Aggregation Server: " + e.getMessage());
                return;
            }
        }
    }

    public void beginOperation() {
        // run main operation loop
        try {
            clientSocket = new Socket(serverName, port); // send socket
            if (this.stationID.equals("latest")) {
                System.out.println("GETClient (reading all data): Connected to the weather server!");
            } else {
                System.out.println("GETClient (reading from Content Server " + this.stationID + "): Connected to the weather server!");
            }

            output = new ObjectOutputStream(clientSocket.getOutputStream());
            input = new ObjectInputStream(clientSocket.getInputStream());
            clock.updateTime(); // *** all sockets instantiated = 1 event
            output.writeObject(clock.getTime() + "\n" + "GETClient" + this.stationID); // send timestamp and stationID
            output.flush();

            Scanner scanner = new Scanner(System.in); // scan terminal for user PUT requests
            String currLine = "";
            while (true) {
                currLine = scanner.nextLine();
                if (currLine.equals("GET")) {
                    sendGET(port);
                } else if (currLine.equals("END")) {
                    clientSocket.close();
                    return;
                } else {
                    // ignore any other inputs
                }
            }
        } catch (IOException ie) {
            System.out.println("Failed to connect to Aggregation Server: " + ie.getMessage());
        }
    }

    public static void main(String[] args) {
        GETClient client = new GETClient();
        client.getInfo();
        client.beginOperation();
    }

}
