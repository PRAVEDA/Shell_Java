import java.io.*;
import java.util.*;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.print("$ ");
        System.out.flush();

        InputStream in = System.in;
        StringBuilder buffer = new StringBuilder();

        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                System.out.println();
                String input = buffer.toString().trim();
                buffer.setLength(0);

                if (!input.isEmpty()) {
                    if (input.equals("exit 0")) {
                        System.exit(0);
                    } else {
                        handleCommand(input);
                    }
                }

                System.out.print("$ ");
                System.out.flush();

            } else if (c == '\t') {
                String completed = handleTabCompletion(buffer.toString());
                buffer.setLength(0);
                buffer.append(completed);

            } else if (c == 127 || c == '\b') {
                if (buffer.length() > 0) {
                    buffer.deleteCharAt(buffer.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }

            } else {
                buffer.append((char) c);
                System.out.print((char) c);
                System.out.flush();
            }
        }
    }

    static void handleCommand(String input) {
        System.out.println(input + ": command not found");
    }

    public static String handleTabCompletion(String currentBuffer) {
        int lastSpaceIdx = currentBuffer.lastIndexOf(' ');
        String prefix = (lastSpaceIdx == -1) ? currentBuffer : currentBuffer.substring(lastSpaceIdx + 1);

        if (prefix.isEmpty()) return currentBuffer;

        File currentDir = new File(System.getProperty("user.dir"));
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
            String remainder = matches.get(0).substring(prefix.length()) + " ";
            System.out.print(remainder);
            System.out.flush();
            return currentBuffer + remainder;
        }

        return currentBuffer;
    }
}