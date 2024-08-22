import java.io.*;
import java.util.HashMap;

public class JSONParser {

    public boolean isNumber(String input) {
        try {
            Float test = Float.parseFloat(input);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    // Convert a given text file to JSON
    public void textToJSON(String file) {
        HashMap<String, String> dataJSON = new HashMap<String, String>();

        StringBuilder rawText = new StringBuilder();
        String temp;
        String[] line;
        try {
            BufferedReader br = new BufferedReader(new FileReader(file));

            // count number of lines, store it
            int numLines = 0;
            while(br.readLine() != null) {
                numLines++;
            }

            // create clean text file
            PrintWriter pwTEMP = new PrintWriter("jsonfile.json");
            pwTEMP.close();

            // Append opening bracket to JSON file or create it if doesn't exist
            try (FileWriter fwriter = new FileWriter("jsonfile.json", true);
                PrintWriter pwriter = new PrintWriter(fwriter)) {

                pwriter.println("{");
            } catch (IOException ie) {
                ie.printStackTrace();
            }

            br = new BufferedReader(new FileReader(file));

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
                try (FileWriter fwriter = new FileWriter("jsonfile.json", true);
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
                    numLines--;
                } catch (IOException ie) {
                    ie.printStackTrace();
                }

            }

            // Add closing bracket
            try (FileWriter fwriter = new FileWriter("jsonfile.json", true);
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

    // For testing this class
//    public static void main(String[] args) {
//        JSONParser j = new JSONParser();
//        j.textToJSON("entryfile.txt");
//    }

}
