package AggregationServer;

import JSONParser.JSONParser;
import lamport.LamportClock;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

// Isolation: No request can see one another, it only knows the AS
// Consistency: Data will be universal
// Atomicity: Requests are either processed completely or if failure occurs, undo wholly.
// Durability: Changes are only made by future requests -> results remain after crash/failure
// Causal time ordering: Relaxed ordering for better performance -> Independent threads can be concurrent

// Fault tolerance: eager release consistency when restoring state, ensures sync before starting up again

public class AggregationServer {
    private final String weatherFileName = "AggregationServer/SERVER_DATA.txt";
    private final LamportClock clock;
    private String port;
    private ServerSocket ass;

    // keeps track of content server ids
    private volatile Vector<String> stationIDs = new Vector<String>();

    // String = socket identity, Socket = reference socket
    private volatile ConcurrentHashMap<String, Socket> socketThreads = new ConcurrentHashMap<String, Socket>();

    // String = request data, Socket = reference socket
    private volatile BlockingQueue<ConcurrentHashMap.Entry<String, Socket>> requestQueue = new LinkedBlockingQueue<ConcurrentHashMap.Entry<String, Socket>>();

    // String = entry type, long = currentTimeMillis() at last update
    private volatile ConcurrentHashMap<String, Long> lastUpdateTimes = new ConcurrentHashMap<String, Long>();

    // String: entry type, String: who last updated it
    private volatile ConcurrentHashMap<String, String> whoUpdated = new ConcurrentHashMap<String, String>();

    public void getPort() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter port for Aggregation server (press enter to skip): ");
        String input = scanner.nextLine();
        if (input.isEmpty()) {
            this.port = "4567";
            return;
        } else {
            this.port = input;
            return;
        }
    }

    // Methods
    public AggregationServer() {
        clock = new LamportClock();
        getPort();
        try {
            ass = new ServerSocket(Integer.parseInt(this.port));
        } catch (IOException ie) {
            System.out.println("Couldn't establish ServerSocket: " + ie.getMessage());
            return;
        }
    }

    // remove weather data entries that were last uploaded by the specified ID
    // stationID: stationID to remove from entries, updatees: types last updated by that stationID
//    public void removeEntries(String stationID, ConcurrentHashMap<String, String> updatees) {
//        // Retrieve the data as HashMap (type, entry)
//        ConcurrentHashMap<String, String> currentData = new ConcurrentHashMap<String, String>();
//        BufferedReader reader;
//        String currLine = "";
//        String[] temp;
//        try {
//            // Check if file empty
//            Path path = Paths.get(weatherFileName);
//            if (Files.exists(path) && (Files.size(path) > 0)) {
//                // Store entire file in one go
//                String content = Files.readString(path);
//                // split content by line
//                String[] lines = content.split(System.lineSeparator());
//                String[] lineContent;
//                for (int i = 0; i < lines.length; ++i) {
//                    lineContent = lines[i].split(":", 2);
//                    currentData.put(lineContent[0], lineContent[1]);
//                }
//            } else {
//                return;
//            }
//
//            // for each updatee, remove it from the currentData hashmap
//            for (ConcurrentHashMap.Entry<String, String> curr_updatee : updatees.entrySet()) {
//                currentData.remove(curr_updatee.getKey()); // remove type (and entry) from currentData
//            }
//
//            // rewrite weather file with new data
//            PrintWriter pw = new PrintWriter(weatherFileName);
//            for (ConcurrentHashMap.Entry<String, String> curr_entry : currentData.entrySet()) {
//                pw.println(curr_entry.getKey() + ":" + curr_entry.getValue());
//            }
//            pw.flush();
//        } catch (IOException fnfe) {
//            System.out.println("Error: Failed to retrieve weather file: " + fnfe.getMessage());
//            return;
//        }
//    }

    // Checking if cleanup is needed for threads HashMap
