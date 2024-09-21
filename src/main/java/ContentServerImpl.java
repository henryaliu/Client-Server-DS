import lamport.LamportClock;
import lamport.LamportClockImpl;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Scanner;
import java.lang.StringBuilder;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ContentServerImpl implements ContentServer {

    // globally number each CS
    private final String stationID;

    private final LamportClock clock;

    public String serverName;
    public Integer port;

    public String inputFileLoc;
    public String outputFileLoc;
    public HashMap<String, String> fileData;

    private Socket csSocket;
    private JSONParser parser;

    private BufferedReader input;
    private PrintWriter output;

    Logger logger = Logger.getLogger(ContentServerImpl.class.getName());

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

    public ContentServerImpl() {
        this.stationID = UUID.randomUUID().toString(); // set ID of this server to newest

        clock = new LamportClockImpl();
        fileData = new HashMap<String, String>();

        // Get server name, port and file entry
        getParameters();
        // Establish a connection to the AggregationServer
        try {
            csSocket = new Socket(serverName, Integer.valueOf(port)); // send socket
            System.out.println("Content server " + this.stationID + ": Attempting to connect to the AS...");

            PrintWriter out = new PrintWriter(csSocket.getOutputStream(), true);
            out.print("CS" + this.stationID + "\n"); // send "stationID" to AS
            out.flush();

            Scanner scanner = new Scanner(System.in); // scan terminal for user PUT requests
            String currLine = "";
            while (true) {
                currLine = scanner.nextLine();
                if (currLine.equals("PUT")) {
                    sendPUT(port);
                } else if (currLine.equals("END")) {
                    csSocket.close();
                    return;
                } else {

                }
            }

        } catch (IOException ie) {
            throw new RuntimeException(ie);
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
            throw new RuntimeException(ie);
        }

        // parse to JSON using JSONParser
        JSONParser jp = new JSONParser();
        // Parse the file to new JSON file
        jp.textToJSON(inputFileLoc, "weather.json");

        // Send the HTTPS PUT message containing the JSON data to the AS
        String PUT = "PUT /weather.json HTTP/1.1" + "\n"; // first line, doesn't change
        PUT += "User-Agent: ATOMClient/1/0" + "\n";
        PUT += "Content-Type: ";
        for (int i = 0; i < contentTypes.size(); i++) {
            PUT += contentTypes.get(i);
            if (i != (contentTypes.size() - 1)) {
                PUT += ", ";
            }
        }
        PUT += "\n";
        PUT += "Content-Length: " + contentTypes.size() + "\n" + "\n";
        try {
            // copy the JSON file over
            BufferedReader reader = new BufferedReader(new FileReader("weather.json"));
            String temp;
            while ((temp = reader.readLine()) != null) {
                PUT += (temp + "\n");
            }
        } catch (IOException ie) {
            throw new RuntimeException(ie);
        }

        // send the PUT message
        try {
            output = new PrintWriter(csSocket.getOutputStream(), true);
            output.println(PUT);
            output.flush();
        } catch (IOException ie) {
            System.out.println("Failed to send PUT message to AS");
        }

        // Check for confirmation (thumbs up) from AS
        while (true) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(csSocket.getInputStream()));
                String status = reader.readLine();
                if (status != null && !(status.isEmpty())) {
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
                        // else wait until some response is received on the status of the PUT
                    }
                }
            } catch (IOException ie) {
                System.out.println("Failure to receive status: " + ie.getMessage());
            }

        }

    }

}
