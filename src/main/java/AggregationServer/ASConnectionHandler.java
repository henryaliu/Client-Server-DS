package AggregationServer;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import JSONParser.JSONParser;

// An employee of the AggregationServer.AggregationServer
// This class handles the backend, ie writing to the weather file
public class ASConnectionHandler extends Thread {

    private Socket receivedSocket;
    private String entID;
    private BufferedReader input;
    private PrintWriter output;

    private boolean alive;

    private long timeStamp;

    private BufferedReader requestInput;
    private PrintWriter requestOutput;

    private final String weatherFileName = "AggregationServer/SERVER_DATA.txt";

    // string1: key type, string2: who last updated it
    ConcurrentHashMap<String, String> whoUpdated = new ConcurrentHashMap<String, String>();

    // To implement: task queue for PUT and GET operations in a sequential, ordered manner
    public Queue<String> serverTaskQueue;

    public ASConnectionHandler(Socket s, String entityID) throws IOException {
        // Initialise socket
        alive = true;
        timeStamp = System.currentTimeMillis();
        this.receivedSocket = s;
        this.entID = entityID; // stationID (CS) or clientID (Client)
        System.out.println("\nThread created for " + s + ".");
    }

    public String getEntID() {
        return entID;
    }

    public boolean isConnected() {
        return this.alive;
    }

    public ConcurrentHashMap<String, String> getUpdatees() {
        return whoUpdated;
    }

    // given a string in input file format (NOT JSON), update the existing weather data file
    public void updateFile(String entries) {
        // first, check the file exists
        String filepath = weatherFileName;
        Path path = Paths.get(filepath);
        try {
            if (!Files.exists(path) || Files.size(path) == 0) {
                // if it doesn't exist, simply create this file, write to it, and return
                System.out.println("No weather file yet - creating new one");
                PrintWriter pw = new PrintWriter(weatherFileName);
                pw.println(entries);
                pw.flush();
                return;
            }
        } catch (IOException ie) {
            System.out.println("Server error - data file doesn't exist, failed to make file."); // may be counterproductive
            return;
        }

        // default: update existing file
        // first, turn the weather file into string HashMap (type, entry)
        ConcurrentHashMap<String, String> currentWeatherData = new ConcurrentHashMap<String, String>();
        try {
            BufferedReader re = new BufferedReader(new FileReader(weatherFileName));
            String currLine = "";
            String[] temp;
            while (((currLine = re.readLine()) != null) && (!currLine.isEmpty())) {
                temp = currLine.split(":", 2);
                currentWeatherData.put(temp[0], temp[1]); // temp[0]=entry type, temp[1]=entry value
            }
        } catch (IOException ie) {
            System.out.println("Server error - failed to retrieve server data file");
        }

        // Split entries-to-update into newEntries HashMap
        String[] feed = entries.split(System.lineSeparator());
        ConcurrentHashMap<String, String> newEntries = new ConcurrentHashMap<String, String>();
        String[] feedLine;
        for (int i = 0; i < feed.length; ++i) { // put new entries (type, value) into hashmap
            feedLine = feed[i].split(":", 2);
            newEntries.put(feedLine[0], feedLine[1]);
        }

        // write new data to the data file
        try {
            PrintWriter pw = new PrintWriter(weatherFileName);
            // for each entry to update
            for (ConcurrentHashMap.Entry<String, String> new_entry : newEntries.entrySet()) {
                // for each entry in current weather data
                for (ConcurrentHashMap.Entry<String, String> curr_entry : currentWeatherData.entrySet()) {
                    // if entry-to-update equals current weather entry, replace current weather entry with updated entry
                    if (new_entry.getKey().equals(curr_entry.getKey())) {
                        currentWeatherData.replace(new_entry.getKey(), new_entry.getValue()); // update with new entry
                        newEntries.remove(new_entry.getKey()); // remove it from newEntries vector
                        // if key hasn't been added before, add to whoUpdated hashmap
                        if (whoUpdated.get(new_entry.getKey()) == null) {
                            whoUpdated.put(new_entry.getKey(), entID);
                        } else {
                            // else, replace existing author with new author (entID)
                            whoUpdated.replace(new_entry.getKey(), entID);
                        }
                    }
                }
            }

            // check for values remaining (ie, they arent currently in the weather data
            if (!(newEntries.isEmpty())) {
                currentWeatherData.putAll(newEntries);
                for (ConcurrentHashMap.Entry<String, String> new_entry : newEntries.entrySet()) {
                    whoUpdated.put(new_entry.getKey(), entID);
                }
            }

            // overwrite existing file with curr_entry which was updated with new entries
            for (ConcurrentHashMap.Entry<String, String> curr_entry : currentWeatherData.entrySet()) {
                pw.println(curr_entry.getKey() + ":" + curr_entry.getValue());
            }
            pw.flush();
        } catch (IOException ie) {
            System.out.println("Server error - failed to update weather data");
        }
    }