//    public void startCheckThread() {
//        // special thread to check through threads
//        Thread checkThreads = new Thread(() -> {
//            // check keepAlive status of each thread
//            while (true) {
//                try {
//                    Thread.sleep(1000); // 1-second period for thread to avoid resource overload
//                } catch (InterruptedException ie) {
//                    System.out.println(ie.getMessage());
//                }
//                if (!(socketThreads.isEmpty())) {
//                    for (ConcurrentHashMap.Entry<String, Socket> curr_thread : socketThreads.entrySet()) {
//                        // check for threads that haven't communicated
//                        if (!(curr_thread.getValue().isConnected())) { // if thread not alive
//                            removeEntries(curr_thread.getKey(), curr_thread.getValue().getUpdatees()); // remove entries uploaded by the entity
//                            //socketThreads.remove(curr_thread.getKey()); // remove thread from threads HashMap
//                            System.out.println("Content from " + curr_thread.getKey() + " was deleted.");
//                        }
//                    }
//                }
//            }
//        });
//        checkThreads.setDaemon(true);
//        checkThreads.start();
//    }

    // Scanning for END from user
    public void startScanThread() {
        Thread scanThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            String line = "";
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    System.out.println(ie.getMessage());
                }
                line = scanner.nextLine();
                if ((line != null) && (line.equals("END"))) {
                    try {
                        ass.close();
                        return;
                    } catch (IOException ie) {
                        System.out.println("Termination failure: " + ie.getMessage());
                    }
                }
            }
        });
        scanThread.start();
    }

    // Continuously listen for incoming connections
    public void listenForConnections() {
        Thread listen = new Thread(() -> {
            // loop to constantly accept any new connections
            while (true) {
                Socket sc;
                try {
                    if ((sc = ass.accept()) != null) {
                        // get the type and ID of the entity on other end of socket
                        BufferedReader identifier = new BufferedReader(new InputStreamReader(sc.getInputStream()));
                        String identity = identifier.readLine(); // stationID
                        stationIDs.add(identity); // add to records of stationIDs

                        socketThreads.put(identity, sc);
                        System.out.println("Connection established for " + identity + "\n");
                        listenForRequests(sc, identity); // creates a new thread to listen for requests
                    }
                } catch (IOException ie) {
                    System.out.println("Failed to accept incoming socket: " + ie.getMessage());
                }
            }
        });
        listen.setDaemon(true);
        listen.start();
    }

    // loop for each thread to listen for requests
    public void listenForRequests(Socket socket, String identity) {
        Thread listenRequests = new Thread(() -> {
            // loop to check for any incoming requests from the socket
            BufferedReader requestInput;
            String request = "";
            while (true) {
                try {
                    requestInput = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    if (((request = requestInput.readLine()) != null) && (!request.isEmpty())) { // first line
                        // if we received a request, put request data in string, store in requestqueue
                        String requestData = request + "\n";
                        // read first four lines, get length from fourth line
                        Integer numLines = 0;
                        for (int i = 0; i < 4; ++i) {
                            request = requestInput.readLine();
                            requestData += (request + "\n");
                            if (i == 2) { // Content-Length
                                String[] temp = request.split(":", 2);
                                if (temp.length > 1) {
                                    temp[1] = temp[1].trim();
                                    numLines = Integer.parseInt(temp[1]);
                                }
                            }
                        }
                        while (numLines > 0) {
                            requestData += (requestInput.readLine() + "\n");
                            numLines--;
                        }
                        // add stationID as first line of request data for convenience
                        String copy = requestData;
                        requestData = "";
                        requestData += (identity + "\n" + copy);
                        boolean uploaded = requestQueue.offer(new AbstractMap.SimpleEntry<>(requestData, socket)); // add data and socket to requestQueue
                        if (uploaded) {
                            System.out.println("Added new request to queue");
                        } else {
                            System.out.println("Failed to add new request to queue");
                        }
                    }
                } catch (IOException ie) {
                    System.out.println(ie.getMessage());
                }
            }
        });
        listenRequests.setDaemon(true);
        listenRequests.start();
    }

    // timestamp occurs when: when socket first connects and whenever a request is executed by weather server
    public void checkUpdateTimes() {
        Thread checkTimes = new Thread(() -> {
            while (true) {
                // search through last updated time for each entry type
                if (!(lastUpdateTimes.isEmpty())) {
                    for (ConcurrentHashMap.Entry<String, Long> curr : lastUpdateTimes.entrySet()) {
                        // if current time - last updated time of entry > 30000, delete the entry
                        if ((System.currentTimeMillis() - curr.getValue()) >= 30000) {
                            lastUpdateTimes.remove(curr.getKey());
                            // rewrite the weather data file
                            try {
                                // get weather data types into hashmap, compare with lastUpdateTimes, if not there, remove
                                // and then rewrite from weather hashmap
                                Path path = Paths.get(weatherFileName);
                                String weatherData = Files.readString(path);
                                String[] lines = weatherData.split(System.lineSeparator());
                                String[] currLine;
                                HashMap<String, String> currentData = new HashMap<String, String>();
                                for (int i = 0; i < lines.length; ++i) {
                                    currLine = lines[i].split(":", 2);
                                    currentData.put(currLine[0], currLine[1]);
                                }
                                // compare
                                for (HashMap.Entry<String, String> curr_data : currentData.entrySet()) {
                                    if (lastUpdateTimes.get(curr_data.getKey()) == null) {
                                        // remove from current data hashmap
                                        currentData.remove(curr_data.getKey());
                                    }
                                }
                                // rewrite data file
                                PrintWriter pw = new PrintWriter(weatherFileName);
                                for (HashMap.Entry<String, String> curr_data : currentData.entrySet()) {
                                    pw.println(curr_data.getKey() + ":" + curr_data.getValue());
                                }
                                pw.flush();
                                System.out.println("Expired content detected. Content removed.");
                            } catch (IOException ie) {
                                System.out.println("Error removing expired content: " + ie.getMessage());
                            }
                        }
                    }
                }
            }
        });
        checkTimes.setDaemon(true);
        checkTimes.start();
    }

    // checks for new requests sent by threads in socketThreads and executes them - only 1 instance of this thread is run
    public void checkForTasks() {
        Thread checkThreads = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000); // wait 0.5 seconds before doing a task
                } catch (InterruptedException ie) {
                    System.out.println("Thread checking error: delay fault " + ie.getMessage());
                }
                if (!requestQueue.isEmpty()) {
                    for (ConcurrentHashMap.Entry<String, Socket> curr_request : requestQueue) { // for each request in the queue
                        // Get the stationID from the data
                        String[] lines = curr_request.getKey().split(System.lineSeparator());
                        String stationID = lines[0];

                        // remove first line from data
                        String requestData = "";
                        for (int i = 1; i < lines.length; ++i) {
                            requestData += (lines[i]);
                            if (i != (lines.length - 1)) {
                                requestData += ("\n");
                            }
                        }
                        executeRequest(requestData, curr_request.getValue(), stationID);
                        requestQueue.remove(); // remove request from queue
                    }
                }
            }
        });
        checkThreads.setDaemon(true);
        checkThreads.start();
    }

    // to execute the task (not a thread, to achieve blocking)
    public void executeRequest(String requestData, Socket referenceSocket, String ID) {
        PrintWriter socketOutput;
        try { // Check socket works
            socketOutput = new PrintWriter(referenceSocket.getOutputStream(), true);
        } catch (IOException ie) {
            System.out.println("Error executing request - Couldn't get socket's output stream: " + ie.getMessage());
            return;
        }
        if (requestData.isEmpty()) { // check that string isn't empty and socket isn't null
            socketOutput.println("204");
            socketOutput.flush();
            return;
        }

        // see if its PUT or GET request, isolate the PUT data if its PUT
        String[] requestLines = requestData.split("\r?\n");
        String[] currLine = requestLines[0].split(" ", 3); // first line
        if (currLine[0].equals("PUT")) {
            // skip straight to 6th line, on 1st line currently
            if (requestLines.length > 5) { // extra check to avoid bounds error
                if (!(requestLines[5].equals("{")) || !(requestLines[requestLines.length - 1].equals("}"))) { // check for { and }
                    socketOutput.println("500");
                    socketOutput.flush();
                    return;
                }
                JSONParser jp = new JSONParser();
                ConcurrentHashMap<String, String> types = jp.getFeedTypes();
                String[] lineElements; // [0] = type, [1] = data
                String PUT_DATA = "";
                for (int i = 6; i < requestLines.length - 1; ++i) { // check through each line for coherence except last (} line)
                    // convert to regular text
                    lineElements = requestLines[i].split(":", 2);
                    lineElements[0] = lineElements[0].trim();
                    lineElements[0] = lineElements[0].replace("\"", "");
                    lineElements[1] = lineElements[1].trim();
                    lineElements[1] = lineElements[1].replaceAll(",", "");
                    lineElements[1] = lineElements[1].replaceAll("\"", "");

                    if ((types.get(lineElements[0]) != null) && (types.get(lineElements[0]).equals("string")) && jp.isNumber(lineElements[1])) {
                        // if type is string, but the value is int
                        socketOutput.println("500");
                        socketOutput.flush();
                        return;
                    }
                    if ((types.get(lineElements[0]) != null) && (types.get(lineElements[0]).equals("int")) && (!jp.isNumber(lineElements[1]))) {
                        // if type is int, but the value is string
                        socketOutput.println("500");
                        socketOutput.flush();
                        return;
                    }
                    PUT_DATA += (lineElements[0] + ":" + lineElements[1] + "\n");
                    lastUpdateTimes.put(lineElements[0], System.currentTimeMillis()); // also add the types and their timestamps
                }
                Path path = Paths.get(weatherFileName);
                try {
                    if (Files.exists(path) && (Files.size(path) > 0)) {
                        updateFile(PUT_DATA, ID); // JSON is valid, update the file with this data
                        socketOutput.println("200");
                        socketOutput.flush();
                        return;
                    } else { // else create and print to new file, return 201
                        System.out.println("No weather file yet - creating one now");
                        PrintWriter writer = new PrintWriter(weatherFileName);
                        writer.println(PUT_DATA);
                        writer.flush();
                        socketOutput.println("201");
                        socketOutput.flush();
                        return;
                    }
                } catch (IOException ie) {
                    System.out.println("Error trying to reach server weather data: " + ie.getMessage());
                    return;
                }
            } else {
                socketOutput.println("204"); // empty JSON
                socketOutput.flush();
                return;
            }
        } else if (currLine[0].equals("GET")) {

        } else {

        }
    }

    // updates the weather file with the given data (in regular text entry format), ID = stationID
    public void updateFile(String entries, String ID) {
        // first, check the file exists
        Path path = Paths.get(weatherFileName);
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
        ConcurrentHashMap<String, String> currentWeatherData = new ConcurrentHashMap<String, String>();
        try { // first, turn the weather file into string HashMap (type, entry)
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
                            whoUpdated.put(new_entry.getKey(), ID);
                        } else {
                            // else, replace existing author with new author (entID)
                            whoUpdated.replace(new_entry.getKey(), ID);
                        }
                    }
                }
            }

            // check for values remaining (ie, they arent currently in the weather data
            if (!(newEntries.isEmpty())) {
                currentWeatherData.putAll(newEntries);
                for (ConcurrentHashMap.Entry<String, String> new_entry : newEntries.entrySet()) {
                    whoUpdated.put(new_entry.getKey(), ID);
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

    public static void main(String[] args) {
        AggregationServer aggr = new AggregationServer();
        aggr.startScanThread();
        aggr.checkForTasks(); // continuously check threads for incoming requests
        aggr.listenForConnections(); // continuously listen for incoming connections
    }

}

