import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
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
                String currentStr = buffer.toString();
                int lastSpaceIdx = currentStr.lastIndexOf(' ');
                boolean printBell = false;

                if (lastSpaceIdx == -1) {
                    // --- COMMAND COMPLETION ---
                    String prefix = currentStr;
                    if (!prefix.isEmpty()) {
                        List<String> commandMatches = findCommandMatches(prefix);

                        if (!commandMatches.isEmpty()) {
                            String commonPrefix = findLongestCommonPrefix(commandMatches);

                            if (commonPrefix.length() > prefix.length()) {
                                buffer.setLength(0);
                                buffer.append(commonPrefix);
                                if (commandMatches.size() == 1) {
                                    buffer.append(" ");
                                }
                            } else {
                                // Multiple matches exist but they diverge immediately (e.g. cow, fox, bee)
                                // Ring the bell!
                                printBell = true;
                            }
                        } else {
                            // No matches found at all
                            printBell = true;
                        }
                    } else {
                        printBell = true;
                    }
                } else {
                    // --- FILENAME COMPLETION ---
                    String prefix = currentStr.substring(lastSpaceIdx + 1);
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
                            buffer.append(remainder);
                        } else {
                            // 0 or multiple filename matches
                            printBell = true;
                        }
                    } else {
                        printBell = true;
                    }
                }

                // Clean the line and update display
                System.out.print("\r\33[K$ " + buffer.toString());
                if (printBell) {
                    System.out.print("\007"); // Ring the terminal bell (\a)
                }
                System.out.flush();
                continue;
            } else if (ch == '\n' || ch == '\r') {
                String input = buffer.toString().trim();

                if (!input.isEmpty()) {
                    executeCommand(input);
                }

                buffer.setLength(0);
                System.out.print("$ ");
                System.out.flush();
            } else {
                buffer.append(ch);
            }
        }
    }

    private static List<String> findCommandMatches(String prefix) {
        List<String> matches = new ArrayList<>();
        String[] builtins = { "exit", "jobs", "type" };
        for (String b : builtins) {
            if (b.startsWith(prefix) && !matches.contains(b)) {
                matches.add(b);
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] paths = pathEnv.split(File.pathSeparator);
            for (String path : paths) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && f.canExecute() && f.getName().startsWith(prefix)) {
                                if (!matches.contains(f.getName())) {
                                    matches.add(f.getName());
                                }
                            }
                        }
                    }
                }
            }
        }
        Collections.sort(matches);
        return matches;
    }

    private static String findLongestCommonPrefix(List<String> strs) {
        if (strs == null || strs.isEmpty())
            return "";
        String prefix = strs.get(0);
        for (int i = 1; i < strs.size(); i++) {
            while (strs.get(i).indexOf(prefix) != 0) {
                prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.isEmpty())
                    return "";
            }
        }
        return prefix;
    }

    private static void executeCommand(String input) {
        String[] argsList = input.split("\\s+");
        String command = argsList[0];

        if (command.equals("exit")) {
            System.exit(0);
        } else if (command.equals("jobs")) {
            // Empty implementation
        } else if (command.equals("type")) {
            if (argsList.length > 1) {
                String targetCommand = argsList[1];
                if (targetCommand.equals("jobs") || targetCommand.equals("exit") || targetCommand.equals("type")) {
                    System.out.println(targetCommand + " is a shell builtin");
                } else {
                    System.out.println(targetCommand + ": not found");
                }
            }
        } else {
            System.out.println(input + ": command not found");
        }
    }
}