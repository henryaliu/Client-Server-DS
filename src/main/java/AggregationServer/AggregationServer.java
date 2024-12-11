package AggregationServer;

import JSONParser.JSONParser;
import lamport.LamportClock;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Scanner;
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
    private String port = "4567";
    private ServerSocket ass;

    private String fileDirectory = "AggregationServer/";

    // Stores individual threads for each socket (Content Server or Client) connected to the server
    // String = ID of the entity, Socket = entity's socket
    private volatile ConcurrentHashMap<String, Socket> socketThreads = new ConcurrentHashMap<String, Socket>();

    // Stores pairs of serialised Object Streams for each socket connected to the server
    // Both streams are put into an ArrayList so only one HashMap is needed for retrieving the output/input streams
    private volatile ConcurrentHashMap<Socket, ArrayList<ObjectStreamConstants>> streams = new ConcurrentHashMap<Socket, ArrayList<ObjectStreamConstants>>();

    // Concurrent thread-safe queue for storing requests accepted by the server
    // String = request data (e.g. JSON data), Socket = entity's socket
    private volatile BlockingQueue<ConcurrentHashMap.Entry<String, Socket>> requestQueue = new LinkedBlockingQueue<ConcurrentHashMap.Entry<String, Socket>>();

    // Stores the files and their updated times
    // Function checkUpdateTimes uses this to compare with latest time, removing it if it is older than 30 seconds or if limit 20 exceeded and the oldest is removed
    // String: weather data file path/name, Long: currentTimeMillis() of when it was added (remove when older than 30s from current time)
    private volatile ConcurrentHashMap<String, Long> currentFiles = new ConcurrentHashMap<String, Long>();

    // Stores the latest clock value of the Aggregation Server without a crash,
    // Can have many elements, each element corresponds to a Content Server's uploaded file
    // String: latest file stored as a String, Integer: latest Lamport time prior to crash
    private volatile ConcurrentHashMap<String, Integer> latestSavedFiles = new ConcurrentHashMap<String, Integer>();

    // Asks the user in the terminal to declare a port for the aggregation server, stores this data in member variables
    public void getPort() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Enter the port for this Aggregation server (press enter to skip): ");
        String input = scanner.nextLine();
        if (input.isEmpty() || input.trim().isEmpty()) {
            this.port = "4567"; // default port is 4567
            return;
        } else {
            this.port = input.trim();
            try {
                Integer test = Integer.parseInt(this.port);
            } catch (NumberFormatException nfe) {
                System.out.println("INVALID PORT: Not a number. Please retry.");
                getPort();
                return;
            }
            return;
        }
    }

    // For testing purposes
    public ServerSocket getServerSocket() {
        return ass;
    }

    // For integration tests, avoiding need for terminal input
    public void setPort(String inputPort) {
        this.port = inputPort;
    }

    // For integration tests, avoiding need for terminal input
    public void setDirectory(String directory) { this.fileDirectory = directory; }

    // For testing, to retrieve currentFiles variable
    public ConcurrentHashMap<String, Long> getCurrFiles() {
        return currentFiles;
    }

    // For testing purposes
    public BlockingQueue<ConcurrentHashMap.Entry<String, Socket>> getRequestQueue() {
        return requestQueue;
    }

    // For testing purposes, mock-function for adding update times to currentFiles
    public void addToCurrentFiles(String filePath, Long time) {
        currentFiles.put(filePath, time);
        return;
    }

    // For testing purposes, retrieving the streams hashmap
    public ConcurrentHashMap<Socket, ArrayList<ObjectStreamConstants>> getStreams() {
        return this.streams;
    }

    // For testing purposes, adding mock tasks
    public void addToRequestQueue(ConcurrentHashMap.Entry<String, Socket> request) {
        this.requestQueue.offer(request);
        return;
    }

    // Constructor: initialises Lamport clock, cleans weather file
    // Not threaded: runs only once at the beginning to prepare the server
    public AggregationServer() {
        clock = new LamportClock();

        // Special case: if the AggregationServer was run before and crashed, CS files are maintained.
        // Design of the AggregationServer replicates the Content Server data into files in the local folder
        Path path = Paths.get(fileDirectory);
        File existingFiles[] = path.toFile().listFiles();
        StringBuffer sbuffer = new StringBuffer();
        if (existingFiles != null) {
            for (File file : existingFiles) {
                if (file.isFile() && file.getName().endsWith(".txt")) {
                    currentFiles.put(file.toString(), System.currentTimeMillis());
                }
            }
        }
    }

    // Begins the server operation by establishing the server socket. Automatically retries up to 5 times
    public void beginOperation() {
        int attempts = 0;
        while (attempts <= 6) { // Allow the server 5 attempts to retry creating a ServerSocket
            try {
                ass = new ServerSocket(Integer.parseInt(this.port));
                clock.updateTime();
                break;
            } catch (IOException ie) {
                attempts++;
                System.out.println("Attempt " + attempts + ": couldn't establish ServerSocket: " + ie.getMessage());
                System.out.println("\nRetrying...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    System.out.println("Thread sleep fault - Continuing where we left off...");
                }
                if (attempts == 6) {
                    System.out.println("\n" + attempts + " attempts failed. Aggregation server is aborted. **********\n");
                    System.exit(0);
                    return;
                }
            } catch (IllegalArgumentException iae) {
                System.out.println("\n********** This port is invalid: " + iae.getMessage() + " **********\n");
                new AggregationServer();
                return;
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
            while (true) { // Loop runs in the background, listening for any incoming sockets
                Socket sc;
                if ((ass != null) && (!ass.isClosed())) {
                    try {
                        if ((sc = ass.accept()) != null) {
                            clock.updateTime(); // socket accepted = 1 event
                            ObjectOutputStream out = new ObjectOutputStream(sc.getOutputStream()); // Declare output stream first to avoid bugs
                            ObjectInputStream in = new ObjectInputStream(sc.getInputStream());
                            String socketData = "";
                            try {
                                socketData = (String) in.readObject(); // Collect information (e.g. ID) of the entity who connected
                            } catch (ClassNotFoundException cnfe) {
                                System.out.println("Connection attempt denied: failed to read input stream from socket (" + cnfe.getMessage() + ")");
                                continue;
                            }
                            // The entity has to provide its local time and ID for it to be valid
                            String[] socketDataSplitted = socketData.split("\\r?\\n");
                            if (socketDataSplitted.length < 2) {
                                System.out.println("Connection attempt denied: not enough info was provided");
                                continue;
                            }

                            clock.processEvent(Integer.parseInt(socketDataSplitted[0])); // Tie-break of socket time and local time

                            String identity = socketDataSplitted[1];

                            socketThreads.put(identity, sc); // Adds the socket to HashMap database
                            ArrayList<ObjectStreamConstants> objstreams = new ArrayList<ObjectStreamConstants>();
                            objstreams.add(out);
                            objstreams.add(in);
                            streams.put(sc, objstreams); // Adds the output and input streams to HashMap database
                            System.out.println("Connection established for " + identity + "\n");

                            clock.updateTime(); // Socket data added to the server = 1 event
                            listenForRequests(sc, identity); // Starts a background-running thread to listen for requests from this socket
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

    // Threaded function (runs in background): Continuously loop through and check for requests sent by the provided socket
    // Only called once for every socket (when it is accepted by this server)
    // If it detects a request from the socket's stream, it peeks the data for validity, and formats the data ready to be queued
    // socket = The socket the request was sent by, identity: the entity's ID
    public void listenForRequests(Socket socket, String identity) {
        Thread listenRequests = new Thread(() -> {
            ArrayList<ObjectStreamConstants> objstreams = new ArrayList<ObjectStreamConstants>(streams.get(socket));
            String firstLine = "";
            while ((!socket.isClosed()) && (!objstreams.isEmpty()) && (objstreams.get(1) != null)) { // objstreams.get(1) = ObjectInputStream for the socket
                try {
                    String wholeString = (String) ((ObjectInputStream) objstreams.get(1)).readObject(); // Get the request data as a string
                    String[] requestLines = wholeString.split("\\r?\\n"); // Splits the data into array of lines
                    if ((firstLine = requestLines[0]) != null && (!firstLine.isEmpty())) { // Take a peek at the data
                        if (requestLines.length > 4) { // If a PUT message (> 4 lines), it has a timestamp at the top that needs to be removed
                            clock.processEvent(Integer.parseInt(requestLines[0])); // Tie-break of local and received Lamport times
                            wholeString = "";
                            for (int i = 1; i < requestLines.length; ++i) { // Rebuild the request message without the timestamp
                                wholeString += requestLines[i];
                                if (i != (requestLines.length-1)) {
                                    wholeString += "\n";
                                }
                            }
                            requestLines = wholeString.split(System.lineSeparator());
                        }

                        firstLine = requestLines[0]; // Splits the first line into so that we can get PUT/GET (first word)
                        String[] firstLineWords = firstLine.split(" ", 3);

                        if (!firstLineWords[0].equals("PUT") && !firstLineWords[0].equals("GET")) { // Checks the first keyword is either PUT or GET
                            System.out.println("A request was received but was invalid (Not a PUT/GET)");
                            // Sends back status 400
                            clock.updateTime();
                            ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "400");
                            ((ObjectOutputStream) objstreams.getFirst()).flush();
                            return;
                        }
                        wholeString = (identity + "\n" + wholeString); // Adds ID of who sent the request to top of line
                        boolean uploaded = requestQueue.offer(new AbstractMap.SimpleEntry<>(wholeString, socket)); // Adds data and socket to requestQueue
                        if (uploaded) {
                            System.out.println("Added new request to queue");
                            clock.updateTime();
                        } else { // Checks for failure
                            System.out.println("Error: something went wrong when adding a new request to the queue");
                            clock.updateTime();
                        }
                    }
                } catch (IOException | ClassNotFoundException e) {
                    System.out.println("A socket connection has ended: " + e.getMessage());
                    clock.updateTime();
                    return;
                }
            }
        });
        listenRequests.setDaemon(true);
        listenRequests.start();
    }

    // Threaded function (runs-in-background): Continuously checks the currentFiles HashMap for expired content to remove
    // Only called once - only one instance exists
    // Loops through a HashMap containing files and their last updated time, compares with current time, checks if >30 seconds
    public void checkUpdateTimes() {
        Thread checkTimes = new Thread(() -> {
            while (true) {
                boolean removed = false;
                for (ConcurrentHashMap.Entry<String, Long> curr_file : currentFiles.entrySet()) { // HashMap of files and their last update time
                    if ((System.currentTimeMillis() - curr_file.getValue()) > 30000) {
                        currentFiles.remove(curr_file.getKey());
                        String[] currLine = curr_file.getKey().split("_", 2); // Get the file path and name
                        currLine[0] = currLine[0].replaceAll("SERVER_DATA_", ""); // Extracts the stationID from the file name
                        File currentFile = new File(curr_file.getKey());
                        if (currentFile.delete()) {
                            removed = true;
                        } else {
                            System.out.println("Failed to remove some expired content. Trying again..."); // Since this is a loop, it will try again
                        }
                    }
                }

                if (removed) {
                    System.out.println("Expired content detected. Content removed.");
                }

                try {
                    Thread.sleep(10); // Small break to avoid thread resource-overload
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

    // Threaded function (runs-in-background): Continuously checks the requestQueue for new tasks to execute
    // Checks for new requests added to the queue and executes them - only 1 instance of this thread is run
    // Requests in the queue are already scanned for validity, this function extracts the stationID at the first line
    // The stationID is only used for the PUT request so the server knows where to put the file
    public void checkForTasks() {
        Thread checkThreads = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000); // Allows time for new requests to be added and prevents resource-overload
                } catch (InterruptedException ie) {
                    System.out.println("Delay fault in task checker: " + ie.getMessage());
                }
                if (!requestQueue.isEmpty()) { // Only checks the queue if it isn't empty
                    for (ConcurrentHashMap.Entry<String, Socket> curr_request : requestQueue) { // Loops through each entry in the queue
                        // Get the stationID from the data by first splitting the string into an array of lines for convenience
                        String[] lines = curr_request.getKey().split("\\r?\\n");
                        String stationID = lines[0]; // store the entityID, which is stationID and only used during PUT requests

                        String requestData = "";
                        for (int i = 1; i < lines.length; ++i) { // Removes entityID from the first line
                            requestData += (lines[i]);
                            if (i != (lines.length - 1)) {
                                requestData += ("\n");
                            }
                        }
                        executeRequest(requestData, curr_request.getValue(), stationID); // Non-threaded function -> Blocked call
                        requestQueue.remove(); // Waits for above function to finish, before removing it from the queue.
                        clock.updateTime();
                    }
                }
            }
        });
        checkThreads.setDaemon(true);
        checkThreads.start();
    }

    // Non-threaded function: Can be called multiple times
    // Checks through the request data more rigorously to ensure the format is valid
    // Checks for entries such as Host and User-Agent, and Content data if its a PUT message
    // Returns true if valid, false if not, request = request message as a String
    public boolean isValidRequest(String request) {
        if ((request == null) || request.isEmpty()) {
            return false;
        }
        String[] lines = request.split("\\r?\\n");
        ArrayList<String> requestEntryTypes = new ArrayList<>();
        String[] firstLine = lines[0].split(" ", 3);
        for (int i = 1; i < lines.length; ++i) { // Ignores the first line which tells us if its PUT or GET
            requestEntryTypes.add(lines[i].split(":", 2)[0].trim()); // Only get the request entry type (e.g. Host, User-Agent)
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
        if ((firstLine[0].equals("GET")) && !requestEntryTypes.contains("Accept")) {
            return false;
        }
        return true;
    }

    // Non-threaded function (blocked): Function must finish before next line of code executes -> Ensures 1 request at a time
    // Identifies which request it is, and calls it
    // requestData = request message as a String, referenceSocket = socket the request was sent by, ID = entity's ID
    public void executeRequest(String requestData, Socket referenceSocket, String ID) {
        ArrayList<ObjectStreamConstants> objstreams = streams.get(referenceSocket);
        try {
            if (requestData.isEmpty() || (!isValidRequest(requestData))) { // Checks request message isn't empty or invalid
                clock.updateTime();
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "204");
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }

            String[] requestLines = requestData.split("\\r?\\n");
            String[] currLine = requestLines[0].split(" ", 3); // first line
            if (currLine[0].equals("PUT")) {
                clock.updateTime(); // Calling the PUT message counts as event;
                executePUT(requestData, referenceSocket, ID);
                return;
            } else if (currLine[0].equals("GET")) {
                executeGET(requestData, referenceSocket, ID); // Clock doesn't update UNTIL data is sent, so no clock update here
                return;
            } else {
                System.out.println("Unidentifiable request - No action took place");
                clock.updateTime(); // Request failure = 1 event
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "500");
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }
        } catch (IOException ie) {
            System.out.println("Failed to read data from socket: " + ie.getMessage());
            return;
        }
    }

    // Non-threaded function (blocked): Executes PUT request
    // Gets the JSON data within the request message, converts it from JSON, decides what to do with it
    // requestData = PUT message as String, referenceSocket = socket that sent the PUT, ID = entity's ID (stationID)
    public void executePUT(String requestData, Socket referenceSocket, String ID) {
        ArrayList<ObjectStreamConstants> objstreams = streams.get(referenceSocket);
        try {
            ID = ID.replaceAll("CS", ""); // Omits the CS from the ID, leaving only the numeric value
            String[] requestLines = requestData.split("\r?\n");
            if (requestLines.length > 6) { // Checks bounds are long enough
                if (!(requestLines[6].equals("{")) || !(requestLines[requestLines.length - 1].equals("}"))) { // Checks the JSON is enclosed by brackets
                    clock.updateTime();
                    ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "500");
                    ((ObjectOutputStream) objstreams.getFirst()).flush();
                    return;
                }
                JSONParser jp = new JSONParser();
                ConcurrentHashMap<String, String> types = jp.getFeedTypes();
                String[] lineElements; // [0] = type, [1] = data (e.g: [0] = dewpt, [1] = 5.7)
                String PUT_DATA = "";
                for (int i = 7; i < requestLines.length - 1; ++i) { // Loop to trim spaces from data & check entries make sense
                    lineElements = requestLines[i].split(":", 2);
                    lineElements[0] = lineElements[0].trim();
                    lineElements[0] = lineElements[0].replace("\"", "");
                    lineElements[1] = lineElements[1].trim();
                    lineElements[1] = lineElements[1].replaceAll(",", "");
                    lineElements[1] = lineElements[1].replaceAll("\"", "");

                    if ((types.get(lineElements[0]) != null) && (types.get(lineElements[0]).equals("string")) && jp.isNumber(lineElements[1])) {
                        clock.updateTime();
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "500");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return; // Don't PUT the message if the entry type is string but the value is a number
                    }
                    if ((types.get(lineElements[0]) != null) && (types.get(lineElements[0]).equals("int")) && (!jp.isNumber(lineElements[1]))) {
                        clock.updateTime();
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "500");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return; // Don't PUT the message if the entry type is int but the value is a string
                    }
                    PUT_DATA += (lineElements[0] + ":" + lineElements[1] + "\n");
                }
                String weatherFileName = fileDirectory + "SERVER_DATA_" + ID + ".txt";
                Path path = Paths.get(weatherFileName);
                try {
                    if (Files.exists(path) && (Files.size(path) > 0)) { // Checks the file exists and isn't empty
                        updateFile(PUT_DATA, ID); // If file exists and isn't empty, call function to update the file
                        clock.updateTime();
                        ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "200");
                        ((ObjectOutputStream) objstreams.getFirst()).flush();
                        return;
                    } else { // Else, a new file needs to be made
                        System.out.println("No weather file yet - creating one now");
                        FileWriter temp = new FileWriter(weatherFileName); // Creates the file
                        temp.close();
                        PrintWriter writer = new PrintWriter(weatherFileName); // Writes data to the file
                        writer.println(PUT_DATA);
                        writer.flush();
                        writer.close();
                        currentFiles.put(weatherFileName, System.currentTimeMillis()); // Add/replace file to currentFiles hashmap
                        clock.updateTime(); // Sending the message back = 1 event
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
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "204"); // Empty JSON
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            }
        } catch (IOException ie) {
            System.out.println("Error executing request - Couldn't get socket's output stream: " + ie.getMessage());
            return;
        }
    }

    // Non-threaded function (blocked): Executes GET request
    // ID = GETClient ID NOT stationID
    // Not timestamped by clock until message is sent back to client
    // The timestamp is always the first line
    // requestData = message as a String, referenceSocket = socket that sent the GET, ID = ID of the socket who sent GET
    public void executeGET(String requestData, Socket referenceSocket, String ID) {
        // Read text file
        ArrayList<ObjectStreamConstants> objstreams = streams.get(referenceSocket);
        try {
            String[] requestLines = requestData.split("\r?\n");
            String stationID = "";
            if (requestLines.length > 3) {
                // Retrieves the requested stationID from 4th line
                String[] fourthLine = requestLines[3].split(":", 2);
                String[] fourthLineData = fourthLine[1].split("/", 2);
                stationID = fourthLineData[0].trim();
                stationID = stationID.replaceAll("CS", "");
            }
            Path filePath = Paths.get("");
            if (stationID.equals("latest")) { // "latest" = default by GETClient = Send back the latest added data
                Long latestTime = 0L; // latestTime = largest Long value
                Long currentTime = 0L;
                for (ConcurrentHashMap.Entry<String, Long> curr_file : currentFiles.entrySet()) { // Finds the last added file
                    if ((currentTime = curr_file.getValue()) > latestTime) {
                        latestTime = currentTime;
                        filePath = Paths.get(curr_file.getKey());
                    }
                }
            } else {
                // Sets the filename to unique file with the stationID found earlier appended to the end
                filePath = Paths.get(fileDirectory + "SERVER_DATA_" + stationID + ".txt");
                if ((!Files.exists(filePath)) || (Files.size(filePath) == 0)) { // Returns an error if the file doesn't exist
                    clock.updateTime();
                    ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "204");
                    ((ObjectOutputStream) objstreams.getFirst()).flush();
                    return;
                }
            }
            JSONParser jp = new JSONParser();
            String weatherData = Files.readString(filePath); // Read the file into a String
            String weatherDataJSON = jp.stringToJSON(weatherData); // Parse the data in the String into JSON
            clock.updateTime();
            ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + weatherDataJSON);
            ((ObjectOutputStream) objstreams.getFirst()).flush();
            return;
        } catch (IOException ie) {
            System.out.println("Error trying to fetch server weather data: " + ie.getMessage());
            try {
                ((ObjectOutputStream) objstreams.getFirst()).writeObject(clock.getTime() + "\n" + "204");
                ((ObjectOutputStream) objstreams.getFirst()).flush();
                return;
            } catch (IOException ie1) {
                System.out.println("Error trying to send message back to client: " + ie1.getMessage());
            }
        }
    }

    // updates the weather file with the given data (in regular text entry format), ID = stationID
    // Non-threaded function (blocked): Helper function for executePUT function
    // Updates file with new data by overwriting existing data and adding new data in a HashMap first, then printing it to the file
    // entries = The data to be uploaded in non-JSON format, ID = ID of the content server who uploaded the file
    public void updateFile(String entries, String ID) {
        // first, check the file exists
        ID = ID.replaceAll("CS", "");
        String weatherFileName = (fileDirectory + "SERVER_DATA_" + ID + ".txt"); // Determine the file to modify
        Path path = Paths.get(weatherFileName);
        try {
            if (!Files.exists(path) || Files.size(path) == 0) { // Redudant check: if file doesn't exist create it
                System.out.println("No weather file yet - creating new one");
                PrintWriter pw = new PrintWriter(weatherFileName);
                pw.println(entries);
                pw.flush();
                pw.close();
                clock.updateTime(); // *** new file created = 1 event
                return;
            }
        } catch (IOException ie) {
            System.out.println("Server error - data file doesn't exist, failed to make file."); // may be counterproductive
            return;
        }
        // Default case: updates the existing file
        ConcurrentHashMap<String, String> currentWeatherData = new ConcurrentHashMap<String, String>();
        try { // Turns the weather file into string HashMap containing key = type, value = data
            BufferedReader re = new BufferedReader(new FileReader(weatherFileName));
            String currLine = "";
            String[] temp;
            while (((currLine = re.readLine()) != null) && (!currLine.isEmpty())) {
                temp = currLine.split(":", 2);
                currentWeatherData.put(temp[0], temp[1]); // temp[0]=entry type, temp[1]=entry value
            }
            re.close();
        } catch (IOException ie) {
            System.out.println("Server error - failed to retrieve server data file");
        }

        String[] feed = entries.split(System.lineSeparator());
        ConcurrentHashMap<String, String> newEntries = new ConcurrentHashMap<String, String>();
        String[] feedLine;
        for (int i = 0; i < feed.length; ++i) { // Puts the newly-uploaded data into a HashMap
            feedLine = feed[i].split(":", 2);
            newEntries.put(feedLine[0], feedLine[1]);
        }

        try {
            PrintWriter pw = new PrintWriter(weatherFileName);
            // For each entry to update
            for (ConcurrentHashMap.Entry<String, String> new_entry : newEntries.entrySet()) {
                // For each entry in the current weather data
                for (ConcurrentHashMap.Entry<String, String> curr_entry : currentWeatherData.entrySet()) {
                    // if entry-to-update equals current weather entry, replace the current weather entry with updated entry
                    if (new_entry.getKey().equals(curr_entry.getKey())) { // Special case: type already exists, so replace with new entry
                        currentWeatherData.replace(new_entry.getKey(), new_entry.getValue());
                        newEntries.remove(new_entry.getKey()); // Remove it from newEntries HashMap
                    }
                }
            }

            if (!(newEntries.isEmpty())) {
                currentWeatherData.putAll(newEntries); // Adds entries that haven't been added before
            }
            for (ConcurrentHashMap.Entry<String, String> curr_entry : currentWeatherData.entrySet()) {
                pw.println(curr_entry.getKey() + ":" + curr_entry.getValue()); // Reprint the file data with the new version
            }
            pw.flush();
            pw.close();
            clock.updateTime(); // File updated = 1 event
        } catch (IOException ie) {
            System.out.println("Server error - failed to update weather data");
        }
    }

    public static void main(String[] args) {
        AggregationServer aggr = new AggregationServer();
        aggr.getPort();
        aggr.beginOperation();
        aggr.startScanThread();
        aggr.checkUpdateTimes();
        aggr.checkForTasks();
        aggr.listenForConnections();
    }

}

