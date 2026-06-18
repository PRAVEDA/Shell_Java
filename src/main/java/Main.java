import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.print("$ ");
        System.out.flush();

        InputStream in = System.in;
        StringBuilder buffer = new StringBuilder();

        while (true) {
            int readByte = in.read();
            if (readByte == -1) {
                break; // End of stream
            }

            char ch = (char) readByte;

            if (ch == '\t') {
                // 1. Intercept TAB key press and run autocomplete
                String currentStr = buffer.toString();
                String updatedStr = handleTabCompletion(currentStr);
                
                // Update our internal buffer to match the completed string
                buffer.setLength(0);
                buffer.append(updatedStr);
            } 
            else if (ch == '\n' || ch == '\r') {
                // 2. Intercept ENTER key press and execute the command
                System.out.println(); // Move to next line visually
                String input = buffer.toString().trim();
                
                if (!input.isEmpty()) {
                    executeCommand(input);
                }
                
                buffer.setLength(0); // Reset buffer for next command
                System.out.print("$ ");
                System.out.flush();
            } 
            else {
                // 3. Regular character typing: echoing it out and saving to buffer
                System.out.print(ch);
                System.out.flush();
                buffer.append(ch);
            }
        }
    }

    private static void executeCommand(String input) {
        String[] argsList = input.split("\\s+");
        String command = argsList[0];

        if (command.equals("exit")) {
            System.exit(0);
        } 
        else if (command.equals("jobs")) {
            // Empty implementation for #af3 stage
        } 
        else if (command.equals("type")) {
            if (argsList.length > 1) {
                String targetCommand = argsList[1];
                if (targetCommand.equals("jobs") || targetCommand.equals("exit") || targetCommand.equals("type")) {
                    System.out.println(targetCommand + " is a shell builtin");
                } else {
                    System.out.println(targetCommand + ": not found");
                }
            }
        } 
        else {
            System.out.println(input + ": command not found");
        }
    }

    public static String handleTabCompletion(String currentBuffer) {
        int lastSpaceIdx = currentBuffer.lastIndexOf(' ');
        String prefix = (lastSpaceIdx == -1) ? currentBuffer : currentBuffer.substring(lastSpaceIdx + 1);

        if (prefix.isEmpty()) {
            return currentBuffer;
        }

        File currentDir = new File(".");
        File[] files = currentDir.listFiles();
        List<String> matches = new ArrayList<>();

        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(prefix)) {
                    matches.add(file.getName());
                }
            }
        }

        if (matches.size() == 1) {
            String matchedFilename = matches.get(0);
            String remainder = matchedFilename.substring(prefix.length()) + " ";
            
            // Output completion to the terminal immediately
            System.out.print(remainder);
            System.out.flush();
            
            return currentBuffer + remainder;
        }

        return currentBuffer;
    }
}