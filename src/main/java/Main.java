import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Main {
    public static void main(String[] args) throws Exception {
        System.out.print("$ ");
        System.out.flush();

        InputStream in = System.in;
        StringBuilder buffer = new StringBuilder();
        int consecutiveTabs = 0;

        while (true) {
            int readByte = in.read();
            if (readByte == -1)
                break;

            char ch = (char) readByte;

            if (ch == '\t') {
                consecutiveTabs++;
                String currentStr = buffer.toString();
                int lastSpaceIdx = currentStr.lastIndexOf(' ');

                if (lastSpaceIdx == -1) {
                    // COMMAND COMPLETION
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
                                consecutiveTabs = 0;
                                System.out.print("\r\33[K$ " + buffer.toString());
                            } else {
                                if (consecutiveTabs == 1) {
                                    System.out.print("\r\33[K$ " + buffer.toString() + "\007");
                                } else if (consecutiveTabs >= 2) {
                                    System.out.println();
                                    StringBuilder optionsLine = new StringBuilder();
                                    for (int i = 0; i < commandMatches.size(); i++) {
                                        optionsLine.append(commandMatches.get(i));
                                        if (i < commandMatches.size() - 1) {
                                            optionsLine.append("  ");
                                        }
                                    }
                                    System.out.println(optionsLine.toString());
                                    System.out.print("$ " + buffer.toString());
                                    consecutiveTabs = 0;
                                }
                            }
                        } else {
                            System.out.print("\r\33[K$ " + buffer.toString() + "\007");
                        }
                    } else {
                        System.out.print("\r\33[K$ " + buffer.toString() + "\007");
                    }
                } else {
                    // FILENAME COMPLETION
                    String prefix = currentStr.substring(lastSpaceIdx + 1);
                    if (!prefix.isEmpty()) {
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
                            String matchedFilename = matches.get(0);
                            String remainder = matchedFilename.substring(prefix.length()) + " ";
                            buffer.append(remainder);
                            consecutiveTabs = 0;
                            System.out.print("\r\33[K$ " + buffer.toString());
                        } else if (matches.size() > 1) {
                            if (consecutiveTabs == 1) {
                                System.out.print("\r\33[K$ " + buffer.toString() + "\007");
                            } else if (consecutiveTabs >= 2) {
                                Collections.sort(matches);
                                System.out.println();
                                StringBuilder optionsLine = new StringBuilder();
                                for (int i = 0; i < matches.size(); i++) {
                                    optionsLine.append(matches.get(i));
                                    if (i < matches.size() - 1) {
                                        optionsLine.append("  ");
                                    }
                                }
                                System.out.println(optionsLine.toString());
                                System.out.print("$ " + buffer.toString());
                                consecutiveTabs = 0;
                            }
                        } else {
                            System.out.print("\r\33[K$ " + buffer.toString() + "\007");
                        }
                    } else {
                        System.out.print("\r\33[K$ " + buffer.toString() + "\007");
                    }
                }
                System.out.flush();

            } else if (ch == '\n' || ch == '\r') {
                consecutiveTabs = 0;
                String input = buffer.toString().trim();
                buffer.setLength(0);

                if (!input.isEmpty()) {
                    executeCommand(input);
                }

                System.out.print("$ ");
                System.out.flush();

            } else if (ch == 127 || ch == '\b') {
                consecutiveTabs = 0;
                if (buffer.length() > 0) {
                    buffer.deleteCharAt(buffer.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }

            } else {
                consecutiveTabs = 0;
                buffer.append(ch);
            }
        }
    }

    private static void executeCommand(String input) {
        String[] argsList = input.split("\\s+");
        String command = argsList[0];

        if (command.equals("exit")) {
            System.exit(0);
        } else if (command.equals("echo")) {
            if (argsList.length > 1) {
                System.out.println(String.join(" ", Arrays.copyOfRange(argsList, 1, argsList.length)));
            } else {
                System.out.println();
            }
        } else if (command.equals("pwd")) {
            System.out.println(System.getProperty("user.dir"));
        } else if (command.equals("type")) {
            if (argsList.length > 1) {
                String targetCommand = argsList[1];
                if (targetCommand.equals("jobs") || targetCommand.equals("exit") ||
                        targetCommand.equals("type") || targetCommand.equals("echo") ||
                        targetCommand.equals("pwd")) {
                    System.out.println(targetCommand + " is a shell builtin");
                } else {
                    System.out.println(targetCommand + ": not found");
                }
            }
        } else {
            System.out.println(command + ": command not found");
        }
    }

    private static List<String> findCommandMatches(String prefix) {
        List<String> matches = new ArrayList<>();
        String[] builtins = { "exit", "jobs", "type", "echo", "pwd" };
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
}