    // checks the type of request, AND what status to return
    public String checkRequestType() throws IOException {
        String type = "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(receivedSocket.getInputStream()));
        // Read the first line to see if its PUT or GET
        String firstLine = reader.readLine();
        if (firstLine == null || firstLine.isEmpty()) {
            return "204";
        }
        String[] lineBreakUp = firstLine.split("\\s+", 2); // split by space
        int numEntries = 0; // number of lines of data in JSON file
        if (lineBreakUp[0].equals("PUT")) {
            timeStamp = System.currentTimeMillis();
            type = "PUT";
            String onlyJSON = ""; // for storing the JSON data only in the PUT message
            // skip to 6th line (currently at 1st)
            for (int i = 0; i < 5; ++i) {
                firstLine = reader.readLine(); // 2nd
                // at 4th line, take note of the content-length (# of lines)
                if (i == 2) {
                    String[] t = firstLine.split(":", 2);
                    t[1] = t[1].trim();
                    numEntries = Integer.parseInt(t[1]);
                }
                if (i == 4) {
                    onlyJSON += firstLine; // add first JSON line
                }
            }

            // if JSON content (from 6th line) empty, return status 204
            if (firstLine.isEmpty()) {
                return "204";
            }

            // if JSON data doesn't begin with bracket
            if (!firstLine.equals("{")) {
                return "500";
            }

            // Get format HashMap from JSONParser to check validity later
            JSONParser jp = new JSONParser();
            ConcurrentHashMap<String, String> types = jp.getFeedTypes();
            String[] temp;

            // check the JSON in the PUT message for coherence
            int i = 0;
            while (i < numEntries) {
                firstLine = reader.readLine();
                onlyJSON += ("\n" + firstLine); // add this line to JSON string
                temp = firstLine.split(":", 2);
                temp[0] = temp[0].trim(); // trim white space
                temp[0] = temp[0].replaceAll("\"", ""); // remove quotation marks
                temp[1] = temp[1].trim();
                temp[1] = temp[1].replaceAll("\"", "");
                temp[1] = temp[1].replaceAll(",", "");

                // check if right format for the feed type
                // find the feedType in HashMap
                if ((types.get(temp[0]) != null) && (types.get(temp[0]).equals("string")) && (jp.isNumber(temp[1]))) {
                    // feedType expects string but the entry is number -> invalid JSON
                    return "500"; // incorrect format - invalid JSON
                }
                if ((types.get(temp[0]) != null) && (types.get(temp[0]).equals("int")) && (!jp.isNumber(temp[1]))) {
                    // feedType expects int but the entry is string -> invalid JSON
                    return "500"; // incorrect format - invalid JSON
                }
                ++i;
            }

            // once all data checked for validity, check for the bracket
            firstLine = reader.readLine();
            onlyJSON += ("\n" + firstLine); // add bracket line to JSON string
            if (!(firstLine.equals("}"))) {
                return "500"; // no } bracket - invalid JSON
            }

            // finally, if the JSON is valid, parse it to text file format
            String PUT = jp.JSONtoString(onlyJSON);

            // If AS' local storage file doesn't exist, return 201
            String filepath = weatherFileName;
            Path path = Paths.get(filepath);
            if (Files.exists(path) && (Files.size(path) > 0)) {
                // exists, update the file
                updateFile(PUT);
                return "200"; // return status 200
            } else {
                // doesn't exist, create new file and write to it
                System.out.println("No weather file yet - creating one now");
                PrintWriter writer = new PrintWriter(weatherFileName);
                writer.println(PUT);
                writer.flush();
                return "201"; // return status 201
            }
        // END OF PUT CODE
        } else if (lineBreakUp[0].equals("GET")) {
            type = "GET";
            String text = "";
            String JSON = "";
            // store SERVER_DATA.txt as JSON string
            Path path = Paths.get(weatherFileName);
            if (Files.exists(path) && (Files.size(path) > 0)) {
                JSONParser jp = new JSONParser();
                JSON = jp.stringToJSON(Files.readString(path));
            } else {
                // file doesn't exist
                return "EMPTY_GET";
            }
            // send JSON string to JAVA
            return JSON;
        } else {
            // else no request was sent yet, loop again
        }
        return type;
    }

    public void startTimerThread() {
        Thread checkTimer = new Thread(() -> {
            while (true) {
                // check time elapsed since last message sent
                if ((System.currentTimeMillis() - timeStamp) >= 30000) {
                    // if 30s elapsed, assume connection ended -> discard JSON and formally end socket connection
                    System.out.println("Discarding JSON that was last updated by " + entID);
                    alive = false;
                    timeStamp = System.currentTimeMillis();
                    alive = true;
                }
            }
        });
        checkTimer.setDaemon(true);
        checkTimer.start();
    }

    // if a socket connection is accepted by AS, start new thread
    // type: GET, PUT
    // status: what status to print
    public void run() {
        // New socket = new connection to AS = new Thread
        startTimerThread();
        // runs continuously until we call its stationID to stop it

        // Deal with lamport clock timing

        // loop to check for any incoming requests from the socket
        while (true) {
            // repeatedly try to get any input from this socket
            try {
                String request = checkRequestType(); // could be request or heartbeat
                if ((request != null) && !request.isEmpty()) {
                    // print the status
                    PrintWriter confirmer = new PrintWriter(receivedSocket.getOutputStream(), true);
                    if (request.equals("500")) {
                        confirmer.print("500\n");
                    } else if (request.equals("204")) {
                        confirmer.print("204\n");
                    } else if (request.equals("400")) {
                        confirmer.print("400\n");
                    } else if (request.equals("201")) {
                        confirmer.print("201\n");
                    } else if (request.equals("200")) {
                        confirmer.print("200\n");
                    } else if (request.equals("EMPTY_GET")) {
                        confirmer.print("EMPTY_GET");
                    } else {
                        // else, it is likely a GET request and request = weather data, so send it.
                        confirmer.print(request);
                    }
                    confirmer.flush();
                }
            } catch (IOException ie) {
                System.out.println(ie.getMessage());
            }
        }
    }
}



