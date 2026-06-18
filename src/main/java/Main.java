import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // Unbuffered standard output to ensure CodeCrafters tester catches every print instantly
        System.out.print("$ ");
        System.out.flush();

        // Standard reading loop (Adapt this to how your specific shell parses raw characters)
        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine().trim();
            
            if (input.isEmpty()) {
                System.out.print("$ ");
                System.out.flush();
                continue;
            }

            // Simple command execution logic placeholder
            if (input.equals("exit 0")) {
                System.exit(0);
            } else {
                System.out.println(input + ": command not found");
            }

            System.out.print("$ ");
            System.out.flush();
        }
    }

    // Call this helper method inside your raw keypress processing loop when '\t' (Tab) is detected
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

        // Only complete if there is EXACTLY one match
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