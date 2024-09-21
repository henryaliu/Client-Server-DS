import java.io.*;
import java.nio.Buffer;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

public class JSONParser {

    private ConcurrentHashMap<String, String> feedTypes = new ConcurrentHashMap<String, String>();

    // Add feed types for checking validity later
    public JSONParser() {
        feedTypes.put("id", "string");
        feedTypes.put("name", "string");
        feedTypes.put("state", "string");
        feedTypes.put("time_zone", "string");
        feedTypes.put("lat", "int");
        feedTypes.put("lon", "int");
        feedTypes.put("local_date_time", "string");
        feedTypes.put("local_date_time_full", "int");
        feedTypes.put("air_temp", "int");
        feedTypes.put("apparent_t", "int");
        feedTypes.put("cloud", "string");
        feedTypes.put("dewpt", "int");
        feedTypes.put("press", "int");
        feedTypes.put("rel_hum", "int");
        feedTypes.put("wind_dir", "string");
        feedTypes.put("wind_spd_kmh", "int");
        feedTypes.put("wind_spd_kt", "int");
    }

    public ConcurrentHashMap<String, String> getFeedTypes() {
        return feedTypes;
    }

    Integer lines;

    public boolean isNumber(String input) {
        try {
            Float test = Float.parseFloat(input);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    public void writeToFile(String[] line, String outputName, int numLines) {
        // Write to the text file
        try (FileWriter fwriter = new FileWriter(outputName, true);
             PrintWriter pwriter = new PrintWriter(fwriter)) {
            // If entry is number, avoid quotation marks, or greater than 7 digits
            if (isNumber(line[1]) && (line[1].length() < 7)) {
                // If last line, remove comma
                if (numLines == 1) {
                    pwriter.println("    \"" + line[0] + "\" : " + line[1]);
                } else {
                    pwriter.println("    \"" + line[0] + "\" : " + line[1] + ",");
                }
            } else {
                // If last line, remove comma
                if (numLines == 1) {
                    pwriter.println("    \"" + line[0] + "\" : \"" + line[1] + "\"");
                } else {
                    pwriter.println("    \"" + line[0] + "\" : \"" + line[1] + "\",");
                }
            }
        } catch (IOException ie) {
            ie.printStackTrace();
        }
        return;
    }

    // Convert a given text file to JSON
    public void textToJSON(String inputName, String outputName) {

        // If file input is empty, return error message
        if (inputName.isEmpty()) {
            System.out.println("Error: Invalid or empty file." + "\n");
            return;
        }
        HashMap<String, String> dataJSON = new HashMap<String, String>();
        StringBuilder rawText = new StringBuilder();
        String temp;
        String[] line;
        try {
            BufferedReader br = new BufferedReader(new FileReader(inputName));

            // count number of lines, store it
            int numLines = 0;
            while(br.readLine() != null) {
                numLines++;
            }
            this.lines = numLines;

            // create clean text file
            PrintWriter pwTEMP = new PrintWriter(outputName);
            pwTEMP.close();

            // Append opening bracket to JSON file or create it if doesn't exist
            try (FileWriter fwriter = new FileWriter(outputName, true);
                PrintWriter pwriter = new PrintWriter(fwriter)) {

                pwriter.println("{");
            } catch (IOException ie) {
                throw new RuntimeException(ie);
            }

            br = new BufferedReader(new FileReader(inputName));

            while ((temp = br.readLine()) != null) {
                // temp holds 1 line of an entry file
                // check for empty line
                if (temp.isEmpty()) {
                    System.out.println("Parsing Error: empty line");
                    return;
                }

                // split temp by ':', store respective values in HashMap
                line = temp.split(":", 2);
                // trim any blank spaces
                line[0] = line[0].trim();
                line[1] = line[1].trim();

                // check if feed or entry is empty
                if (line[0].isEmpty()) {
                    System.out.println("Parsing Error: empty feed");
                    return;
                }
                if (line[1].isEmpty()) {
                    System.out.println("Parsing Error: empty entry");
                    return;
                }

                // Write to the text file
                writeToFile(line, outputName, numLines);
                numLines--;
            }

            // Add closing bracket
            try (FileWriter fwriter = new FileWriter(outputName, true);
                 PrintWriter pwriter = new PrintWriter(fwriter)) {
                pwriter.println("}");
            } catch (IOException ie) {
                ie.printStackTrace();
            }
        } catch (IOException ie) {
            ie.printStackTrace();
            return;
        }
    }

    public void JSONtoText(String inputName, String outputName) {
        // If file input is empty, return error message
        if (inputName.isEmpty()) {
            System.out.println("Error: Invalid or empty file." + "\n");
            return;
        }

        HashMap<String, String> dataText = new HashMap<String, String>();
        StringBuilder jsonText = new StringBuilder();
        String temp;
        String[] line;
        try {
            BufferedReader br = new BufferedReader(new FileReader(inputName));
            FileWriter fwriter = new FileWriter(outputName, true);
            PrintWriter pw = new PrintWriter(fwriter);

            String currLine = br.readLine(); // skip the opening bracket ({)
            int index = 0;
            while (!(currLine = br.readLine()).equals("}")) {
                // split by : first
                line = currLine.split(":", 2);
                // trim the spaces, commas, and quotation marks
                line[0] = line[0].trim();
                line[0] = line[0].replaceAll(",", "");
                line[0] = line[0].replaceAll("\"", "");

                if (!line[1].isEmpty() && line[1].length() > 1) {
                    line[1] = line[1].trim();
                    line[1] = line[1].replaceAll(",", "");
                    line[1] = line[1].replaceAll("\"", "");
                } else {
                    return;
                }

                // piece together into regular text file

                pw.println(line[0] + ":" + line[1]);
            }
            pw.close();
            return;
        } catch (IOException ie) {
            throw new RuntimeException(ie);
        }
    }

    // convert JSON to text but instead the input is a string
    public String JSONtoString(String JSON) {
        // assumes JSON is multi-line string
        if (JSON.isEmpty()) {
            System.out.println("Error: Invalid or empty JSON." + "\n");
            return "";
        }
        String data = "";
        String[] lines = JSON.split(System.lineSeparator());
        String[] l;
        for (int i = 1; i < lines.length-1; ++i) { // ignore 1st and last line (brackets)
            l = lines[i].split(":", 2);
            l[0] = l[0].trim();
            l[1] = l[1].trim();
            l[0] = l[0].replaceAll("\"", "");
            l[0] = l[0].replaceAll(",", "");
            l[1] = l[1].replaceAll("\"", "");
            l[1] = l[1].replaceAll(",", "");
            data += (l[0] + ":" + l[1] + "\n");
        }
        return data;
    }

    // For testing this class
    public static void main(String[] args) {
        JSONParser j = new JSONParser();
        String PUT = "{\n" + "\"id\" : \"IDS60901\"," + "\n" + "\"name\" : \"Adelaide\"" + "\n}";
        PUT = j.JSONtoString(PUT);
        System.out.print(PUT + "\n");
    }

}
