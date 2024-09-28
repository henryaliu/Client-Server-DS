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

public class AggregationServer implements Serializable {
    // Provides a common serialisation ID across all servers/entities
    @Serial
    private static final long serialVersionUID = 4567L;

    private final String weatherFileName = "AggregationServer/SERVER_DATA.txt";
    private final LamportClock clock;
    private String port;
    private ServerSocket ass;

    // keeps track of content server ids
    private volatile Vector<String> stationIDs = new Vector<String>();

    // String = socket identity, Socket = reference socket
    private volatile ConcurrentHashMap<String, Socket> socketThreads = new ConcurrentHashMap<String, Socket>();

    private volatile ConcurrentHashMap<Socket, ArrayList<ObjectStreamConstants>> streams = new ConcurrentHashMap<>();

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

    // Scanning for END from user
    public void startScanThread() {
        Thread scanThread = new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            String line = "";
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    System.out.println("Scanning thread error: " + ie.getMessage());
                }
                line = scanner.nextLine();
                if ((line != null) && (line.equals("END"))) {
                    try {
                        // close all sockets
                        for (ConcurrentHashMap.Entry<String, Socket> threads : socketThreads.entrySet()) {
                            threads.getValue().close();
                        }
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
                if (!ass.isClosed()) {
                    try {
                        if ((sc = ass.accept()) != null) {
                            // get the type and ID of the entity on other end of socket
                            ObjectOutputStream out = new ObjectOutputStream(sc.getOutputStream()); // declare output stream first to avoid bugs
                            ObjectInputStream in = new ObjectInputStream(sc.getInputStream());
                            String identity = "";
                            try {
                                identity = (String) in.readObject();
                            } catch (ClassNotFoundException cnfe) {
                                System.out.println("Connection attempt denied: failed to read input stream from socket (" + cnfe.getMessage() + ")");
                                continue;
                            }

                            if (stationIDs.contains(identity)) {
                                System.out.println("Connection attempt denied: an entity with this ID already exists!");
                                continue;
                            }

                            stationIDs.add(identity); // add to records of stationIDs

                            socketThreads.put(identity, sc);
                            ArrayList<ObjectStreamConstants> objstreams = new ArrayList<>(); // array that stores output, and input streams
                            objstreams.add(out);
                            objstreams.add(in);
                            streams.put(sc, objstreams);
                            System.out.println("Connection established for " + identity + "\n");
                            listenForRequests(sc, identity); // creates a new thread to listen for requests
                        }
                    } catch (IOException ie) {
                        if (!ass.isClosed()) {
                            System.out.println("Failed to accept incoming socket: " + ie.getMessage());
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
                        String[] firstLineWords = firstLine.split(" ", 3);

                        // take a peek at the first key to make sure its a valid request: PUT/GET
                        if (!firstLineWords[0].equals("PUT") && !firstLineWords[0].equals("GET")) {
                            System.out.println("A request was received but was invalid (Not a PUT/GET)");
                            // send back status 400
                            ((ObjectOutputStream) objstreams.getFirst()).writeObject("400");
                            ((ObjectOutputStream) objstreams.getFirst()).flush();
                            return;
                        }
                        // Add stationID to top of line
                        wholeString = (identity + "\n" + wholeString);
                        System.out.println(wholeString);
                        boolean uploaded = requestQueue.offer(new AbstractMap.SimpleEntry<>(wholeString, socket)); // add data and socket to requestQueue
                        if (uploaded) {
                            System.out.println("Added new request to queue");
                        } else {
                            System.out.println("Error: something went wrong when adding a new request to the queue");
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {
                    System.out.println("Error reading request: " + e.getMessage());
                    return;
                }
            }
        });
        listenRequests.setDaemon(true);
        listenRequests.start();
    }

    // timestamp occurs when: when socket first connects and whenever a request is executed by weather server (only 1 instance)
    public void checkUpdateTimes() {
        Thread checkTimes = new Thread(() -> {
            while (true) {
                // search through last updated time for each entry type
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    System.out.println("Timer thread interrupted: " + ie.getMessage());
                }
                boolean removed = false;
                if (!(lastUpdateTimes.isEmpty())) {
                    for (ConcurrentHashMap.Entry<String, Long> curr : lastUpdateTimes.entrySet()) {
                        // if current time - last updated time of entry > 30000, delete the entry
                        if ((System.currentTimeMillis() - curr.getValue()) >= 30000) {
                            removed = true;
                            lastUpdateTimes.remove(curr.getKey());
                        }
                    }
                    if (removed) {
                        // rewrite the weather data file
                        try {
                            // get weather data types into hashmap, compare with lastUpdateTimes, if not there, remove
                            // and then rewrite from weather hashmap
                            Path path = Paths.get(weatherFileName);
                            String weatherData = Files.readString(path);
                            String[] lines = weatherData.split(System.lineSeparator());
                            String[] currLine;
                            ConcurrentHashMap<String, String> currentData = new ConcurrentHashMap<String, String>(); // to print
                            if (lines.length >= 1) { // loop through all lines of the weather data
                                for (int i = 0; i < lines.length; ++i) {
                                    currLine = lines[i].split(":", 2);
                                    currentData.put(currLine[0], currLine[1]);
                                }
                            }
                            // compare
                            for (ConcurrentHashMap.Entry<String, String> curr_data : currentData.entrySet()) {
                                if (lastUpdateTimes.get(curr_data.getKey()) == null) {
                                    // remove the expired content from copy of the current data
                                    currentData.remove(curr_data.getKey());
                                }
                            }
                            // rewrite data file
                            PrintWriter pw = new PrintWriter(weatherFileName);
                            for (ConcurrentHashMap.Entry<String, String> curr_data : currentData.entrySet()) {
                                pw.println(curr_data.getKey() + ":" + curr_data.getValue());
                            }
                            pw.flush();
                        } catch (IOException ie) {
                            System.out.println("Error removing expired content: " + ie.getMessage());
                        }
                        System.out.println("Expired content detected. Content removed.");
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
                    System.out.println("Delay fault in task checker: " + ie.getMessage());
                }
                if (!requestQueue.isEmpty()) {
                    for (ConcurrentHashMap.Entry<String, Socket> curr_request : requestQueue) { // for each request in the queue
                        // Get the stationID from the data by first splitting the string into an array of lines for convenience
                        String[] lines = curr_request.getKey().split(System.lineSeparator());
                        String stationID = lines[0]; // only for PUT

                        // remove first line from data
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
                    }
                }
            }
        });
        checkThreads.setDaemon(true);
        checkThreads.start();
    }

    // to execute the task (not a thread, to achieve blocking)
    public void executeRequest(String requestData, Socket referenceSocket, String ID) {
        ArrayList<ObjectStreamConstants> objstreams = streams.get(referenceSocket);
        try {
            if (requestData.isEmpty()) { // check that string isn't empty and socket isn't null
                ((ObjectOutputStream) objstreams.getFirst()).writeObject("204");
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }

            // see if its PUT or GET request, isolate the PUT data if its PUT
            String[] requestLines = requestData.split("\\r?\\n");
            String[] currLine = requestLines[0].split(" ", 3); // first line
            if (currLine[0].equals("PUT")) {
                executePUT(requestData, referenceSocket, ID); // execute put request
                return;
            } else if (currLine[0].equals("GET")) {
                executeGET(requestData, referenceSocket, ID);
                return;
            } else {
                System.out.println("Unidentifiable request - No action took place");
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
            // skip straight to 6th line, on 1st line currently
            if (requestLines.length > 5) { // extra check to avoid bounds error
                if (!(requestLines[5].equals("{")) || !(requestLines[requestLines.length - 1].equals("}"))) { // check for { and }
                    ((ObjectOutputStream) objstreams.getFirst()).writeObject("500");
                    ((ObjectOutputStream) objstreams.getFirst()).flush();
                    return;
                }
                JSONParser jp = new JSONParser();
                ConcurrentHashMap<String, String> types = jp.getFeedTypes();
                String[] lineElements; // [0] = type, [1] = data
                String PUT_DATA = "";
                ConcurrentHashMap<String, String> tempForUpdatees = new ConcurrentHashMap<String, String>();
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
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject("500");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    }
                    if ((types.get(lineElements[0]) != null) && (types.get(lineElements[0]).equals("int")) && (!jp.isNumber(lineElements[1]))) {
                        // if type is int, but the value is string
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject("500");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    }
                    PUT_DATA += (lineElements[0] + ":" + lineElements[1] + "\n");
                    lastUpdateTimes.put(lineElements[0], System.currentTimeMillis()); // also add the types and their timestamps
                    tempForUpdatees.put(lineElements[0], ID);
                }
                Path path = Paths.get(weatherFileName);
                try {
                    if (Files.exists(path) && (Files.size(path) > 0)) {
                        updateFile(PUT_DATA, ID); // JSON is valid, update the file with this data
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject("200");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    } else { // else create and print to new file, return 201
                        System.out.println("No weather file yet - creating one now");
                        PrintWriter writer = new PrintWriter(weatherFileName);
                        writer.println(PUT_DATA);
                        writer.flush();
                        writer.close();

                        whoUpdated.putAll(tempForUpdatees);
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject("201");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    }
                } catch (IOException ie) {
                    System.out.println("Error trying to reach server weather data: " + ie.getMessage());
                    return;
                }
            } else {
                ((ObjectOutputStream) objstreams.getFirst()).writeObject("204"); // empty JSON
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }
        } catch (IOException ie) {
            System.out.println("Error executing request - Couldn't get socket's output stream: " + ie.getMessage());
            return;
        }
    }

    // ID = GETClient ID NOT stationID
    public void executeGET(String requestData, Socket referenceSocket, String ID) {
        // Read text file
        ArrayList<ObjectStreamConstants> objstreams = streams.get(referenceSocket);
        Path path = Paths.get(weatherFileName);
        try {
            if ((!Files.exists(path)) || (Files.size(path) == 0)) { // if weather data empty
                ((ObjectOutputStream) objstreams.getFirst()).writeObject("204");
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

            // convert to JSON, ready to send over
            JSONParser jp = new JSONParser();
            String weatherData = Files.readString(path);
            String weatherDataJSON = jp.stringToJSON(weatherData);

            if (stationID.equals("all")) { // if stationID = all, return all data
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(weatherDataJSON);
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }
            // otherwise, search through weather file
            // put weather data lines into hashmap, entry and value separated
            String[] lines = weatherData.split(System.lineSeparator());
            String[] currLine;
            ConcurrentHashMap<String, String> weatherDataLines = new ConcurrentHashMap<String, String>();
            for (int i = 0; i < lines.length; ++i) {
                currLine = lines[i].split(":", 2);
                weatherDataLines.put(currLine[0], currLine[1]);
            }

            for (ConcurrentHashMap.Entry<String, String> curr_updatee : whoUpdated.entrySet()) { // see who to remove based off stationID
                if (!(curr_updatee.getValue().equals(stationID))) {
                    weatherDataLines.remove(curr_updatee.getKey());
                }
            }
            // put back into String
            String necessaryData = "";
            for (ConcurrentHashMap.Entry<String, String> curr_entry : weatherDataLines.entrySet()) {
                necessaryData += (curr_entry.getKey() + ":" + curr_entry.getValue() + "\n");
            }
            if (necessaryData.isEmpty()) {
                ((ObjectOutputStream) objstreams.getFirst()).writeObject("204");
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            } else {
                necessaryData = jp.stringToJSON(necessaryData);
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(necessaryData);
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }
        } catch (IOException ie) {
            System.out.println("Error trying to fetch server weather data: " + ie.getMessage());
            return;
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
        ID = ID.replaceAll("CS", "");
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
                        whoUpdated.put(new_entry.getKey(), ID);
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

