import lamport.LamportClock;
import lamport.LamportClockImpl;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// Isolation: No request can see one another, it only knows the AS
// Consistency: Data will be universal
// Atomicity: Requests are either processed completely or if failure occurs, undo wholly.
// Durability: Changes are only made by future requests -> results remain after crash/failure
// Causal time ordering: Relaxed ordering for better performance -> Independent threads can be concurrent

// Fault tolerance: eager release consistency when restoring state, ensures sync before starting up again

public class AggregationServerImpl {
    private final LamportClock clock;

    private Queue<String> requestQueue;
    public String port;

    private ServerSocket ass;

    private ASConnectionHandler asch; // dealing with clients and servers wanting to connect
    // keeps track of content server ids
    private Vector<String> stationIDs = new Vector<String>();

    // Vector to keep track of all threads (string = ID)
    private ConcurrentHashMap<String, ASConnectionHandler> socketThreads = new ConcurrentHashMap<String, ASConnectionHandler>();

    private final String weatherFileName = "SERVER_DATA.txt";

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
    public AggregationServerImpl() {
        clock = new LamportClockImpl();
        getPort();
        try {
            ass = new ServerSocket(Integer.parseInt(this.port));
        } catch (IOException ie) {
            System.out.println("Couldn't establish ServerSocket: " + ie.getMessage());
        }
    }

    // remove weather data entries that were last uploaded by the specified ID
    // stationID: stationID to remove from entries, updatees: types last updated by that stationID
    public void removeEntries(String stationID, ConcurrentHashMap<String, String> updatees) {
        // Retrieve the data as HashMap (type, entry)
        ConcurrentHashMap<String, String> currentData = new ConcurrentHashMap<String, String>();
        BufferedReader reader;
        String currLine = "";
        String[] temp;
        try {
            // Check if file empty
            String filepath = weatherFileName;
            Path path = Paths.get(filepath);
            if (Files.exists(path) && (Files.size(path) > 0)) {
//                reader = new BufferedReader(new FileReader(weatherFileName));
//                // store all feed and corresponding entries in HashMap
//                while (((currLine = reader.readLine()) != null) && (!currLine.isEmpty())) {
//                    temp = currLine.split(":", 2);
//                    currentData.put(temp[0], temp[1]);
//                }
                String content = Files.readString(path);
                // split content by line
                String[] lines = content.split(System.lineSeparator());
                String[] lineContent;
                for (int i = 0; i < lines.length; ++i) {
                    lineContent = lines[i].split(":", 2);
                    currentData.put(lineContent[0], lineContent[1]);
                }
            } else {
                System.out.println("Empty file, can't remove entries");
                return;
            }

            // for each updatee, remove it from the currentData hashmap
            for (ConcurrentHashMap.Entry<String, String> curr_updatee : updatees.entrySet()) {
                currentData.remove(curr_updatee.getKey()); // remove type (and entry) from currentData
            }

            // rewrite weather file with new data
            PrintWriter pw = new PrintWriter(weatherFileName);
            for (ConcurrentHashMap.Entry<String, String> curr_entry : currentData.entrySet()) {
                pw.println(curr_entry.getKey() + ":" + curr_entry.getValue());
            }
            pw.flush();
        } catch (IOException fnfe) {
            System.out.println("Error: Failed to retrieve weather file: " + fnfe.getMessage());
            return;
        }
    }

    // Checking if cleanup is needed for threads HashMap
    public void startCheckThread() {
        // special thread to check through threads
        Thread checkThreads = new Thread(() -> {
            // check keepAlive status of each thread
            while (true) {
                try {
                    Thread.sleep(1000); // 1-second period for thread to avoid resource overload
                } catch (InterruptedException ie) {
                    System.out.println(ie.getMessage());
                }
                if (!(socketThreads.isEmpty())) {
                    for (ConcurrentHashMap.Entry<String, ASConnectionHandler> curr_thread : socketThreads.entrySet()) {
                        // check for dead threads
                        if (!(curr_thread.getValue().isConnected())) { // if thread not alive
                            removeEntries(curr_thread.getKey(), curr_thread.getValue().getUpdatees()); // remove entries uploaded by the entity
                            socketThreads.remove(curr_thread.getKey()); // remove thread from threads HashMap
                            System.out.println(curr_thread.getKey() + " deleted.");
                        }
                    }
                }
            }
        });
        checkThreads.setDaemon(true);
        checkThreads.start();
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
                    System.out.println(ie.getMessage());
                }
                line = scanner.nextLine();
                if ((line != null) && (line.equals("END"))) {
                    try {
                        ass.close();
                        return;
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
        scanThread.setDaemon(true);
        scanThread.start();
    }

    // Runs continuously, periodically sending updates
    public void beginOperation() throws IOException {
        Integer threadNumber = 0; // socketThread vector index

        // loop to constantly accept any new connections
        Socket sc;
        String type = "";

        startCheckThread(); // for maintenance on socketThreads hashmap
        startScanThread(); // for scanning for user prompt to end AS

        while (true) {
            if ((sc = ass.accept()) != null) {
                // get the type and ID of the entity on other end of socket
                BufferedReader identifier = new BufferedReader(new InputStreamReader(sc.getInputStream()));
                String identity = identifier.readLine(); // stationID
                stationIDs.add(identity); // add to records of stationIDs

                // Create a new thread for this socket, passing socket and customerID to thread handler
                asch = new ASConnectionHandler(sc, identity);
                // Note: accepting the socket doesn't immediately start the thread
                socketThreads.put(identity, asch);
                threadNumber++;

                // run the thread
                asch.start(); // start to go to next line without waiting for thread
            }
        }
    }




    public void processGETRequest() {
    }

    public void processPUTRequest() {
    }

    public boolean restorePreviousState() {
        return false;
    }

    public void discardJSON() {
    }
}

