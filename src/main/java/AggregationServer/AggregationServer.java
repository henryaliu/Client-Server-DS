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

// Isolation: No request can see one another, it only knows the Aggregation Server and the universal request message protocol
// Consistency: Weather data is universal across all threads and entities connecting to the Aggregation server
// Atomicity: Changes occur at once. If failure occurs, abort changes entirely (i.e. revert to previous working version).
// Durability: Changes are only made by current and future requests -> results remain after crash/failure
// Causal time ordering: Relaxed ordering for better performance -> Independent threads can be concurrent but counted in Lamport time

public class AggregationServer implements Serializable {
    // Provides a universal serialisation ID across all servers/entities
    @Serial
    private static final long serialVersionUID = 4567L;

    // Constant weather data file path and file name
    private volatile LamportClock clock;
    private String port;
    private ServerSocket ass;

    // Stores the IDs of all entities that are connected
    private volatile Vector<String> stationIDs = new Vector<String>();

    // Stores individual threads for each socket (Content Server or Client) connected to the server
    // String = ID of the entity, Socket = entity's socket
    private volatile ConcurrentHashMap<String, Socket> socketThreads = new ConcurrentHashMap<String, Socket>();

    // Stores pairs of serialised Object Streams for each socket connected to the server
    // Both streams are put into an ArrayList so only one HashMap is needed for retrieving the output/input streams
    private volatile ConcurrentHashMap<Socket, ArrayList<ObjectStreamConstants>> streams = new ConcurrentHashMap<>();

    // Concurrent thread-safe queue for storing requests accepted by the server
    // String = request data (e.g. JSON data), Socket = entity's socket
    private volatile BlockingQueue<ConcurrentHashMap.Entry<String, Socket>> requestQueue = new LinkedBlockingQueue<ConcurrentHashMap.Entry<String, Socket>>();

    // Concurrent thread-safe Hash table which stores pairs of the weather data entry (type), and the last time the entry was updated
    // Updated whenever PUT request or weather data cleanup is conducted
    // String = entry type, long = currentTimeMillis() at last update
    private volatile ConcurrentHashMap<String, Long> lastUpdateTimes = new ConcurrentHashMap<String, Long>(); // TO REMOVE

    // Concurrent thread-safe Hash table which stores pairs of the weather data entry (type), and the ID of who last updated it
    // String: entry type, String: who last updated it
    private volatile ConcurrentHashMap<String, String> whoUpdated = new ConcurrentHashMap<String, String>(); // TO REMOVE

    // Stores the files and their updated times
    // A function uses this to compare with latest time, removing it if it is older than 30 seconds or if limit 20 exceeded and the oldest is removed
    // String: weather data file path/name, Long: currentTimeMillis() of when it was added (remove when older than 30s from current time)
    private volatile ConcurrentHashMap<String, Long> currentFiles = new ConcurrentHashMap<String, Long>();

