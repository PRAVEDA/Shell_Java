import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // Print the initial prompt
        System.out.print("$ ");
        System.out.flush();

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
                System.out.print("$ ");
                System.out.flush();
                continue;
            }

            // Split the input into arguments
            String[] argsList = input.split("\\s+");
            String command = argsList[0];

            // Handle built-in commands
            if (command.equals("exit")) {
                System.exit(0);
            } 
            else if (command.equals("jobs")) {
                // For this stage, jobs has an empty implementation.
                // It produces no output and simply returns to the prompt.
            } 
            else if (command.equals("type")) {
                if (argsList.length > 1) {
                    String targetCommand = argsList[1];
                    
                    // Register jobs, exit, and type as builtins
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

            // Always reprint prompt at the end of execution loop
            System.out.print("$ ");
            System.out.flush();
        }
    }

    // Tab completion logic from the previous stage
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
            
            System.out.print(remainder);
            System.out.flush();
            
            return currentBuffer + remainder;
        }

        return currentBuffer;
    }
}