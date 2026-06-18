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
                break; 
            }

            char ch = (char) readByte;

            if (ch == '\t') {
                // 1. Intercept TAB key press and run autocomplete
                String currentStr = buffer.toString();
                
                int lastSpaceIdx = currentStr.lastIndexOf(' ');
                String prefix = (lastSpaceIdx == -1) ? currentStr : currentStr.substring(lastSpaceIdx + 1);

                if (!prefix.isEmpty()) {
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
                        
                        // Update our internal buffer
                        buffer.append(remainder);
                        
                        // 2. Wipe the line and reprint it clean to eliminate the tester's layout spaces
                        // \r brings cursor to start, \33[K clears from cursor to end of line
                        System.out.print("\r\33[K$ " + buffer.toString());
                        System.out.flush();
                        continue;
                    }
                }
            } 
            else if (ch == '\n' || ch == '\r') {
                String input = buffer.toString().trim();
                
                if (!input.isEmpty()) {
                    executeCommand(input);
                }
                
                buffer.setLength(0); 
                System.out.print("$ ");
                System.out.flush();
            } 
            else {
                // The tester's pseudo-terminal handles echoing regular typing.
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
}