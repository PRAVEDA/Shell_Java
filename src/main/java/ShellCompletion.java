import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShellCompletion {

    // Simulates processing the tab press given the current input buffer
    public static String handleTabCompletion(String currentBuffer) {
        // 1. Extract the prefix (text after the last space)
        int lastSpaceIdx = currentBuffer.lastIndexOf(' ');
        String prefix = (lastSpaceIdx == -1) ? currentBuffer : currentBuffer.substring(lastSpaceIdx + 1);

        // If the prefix is empty (e.g., user pressed tab right after a space), skip
        if (prefix.isEmpty()) {
            return currentBuffer;
        }

        // 2. Scan the current working directory
        File currentDir = new File(".");
        File[] files = currentDir.listFiles();
        List<String> matches = new ArrayList<>();

        if (files != null) {
            for (File file : files) {
                // Filter files that start with the prefix
                if (file.getName().startsWith(prefix)) {
                    matches.add(file.getName());
                }
            }
        }

        // 3. If exactly one file matches, complete it
        if (matches.size() == 1) {
            String matchedFilename = matches.get(0);
            
            // Calculate what needs to be appended (remaining characters + trailing space)
            String remainder = matchedFilename.substring(prefix.length()) + " ";
            
            // Print the completion to stdout immediately so the user sees it
            System.out.print(remainder);
            System.out.flush();
            
            // Return the updated buffer
            return currentBuffer + remainder;
        }

        // Return unchanged if there are 0 or multiple matches (handled in later stages)
        return currentBuffer;
    }

    public static void main(String[] args) {
        // Test case simulation
        String buffer = "cat re";
        System.out.print("$ " + buffer); // Simulate prompt
        
        // Simulate pressing <TAB>
        buffer = handleTabCompletion(buffer); 
        
        // Buffer is now updated to "cat readme.txt "
    }
}