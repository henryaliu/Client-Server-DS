package test.java.Testing;

import AggregationServer.AggregationServer;
import Client.GETClient;
import ContentServer.ContentServer;
import JSONParser.JSONParser;
import org.junit.jupiter.api.Test;

import javax.swing.text.AbstractDocument;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.AbstractMap;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class AggregationServerTest {

    @Test
    // For testing mechanism that picks up any files leftover from a crash from previous run.
    // Creates desired number of weather files, tests that they are all stored by server (Unit test)
    // Tested cases for numFiles = 0 (edge case), 5, 20 (edge case)
    void constructorTest() {
        try {
            // Create the desired number of test files (numFiles)
            int numFiles = 20;
            for (int i = 0; i < numFiles; ++i) {
                Path path = Paths.get("AggregationServer/SERVER_DATA_" + i + ".txt");
                Files.createFile(path);
                Thread.sleep(500);
            }

            ByteArrayInputStream bstream = new ByteArrayInputStream(" \n".getBytes());
            AggregationServer as = new AggregationServer();
            System.setIn(bstream);
            ConcurrentHashMap<String, Long> files = as.getCurrFiles();
            int size = files.size();

            assertEquals(size, numFiles); // Assert that the specified number of files were added to server data

            for (int i = 0; i < numFiles; ++i) {
                Path path = Paths.get("SERVER_DATA_" + i + ".txt");
                Files.delete(path);
            }
        } catch (IOException | InterruptedException ie) {
            System.out.println(ie.getMessage());
        }

    }

    @Test
    // Integration test for socket connection (checks that sockets are not null) after ContentServer has connected
    void listenForConnectionsTests() {
        InputStream is = System.in;
        ByteArrayInputStream bstream = new ByteArrayInputStream(" \n".getBytes());
        AggregationServer as = new AggregationServer();
        System.setIn(bstream);
        as.getPort();

        as.beginOperation();
        as.listenForConnections();

        ContentServer cs = new ContentServer();
        cs.setServer("localhost", 4567);

        ExecutorService exec = Executors.newSingleThreadExecutor();
        assertNull(cs.getCSSocket());
        exec.submit(cs::beginOperation);

        try {
            Thread.sleep(500);
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }
        assertNotNull(cs.getCSSocket()); // Checks content server socket is not null
        assertNotNull(as.getServerSocket()); // Checks AggregationServer socket is functional
        assertEquals(1, as.getStreams().size()); // Checks the socket streams have been added

        try {
            // Test with socket timeout
            Socket s = new Socket("localhost", 4567);
            s.setSoTimeout(2000);
            try {
                Thread.sleep(3000); // Wait until timeout is over
            } catch (InterruptedException ie) {
                System.out.println(ie.getMessage());
            }
            assertEquals(1, as.getStreams().size()); // Checks that aggregation server ignored the timed-out socket
        } catch (IOException e) {
            System.out.println(e.getMessage());
        }

        try {
            as.getServerSocket().close();
        } catch (IOException ie) {
            System.out.println(ie.getMessage());
        }

    }

    // Integration test for PUT request sent by CS (was it added to the requestQueue?)
    @Test
    void listenForRequestsTestPUT() {
        // Setup AggregationServer
        AggregationServer as = new AggregationServer();
        as.setDirectory("src/main/java/AggregationServer/");
        as.setPort("4567");

        as.beginOperation();
        as.listenForConnections();

        // Give some time for AggregationServer to start up
        try {
            Thread.sleep(600);
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }

        // Setup ContentServer
        ContentServer cs = new ContentServer();
        cs.setServer("localhost", 4567);
        cs.setEntryLoc("src/main/java/ContentServer/entryfile.txt");
        cs.setFileFolder("src/main/java/ContentServer/");
        cs.setHost("https://localhost.cia.gov:4567");

        ExecutorService asTask = Executors.newSingleThreadExecutor();
        asTask.submit(as::checkForTasks);

        // ExecutorService is used to make beginOperation non-blocking
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.submit(cs::beginOperation);
        try {
            Thread.sleep(1000); // Waits for the server to be fully ready
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }
        assertEquals(0, as.getRequestQueue().size()); // Assert that no task is in the queue yet

        exec.submit(() -> { // Makes sendPUT a non-blocking call, due to while (loop) inside this function
            cs.sendPUT();
            assertEquals(1, as.getRequestQueue().size()); // Assert that task has been added to queue
        });

        try {
            Thread.sleep(1000); // Give 1 second for server to register+store the task
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }
        assertEquals(0, as.getRequestQueue().size()); // Assert that task has been processed, so queue is empty
        try {
            as.getServerSocket().close();
        } catch (IOException ie) {
            System.out.println(ie.getMessage());
        }
    }

    // Integration test for GET request sent by GETClient
    // Note: we don't keep track of data received because Intellij formats it differently when running from JUnit
    @Test
    void listenForRequestsTestGET() {
        // Setup AggregationServer
        AggregationServer as = new AggregationServer();
        as.setDirectory("src/main/java/AggregationServer/");
        as.setPort("4567");

        as.beginOperation();
        as.listenForConnections();

        try {
            Thread.sleep(600);
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }

        // Setup ContentServer
        ContentServer cs = new ContentServer();
        cs.setServer("localhost", 4567);
        cs.setEntryLoc("src/main/java/ContentServer/entryfile.txt"); // 17 line text file
        cs.setFileFolder("src/main/java/ContentServer/");
        cs.setHost("https://localhost.cia.gov:4567");

        as.checkForTasks();

        // ExecutorService is used to make beginOperation and sendPUT non-blocking
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.submit(cs::beginOperation);
        assertEquals(0, as.getRequestQueue().size()); // No task yet, queue should be empty
        ExecutorService exec1 = Executors.newSingleThreadExecutor();
        try {
            Thread.sleep(1000); // Waits for the server to be fully ready
            // Makes sendPUT a non-blocking call, due to while (loop) inside this function
            exec1.submit(cs::sendPUT);
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }
        GETClient gc = new GETClient();
        gc.setInfo("https://localhost.cia.gov:4567", 4567, cs.getID());

        // Makes GETClient functions non-blocking
        ExecutorService GETexec = Executors.newSingleThreadExecutor();
        GETexec.submit(gc::beginOperation);
        try {
            Thread.sleep(2500); // Wait for the server to be fully ready
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }
        assertEquals(0, as.getRequestQueue().size()); // No task yet, queue should be empty
        ExecutorService GETexec1 = Executors.newSingleThreadExecutor();
        GETexec1.submit(() -> {
            gc.sendGET(4567);
        });
        try {
            Thread.sleep(3000); // checkForTasks every 2 seconds, so wait > 2 seconds
            String received = gc.getReceivedJSON();
            assertFalse(received.isEmpty()); // Check that the received file is not empty
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }

        try {
            as.getServerSocket().close();
        } catch (IOException ie) {
            System.out.println(ie.getMessage());
        }
    }

    @Test
    void checkUpdateTimesTest() {
        // Setup AggregationServer
        AggregationServer as = new AggregationServer();
        as.setDirectory("src/main/java/AggregationServer/");
        as.setPort("4567");

        as.beginOperation();
        int numFiles = 20;
        try {
            // Creates the desired number of test files (numFiles)
            for (int i = 0; i < numFiles; ++i) {
                Path path = Paths.get("AggregationServer/SERVER_DATA_" + i + ".txt");
                Files.createFile(path);
                Thread.sleep(500);
                as.addToCurrentFiles("AggregationServer/SERVER_DATA_" + i + ".txt", 0L);
            }
            assertEquals(numFiles, as.getCurrFiles().size()); // checkUpdateTimes not called yet, check files are all there
        } catch (IOException | InterruptedException ie) {
            System.out.println(ie.getMessage());
        }

        try {
            Thread.sleep(3000); // Give 1 second for server to register the task
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }
        ExecutorService exec = Executors.newSingleThreadExecutor(); // Blocked function, makes it non-blocking
        exec.submit(as::checkUpdateTimes);
        try {
            Thread.sleep(1000); // Give 1 second for server to register the task
        } catch (InterruptedException ie) {
            System.out.println(ie.getMessage());
        }
        assertEquals(0, as.getCurrFiles().size()); // All files should be expired -> should be 0 size
        try {
            as.getServerSocket().close();
        } catch (IOException ie) {
            System.out.println(ie.getMessage());
        }
    }

    @Test
    // Unit test
    // Checking that isValidRequest() parses request messages correctly
    void isValidRequestTestPUT() throws IOException { // Regular unit test
        JSONParser jp = new JSONParser(); // Custom JSON Parser (see JSONParser folder)
        jp.textToJSON("src/main/java/ContentServer/entryfile.txt",   "src/main/java/ContentServer/weather.json"); // Parse the entry file to local weather.json file
        Path path = Paths.get( "src/main/java/ContentServer/weather.json");
        long length = ((Files.lines(Paths.get(path.toUri())).count()));

        AggregationServer as = new AggregationServer();
        String PUT = "PUT " + "/weather.json HTTP/1.1" + "\n"; // Construction of UT message
        PUT += "Host: ContentServer/entryfile.txt \n";
        PUT += "User-Agent: ATOMClient/1/0" + "\n";
        PUT += "Content-Type: weather/json" + "\n"; // stationID
        PUT += "Content-Length: " + length + "\n" + " " + "\n";
        PUT += (Files.readString(path)); // Copies the JSON file over
        PUT = (1 + "\n" + PUT); // Add the timestamp to top of the message
        assertEquals(true, as.isValidRequest(PUT)); // Assert that isValidRequest returns true
    }

    @Test
    // Unit test
    // Checking that isValidRequest() parses request messages correctly
    // Invalid case: Content instead of Content-types (checking that HashMap splitting worked)
    void isValidRequestTestPUTInvalid() throws IOException { // Regular unit test
        JSONParser jp = new JSONParser(); // Custom JSON Parser (see JSONParser folder)
        jp.textToJSON("src/main/java/ContentServer/entryfile.txt",   "src/main/java/ContentServer/weather.json"); // Parse the entry file to local weather.json file
        Path path = Paths.get( "src/main/java/ContentServer/weather.json");
        long length = ((Files.lines(Paths.get(path.toUri())).count()));

        AggregationServer as = new AggregationServer();
        String PUT = "PUT " + "/weather.json HTTP/1.1" + "\n"; // Construct the invalid PUT message
        PUT += "Host: ContentServer/entryfile.txt \n";
        PUT += "User-Agent: ATOMClient/1/0" + "\n";
        PUT += "Content: weather/json" + "\n"; // Wrong entry type: Content
        PUT += "Content-Length: " + length + "\n" + " " + "\n";
        PUT += (Files.readString(path)); // Copying JSON data
        assertEquals(false, as.isValidRequest(PUT)); // Assert that isValidRequest returns false
    }

    @Test
    // Unit test
    // Checking that isValidRequest() parses request messages correctly
    // Testing GET message
    void isValidRequestTestGET() throws IOException { // Regular unit test
        AggregationServer as = new AggregationServer();
        String GET = "GET /AggregationServer/SERVER_DATA.txt HTTP/1.1" + "\n";
        GET += "Host: https://localhost.cia.gov:4567\n";
        GET += "User-Agent: ATOMClient/1/0" + "\n";
        GET += "Accept: " + 1234 + "/json" + "\n";
        assertEquals(true, as.isValidRequest(GET));
    }

    @Test
    // Unit test
    // Checking that isValidRequest() parses request messages correctly
    // Testing invalid GET message
    void isValidRequestTestGETInvalid() throws IOException { // Regular unit test
        AggregationServer as = new AggregationServer();
        String GET = "GET /AggregationServer/SERVER_DATA.txt HTTP/1.1" + "\n";
        GET += "Host: https://localhost.cia.gov:4567\n";
        GET += "Accept: " + 1234 + "/json" + "\n";
        assertEquals(false, as.isValidRequest(GET));
    }

    @Test
    // Unit test
    // Modify entryfile.txt (initial data) and test_entry_file.txt (new data) in testing folder
    // Uploads to AggregationServer folder
    void updateFileTest() throws IOException {
        AggregationServer as = new AggregationServer();
        as.setDirectory("src/main/java/AggregationServer/");

        Path path = Paths.get("src/main/java/test/java/Testing/entryfile.txt");
        String initialData = Files.readString(path);
        as.updateFile(initialData, "123"); // Upload the preliminary file to server data
        File initialFile = new File("src/main/java/AggregationServer/SERVER_DATA_123.txt");
        long originalModifiedTime = initialFile.lastModified();

        Path path1 = Paths.get("src/main/java/test/java/Testing/test_entry_file.txt");
        String newData = Files.readString(path1);
        as.updateFile(newData, "123");

        String uploadedTo = "src/main/java/AggregationServer/SERVER_DATA_123.txt";
        Path uploadPath = Paths.get(uploadedTo);
        File newFile = new File("src/main/java/AggregationServer/SERVER_DATA_123.txt");
        long newModifiedTime = newFile.lastModified();
        assertEquals(true, Files.size(uploadPath) > 0); // Assert file exists
        assertTrue(newModifiedTime > originalModifiedTime); // Assert file was in fact updated
        Files.deleteIfExists(uploadPath);

    }

}