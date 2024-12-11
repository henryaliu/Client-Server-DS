Welcome to my Assignment 2

----------------------------------------------------
*** IMPORTANT *** - Please arrange the files under the following folders for this to work:
src
    main
        java
            AggregationServer
                AggregationServer.java
                AggregationServer.class
            Client
                GETClient.java
                GETClient.class
            ContentServer
                ContentServer.java
                ContentServer.class
                entryfile.txt
                other entryfiles to be uploaded here
                weather.json (if it exists)
            JSONParser
                JSONParser.java
                JSONParser.class
            lamport
                LamportClock.java
                LamportClock.class
            test.java.Testing
                AggregationServerTest.java
                entryfile.txt
                LamportClockTest.java
                test_entry_file.txt

---------------------------------------------

PLEASE EMAIL ME IF YOU HAVE TROUBLE AND I WILL SEND MY GITHUB

*** HOW TO RUN THE PROGRAM ***
1. Make sure you create folders for each class:
(AggregationServer, Client, ContentServer, JSONParser, lamport, Testing (needs JUnit to work))
2. Please ensure you are in the java directory (src -> main -> java) but not in any specific folder after that.
3. Open a designated terminal for the AggregationServer folder, change your directory until you are in java folder, where
you can see all the different folders listed above (packages)
4. Repeat step 3 for every Content Server and GETClient that you wish to make (i.e. new terminal for each)
5. In each terminal compile the specific class using: javac PackageName/*.java (e.g. javac AggregationServer/*.java)
6. Compile the helper classes as well (JSONParser, LamportClock)
7. Create an entry file or multiple entry files as you wish in the ContentServer local folder, or in each if you run multiple
8. Store any appropriate weather text data there in regular NON-JSON format (e.g. time_zone:CST)
9. Start up the AggregationServer first by running: java AggregationServer/AggregationServer
10. Do the same for all the other clients/entities you wish to run using same command format (java PackageName/Class)
11. Follow the instructions in the terminal for each class (AggregationServer asks for port - enter to skip)
12. For ContentServer, you need to enter URL in the following format: https://localhost.cia.gov:portnumber
13. Choose any 4-digit port number, as long as it is the same as the AggregationServer
14. It will ask you for the entry file as well, make sure you include the path to it if you are not directly in ContentServer 
folder (e.g. ContentServer/entryfile.txt)
15. The GETClient will ask you for which stationID you want data from. If you enter nothing, it will get you the latest, 
the stationID is a pure number, and you can find it in any ContentServer terminal after you have entered the URL and entry file location
E.g. "Content server 12345: Connected to the weather server!" - 12345 is the stationID you enter in the GETClient to receive its data
16. The servers will tell you on the terminal that it has successfully connected, or it will retry or ask you to enter info again 
the info you provided is invalid
17. For ContentServer, you can type 2 things into the terminal: PUT (sends PUT message), or END (aborts the server)
18. Similarly for GETClient, you can type: GET (sends GET message, you'll receive the data in the terminal) or END (ends server)
19. The terminal in the AggregationServer will display any new connections, new requests, and other info
20. You can END the AggregationServer by typing END in its terminal too
21. Any weather data uploaded to the AggregationServer you can check out in the AggregationServer folder. However,
after 30 seconds without an update, the file will be deleted. The files are named SERVER_DATA_stationID.txt
22. Delete any remaining SERVER_DATA files if you END the servers prematurely and want a fresh start.
23. Note: There might be a few milliseconds of delay before your request is processed. This is simply the server resting
to resource overload due to checking too frequently. 
If you're using Intellij, the weather files on the AS may not immediately show until you click on the AggregationServer 
folder (not code-related).

You can see my testing in the Testing Folder and the Design Sketch I have attached with the code