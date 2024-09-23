package Client;

import JSONParser.JSONParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;

public class GETClient {

    private String AS_URL;
    private String serverName;
    private Integer port;
    private String stationID; // data from the AS will be from last updated by this stationID

    private Socket clientSocket;

    private BufferedReader input;
    private PrintWriter output;

    private String JSON;
    private String receivedData;

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
            this.stationID = "all";
        }
    }

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
            output = new PrintWriter(clientSocket.getOutputStream(), true);
            output.println(GET);
            output.flush();
        } catch (IOException ie) {
            System.out.println("Failed to send GET message to Aggregation Server: " + ie.getMessage());
            return;
        }

        // Wait to receive the requested data (JSON string) from the AS
        while (true) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                String currLine = reader.readLine();
                if ((currLine != null) && (!currLine.isEmpty())) {

                    if (currLine.equals("204")) {
                        System.out.println("Error: no request data was found");
                        return;
                    }

                    // check if we received EMPTY_GET status
                    if (currLine.equals("EMPTY_GET")) {
                        System.out.println("Error: The server's weather file is empty");
                        return;
                    }

                    JSON = ""; // ensure JSON string is empty at first
                    JSON += (currLine + "\n"); // append the first line which was already read

                    // read the rest of the data
                    while ((currLine = reader.readLine()) != null) {
                        JSON += (currLine + "\n");
                    }
                    // convert the JSON string to regular entry file format string
                    JSONParser jp = new JSONParser();
                    receivedData = jp.JSONtoString(JSON);
                    // display (already one line at a time)
                    System.out.println("Weather data has been received: ");
                    System.out.println(receivedData);
                    return;
                }
            } catch (IOException ie) {
                System.out.println("Failed to read data from Aggregation Server: " + ie.getMessage());
                return;
            }
        }
    }

    public void beginOperation() {
        // run main operation loop
        try {
            clientSocket = new Socket(serverName, port); // send socket
            if (this.stationID.equals("all")) {
                System.out.println("GETClient (reading all data): Connected to the weather server!");
            } else {
                System.out.println("GETClient (reading from Content Server " + this.stationID + "): Connected to the weather server!");
            }

            PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
            out.print("GETClient (" + this.stationID + ")\n"); // send "stationID" to AS
            out.flush();

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
            System.out.println(ie.getMessage());
        }
    }

    public static void main(String[] args) {
        GETClient client = new GETClient();
        client.getInfo();
        client.beginOperation();
    }

}
