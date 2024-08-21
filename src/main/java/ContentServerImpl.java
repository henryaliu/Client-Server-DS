import lamport.LamportClock;
import lamport.LamportClockImpl;

import java.util.HashMap;
import java.util.Scanner;

public class ContentServerImpl implements ContentServer {
    private LamportClock clock;

    public String serverName;
    public Integer port;

    public String fileLocation;
    public HashMap<String, String> fileData;

    public ContentServerImpl() {
        clock = new LamportClockImpl();
        fileData = new HashMap<String, String>();
    }

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
        System.out.println("Enter location of the entry file: ");
        String param2 = scanner.nextLine();


    }

    public HashMap<String, String> parseToJSON() {



        return fileData;
    }

    public void sendPUT(Integer port) {

    }

    public static void main(String[] args) {
        ContentServerImpl c = new ContentServerImpl();
        c.getParameters();
    }
}