    // Asks the user in the terminal to declare a port for the aggregation server, stores this data in member variables
    public void getPort() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter the port for this Aggregation server (press enter to skip): ");
        String input = scanner.nextLine();
        if (input.isEmpty()) {
            this.port = "4567"; // default port is 4567
            return;
        } else {
            this.port = input;
            try {
                Integer test = Integer.parseInt(this.port);
            } catch (NumberFormatException nfe) {
                System.out.println("INVALID PORT: Not a number. Please retry.");
                getPort();
            }
            return;
        }
    }

    // Constructor: initialises Lamport clock, determine the server port, establishes ServerSocket, cleans weather file
    // Not threaded: runs only once at the beginning to prepare the server
    public AggregationServer() {
        clock = new LamportClock();
        getPort();
        clock.updateTime();

        int attempts = 1;
        while (attempts <= 6) { // Allow the server 5 attempts to retry creating a ServerSocket
            try {
                ass = new ServerSocket(Integer.parseInt(this.port));
                clock.updateTime();
                break;
            } catch (IOException ie) {
                attempts++;
                System.out.println("Attempt " + attempts + ": couldn't establish ServerSocket. Retrying..." + ie.getMessage());
                if (attempts == 6) {
                    System.out.println(attempts + " attempts failed. Aggregation server is aborted.");
                    return;
                }
            }
        }
        System.out.println("Aggregation server is ONLINE.");
        return;
    }

    // Threaded function (runs in background): checks if user has typed END in terminal to turn off the server
    // Only one instance of this thread is ever called. This is the MAIN thread; If it ends, all server operations/threads end.
    public void startScanThread() {
        Thread scanThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            String line = "";
            while (true) {
                try {
                    Thread.sleep(100); // Short periodic rest to avoid resource-overload due to the while loop
                } catch (InterruptedException ie) {
                    System.out.println("Scanning thread error: " + ie.getMessage());
                }
                line = scanner.nextLine();
                if ((line != null) && (line.equals("END"))) {
                    try {
                        for (ConcurrentHashMap.Entry<String, Socket> threads : socketThreads.entrySet()) {
                            threads.getValue().close(); // safely close all sockets
                        }
                        ass.close();
                        clock.updateTime();
                        return;
                    } catch (IOException ie) {
                        System.out.println("Termination failure, please try again: " + ie.getMessage());
                    }
                }
            }
        });
        scanThread.start();
    }

    // Threaded function (runs in background): Continuously listen for incoming socket connections from entities
    // Once socket is accepted, output and input streams are created for it, clock is updated, and its data is registered
    // Only one instance of this function calls.
    public void listenForConnections() {
        Thread listen = new Thread(() -> {
            // loop to constantly accept any new connections
            while (true) {
                Socket sc;
                if (!ass.isClosed()) {
                    try {
                        if ((sc = ass.accept()) != null) {
                            clock.updateTime(); // socket accepted = 1 event
                            ObjectOutputStream out = new ObjectOutputStream(sc.getOutputStream()); // declare output stream first to avoid bugs
                            ObjectInputStream in = new ObjectInputStream(sc.getInputStream());
                            String socketData = "";
                            try {
                                socketData = (String) in.readObject(); // collects the ID of the entity who connected
                            } catch (ClassNotFoundException cnfe) {
                                System.out.println("Connection attempt denied: failed to read input stream from socket (" + cnfe.getMessage() + ")");
                                continue;
                            }

                            String[] socketDataSplitted = socketData.split(System.lineSeparator());
                            if (socketDataSplitted.length < 2) {
                                System.out.println("Connection attempt denied: not enough info was provided");
                                continue;
                            }

                            clock.processEvent(Integer.parseInt(socketDataSplitted[0])); // tie-break of socket time and local time

                            String identity = socketDataSplitted[1];
                            if (stationIDs.contains(identity)) {
                                System.out.println("Connection attempt denied: an entity with this ID already exists!");
                                continue;
                            }

                            stationIDs.add(identity); // Adds the client ID to the server's records

                            socketThreads.put(identity, sc);
                            ArrayList<ObjectStreamConstants> objstreams = new ArrayList<>();
                            objstreams.add(out);
                            objstreams.add(in);
                            streams.put(sc, objstreams);
                            System.out.println("Connection established for " + identity + "\n");

                            clock.updateTime(); // socket data added to the server = 1 event
                            listenForRequests(sc, identity); // creates a new thread to listen for requests
                        }
                    } catch (IOException ie) {
                        if (!ass.isClosed()) {
                            System.out.println("Failed to accept incoming socket: " + ie.getMessage());
                            clock.updateTime();
                        }
                    }
                } else {
                    return;
                }
            }
        });
        listen.setDaemon(true);
        listen.start();
    }

    // loop for each thread to listen for requests, checks if the request is a valid request
    public void listenForRequests(Socket socket, String identity) {
        Thread listenRequests = new Thread(() -> {
            // loop to check for any incoming requests from the socket
            ArrayList<ObjectStreamConstants> objstreams = streams.get(socket);
            String firstLine = "";
            while ((!socket.isClosed()) && (objstreams.get(1) != null)) {
                // objstreams.get(1) = ObjectInputStream for the socket
                try {
                    String wholeString = (String) ((ObjectInputStream) objstreams.get(1)).readObject();
                    String[] requestLines = wholeString.split(System.lineSeparator());
                    if ((firstLine = requestLines[0]) != null && (!firstLine.isEmpty())) {
                        // take a peek at the first key to make sure it is a valid request: PUT/GET
                        if (requestLines.length > 4) { // PUT > 4 lines -> first line is timestamp
                            clock.processEvent(Integer.parseInt(requestLines[0]));
                            // remove timestamp (first line)
                            wholeString = "";
                            for (int i = 1; i < requestLines.length; ++i) {
                                wholeString += requestLines[i];
                                if (i != (requestLines.length-1)) {
                                    wholeString += "\n";
                                }
                            }
                            requestLines = wholeString.split(System.lineSeparator());
                        }

                        firstLine = requestLines[0];
                        String[] firstLineWords = firstLine.split(" ", 3);

                        if (!firstLineWords[0].equals("PUT") && !firstLineWords[0].equals("GET")) {
                            System.out.println("A request was received but was invalid (Not a PUT/GET)");
                            // send back status 400
                            clock.updateTime();
                            ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "400");
                            ((ObjectOutputStream) objstreams.getFirst()).flush();
                            return;
                        }
                        // Add stationID to top of line
                        wholeString = (identity + "\n" + wholeString);
                        boolean uploaded = requestQueue.offer(new AbstractMap.SimpleEntry<>(wholeString, socket)); // add data and socket to requestQueue
                        if (uploaded) {
                            System.out.println("Added new request to queue");
                            clock.updateTime();
                        } else {
                            System.out.println("Error: something went wrong when adding a new request to the queue");
                            clock.updateTime();
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {
                    System.out.println("Error reading request: " + e.getMessage());
                    clock.updateTime();
                    return;
                }
            }
        });
        listenRequests.setDaemon(true);
        listenRequests.start();
    }

    // timestamp occurs when: when socket first connects and whenever a request is executed by weather server (only 1 instance)
//    public void checkUpdateTimes() {
//        Thread checkTimes = new Thread(() -> {
//            while (true) {
//                // search through last updated time for each entry type
//                try {
//                    Thread.sleep(10);
//                } catch (InterruptedException ie) {
//                    System.out.println("Timer thread interrupted: " + ie.getMessage());
//                    clock.updateTime();
//                    continue;
//                }
//                boolean removed = false;
//                if (!(lastUpdateTimes.isEmpty())) {
//                    for (ConcurrentHashMap.Entry<String, Long> curr : lastUpdateTimes.entrySet()) {
//                        // if current time - last updated time of entry > 30000, delete the entry
//                        if ((System.currentTimeMillis() - curr.getValue()) >= 30000) {
//                            removed = true;
//                            lastUpdateTimes.remove(curr.getKey());
//                        }
//                    }
//                    if (removed) {
//                        // rewrite the weather data file
//                        try {
//                            // get weather data types into hashmap, compare with lastUpdateTimes, if not there, remove
//                            // and then rewrite from weather hashmap
//                            Path path = Paths.get(weatherFileName);
//                            String weatherData = Files.readString(path);
//                            String[] lines = weatherData.split(System.lineSeparator());
//                            String[] currLine;
//                            ConcurrentHashMap<String, String> currentData = new ConcurrentHashMap<String, String>(); // to print
//                            if (lines.length >= 1) { // loop through all lines of the weather data
//                                for (int i = 0; i < lines.length; ++i) {
//                                    currLine = lines[i].split(":", 2);
//                                    currentData.put(currLine[0], currLine[1]);
//                                }
//                            }
//                            // compare
//                            for (ConcurrentHashMap.Entry<String, String> curr_data : currentData.entrySet()) {
//                                if (lastUpdateTimes.get(curr_data.getKey()) == null) {
//                                    // remove the expired content from copy of the current data
//                                    currentData.remove(curr_data.getKey());
//                                }
//                            }
//                            // rewrite data file
//                            PrintWriter pw = new PrintWriter(weatherFileName);
//                            for (ConcurrentHashMap.Entry<String, String> curr_data : currentData.entrySet()) {
//                                pw.println(curr_data.getKey() + ":" + curr_data.getValue());
//                            }
//                            pw.flush();
//                        } catch (IOException ie) {
//                            System.out.println("Error removing expired content: " + ie.getMessage());
//                        }
//                        System.out.println("Expired content detected. Content removed.");
//                        clock.updateTime(); // *** file updated = 1 event
//                    }
//                }
//            }
//        });
//        checkTimes.setDaemon(true);
//        checkTimes.start();
//    }

    public void checkUpdateTimes() {
        Thread checkTimes = new Thread(() -> {
            while (true) {
                boolean removed = false;
                for (ConcurrentHashMap.Entry<String, Long> curr_file : currentFiles.entrySet()) { // Removing all expired content
                    if ((System.currentTimeMillis() - curr_file.getValue()) > 30000) {
                        currentFiles.remove(curr_file.getKey());
                        File currentFile = new File(curr_file.getKey());
                        if (currentFile.delete()) {
                            removed = true;
                        } else {
                            System.out.println("Failed to remove expired content. Trying again...");
                        }
                    }
                }

                if (removed) {
                    System.out.println("Expired content detected. Content removed.");
                }

                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    System.out.println("Timer thread interrupted: " + ie.getMessage());
                    clock.updateTime();
                    continue;
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
                    System.out.println("Delay fault in task checker: " + ie.getMessage());
                }
                if (!requestQueue.isEmpty()) {
                    for (ConcurrentHashMap.Entry<String, Socket> curr_request : requestQueue) { // for each request in the queue
                        // Get the stationID from the data by first splitting the string into an array of lines for convenience
                        String[] lines = curr_request.getKey().split(System.lineSeparator());
                        String stationID = lines[0]; // only for PUT

                        // remove first line from data if PUT
                        String requestData = "";
                        for (int i = 1; i < lines.length; ++i) {
                            requestData += (lines[i]);
                            if (i != (lines.length - 1)) {
                                requestData += ("\n");
                            }
                        }
                        // execute the request
                        executeRequest(requestData, curr_request.getValue(), stationID);
                        requestQueue.remove(); // once executed, remove request from queue
                        clock.updateTime();
                    }
                }
            }
        });
        checkThreads.setDaemon(true);
        checkThreads.start();
    }

    // request: HTTPS PUT or GET request stored in String (normal format, no stationID or lamport at top)
    public boolean isValidRequest(String request) {
        if ((request == null) || request.isEmpty()) {
            return false;
        }
        String[] lines = request.split(System.lineSeparator());
        ArrayList<String> requestEntryTypes = new ArrayList<>();
        String[] firstLine = lines[0].split(" ", 3);
        for (int i = 1; i < lines.length; ++i) {
            requestEntryTypes.add(lines[i].split(":", 2)[0].trim()); // Only get the request entry type (e.g. Host)
        }
        if (!requestEntryTypes.contains("Host")) {
            return false;
        }
        if (!requestEntryTypes.contains("User-Agent")) {
            return false;
        }
        if ((firstLine[0].equals("PUT")) && !requestEntryTypes.contains("Content-Type")) {
            return false;
        }
        if ((firstLine[0].equals("PUT")) && !requestEntryTypes.contains("Content-Length")) {
            return false;
        }
        return true;
    }

    // to execute the task (not a thread, to achieve blocking)
    public void executeRequest(String requestData, Socket referenceSocket, String ID) {
        ArrayList<ObjectStreamConstants> objstreams = streams.get(referenceSocket);
        try {
            if (requestData.isEmpty() || (!isValidRequest(requestData))) { // check that string isn't empty and socket isn't null
                clock.updateTime();
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "204");
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }

            // see if its PUT or GET request, isolate the PUT data if its PUT
            String[] requestLines = requestData.split("\\r?\\n");
            String[] currLine = requestLines[0].split(" ", 3); // first line
            if (currLine[0].equals("PUT")) {
                clock.updateTime(); // PUT message counts as event;
                executePUT(requestData, referenceSocket, ID); // execute put request
                return;
            } else if (currLine[0].equals("GET")) {
                executeGET(requestData, referenceSocket, ID);
                return;
            } else {
                System.out.println("Unidentifiable request - No action took place");
                clock.updateTime();
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "500");
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }
        } catch (IOException ie) {
            System.out.println("Failed to read data from socket: " + ie.getMessage());
            return;
        }
    }

    public void executePUT(String requestData, Socket referenceSocket, String ID) {
        ArrayList<ObjectStreamConstants> objstreams = streams.get(referenceSocket);
        try { // Check socket works
            ID = ID.replaceAll("CS", "");
            String[] requestLines = requestData.split("\r?\n");
            // skip straight to 7th line, on 1st line currently
            if (requestLines.length > 6) { // extra check to avoid bounds error
                if (!(requestLines[6].equals("{")) || !(requestLines[requestLines.length - 1].equals("}"))) { // check for { and }
                    clock.updateTime();
                    ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "500");
                    ((ObjectOutputStream) objstreams.getFirst()).flush();
                    return;
                }
                JSONParser jp = new JSONParser();
                ConcurrentHashMap<String, String> types = jp.getFeedTypes();
                String[] lineElements; // [0] = type, [1] = data
                String PUT_DATA = "";
                ConcurrentHashMap<String, String> tempForUpdatees = new ConcurrentHashMap<String, String>();
                for (int i = 7; i < requestLines.length - 1; ++i) { // check through each line for coherence except last (} line)
                    // convert to regular text
                    lineElements = requestLines[i].split(":", 2);
                    lineElements[0] = lineElements[0].trim();
                    lineElements[0] = lineElements[0].replace("\"", "");
                    lineElements[1] = lineElements[1].trim();
                    lineElements[1] = lineElements[1].replaceAll(",", "");
                    lineElements[1] = lineElements[1].replaceAll("\"", "");

                    if ((types.get(lineElements[0]) != null) && (types.get(lineElements[0]).equals("string")) && jp.isNumber(lineElements[1])) {
                        // if type is string, but the value is int
                        clock.updateTime();
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "500");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    }
                    if ((types.get(lineElements[0]) != null) && (types.get(lineElements[0]).equals("int")) && (!jp.isNumber(lineElements[1]))) {
                        // if type is int, but the value is string
                        clock.updateTime();
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "500");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    }
                    PUT_DATA += (lineElements[0] + ":" + lineElements[1] + "\n");
                    lastUpdateTimes.put(lineElements[0], System.currentTimeMillis()); // also add the types and their timestamps
                    tempForUpdatees.put(lineElements[0], ID);
                }
                String weatherFileName = "AggregationServer/SERVER_DATA_" + ID + ".txt";
                Path path = Paths.get(weatherFileName);
                try {
                    if (Files.exists(path) && (Files.size(path) > 0)) {
                        updateFile(PUT_DATA, ID); // JSON is valid, update the file with this data
                        clock.updateTime();
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "200");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    } else { // else create and print to new file, return 201
                        System.out.println("No weather file yet - creating one now");
                        FileWriter temp = new FileWriter(weatherFileName);
                        temp.close();
                        PrintWriter writer = new PrintWriter(weatherFileName);
                        writer.println(PUT_DATA);
                        writer.flush();
                        writer.close();
                        currentFiles.put(weatherFileName, System.currentTimeMillis()); // Add/replace file to currentFiles hashmap
                        clock.updateTime(); // send message back
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "201");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    }
                } catch (IOException ie) {
                    System.out.println("Error trying to reach server weather data: " + ie.getMessage());
                    return;
                }
            } else {
                clock.updateTime();
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "204"); // empty JSON
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }
        } catch (IOException ie) {
            System.out.println("Error executing request - Couldn't get socket's output stream: " + ie.getMessage());
            return;
        }
    }

    // ID = GETClient ID NOT stationID
    // Not timestamped by clock until message is sent back to client
    // The timestamp is always the first line
    public void executeGET(String requestData, Socket referenceSocket, String ID) {
        // Read text file
        ArrayList<ObjectStreamConstants> objstreams = streams.get(referenceSocket);
        ID = ID.replaceAll("CS", "");
        String weatherFileName = "AggregationServer/SERVER_DATA_" + ID + ".txt";
        Path path = Paths.get(weatherFileName);
        try {
            if ((!Files.exists(path)) || (Files.size(path) == 0)) { // if weather data empty
                clock.updateTime();
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "204");
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }

            // read fourth line (index 3) for stationID to retrieve
            String[] requestLines = requestData.split("\r?\n");
            String stationID = "";
            if (requestLines.length > 3) {
                String[] fourthLine = requestLines[3].split(":", 2);
                String[] fourthLineData = fourthLine[1].split("/", 2);
                stationID = fourthLineData[0].trim();
                stationID = stationID.replaceAll("CS", "");
            }
            Path filePath = Paths.get("");
            if (stationID.equals("latest")) { // if stationID = all, return most recently uploaded data
                // search for latest
                Long latestTime = 0L; // latest time == largest long value
                Long currentTime = 0L;
                for (ConcurrentHashMap.Entry<String, Long> curr_file : currentFiles.entrySet()) {
                    if ((currentTime = curr_file.getValue()) > latestTime) {
                        latestTime = currentTime;
                        filePath = Paths.get(curr_file.getKey());
                    }
                }
            } else {
                ID = ID.replaceAll("CS", "");
                filePath = Paths.get("AggregationServer/SERVER_DATA_" + ID + ".txt");
            }
            // convert to JSON, ready to send over
            JSONParser jp = new JSONParser();
            String weatherData = Files.readString(filePath);
            String weatherDataJSON = jp.stringToJSON(weatherData);
            clock.updateTime();
            ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + weatherDataJSON);
            ((ObjectOutputStream) objstreams.getFirst()).flush();
            return;
        } catch (IOException ie) {
            System.out.println("Error trying to fetch server weather data: " + ie.getMessage());
            return;
        }
    }

    // updates the weather file with the given data (in regular text entry format), ID = stationID
    public void updateFile(String entries, String ID) {
        // first, check the file exists
        ID = ID.replaceAll("CS", "");
        String weatherFileName = "AggregationServer/SERVER_DATA_" + ID + ".txt";
        Path path = Paths.get(weatherFileName);
        try {
            if (!Files.exists(path) || Files.size(path) == 0) {
                // if it doesn't exist, simply create this file, write to it, and return
                System.out.println("No weather file yet - creating new one");
                PrintWriter pw = new PrintWriter(weatherFileName);
                pw.println(entries);
                pw.flush();
                clock.updateTime(); // *** new file created = 1 event
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
                    if (new_entry.getKey().equals(curr_entry.getKey())) { // type already exists, so replace with new entry
                        currentWeatherData.replace(new_entry.getKey(), new_entry.getValue());
                        newEntries.remove(new_entry.getKey()); // remove it from newEntries vector
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
            clock.updateTime(); // file updated - 1 event
        } catch (IOException ie) {
            System.out.println("Server error - failed to update weather data");
        }
    }

    public static void main(String[] args) {
        AggregationServer aggr = new AggregationServer();
        aggr.startScanThread();
        aggr.checkUpdateTimes();
        aggr.checkForTasks(); // continuously check threads for incoming requests
        aggr.listenForConnections(); // continuously listen for incoming connections
    }

}

