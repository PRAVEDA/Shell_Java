import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

public class Main {

    private static File findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] paths = pathEnv.split(File.pathSeparator);

        for (String dir : paths) {
            File file = new File(dir, command);
            if (file.exists() && file.canExecute()) {
                return file;
            }
        }
        return null;
    }

    private static List<String> parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);

            if (inDoubleQuotes && ch == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                    }
                } else {
                    current.append('\\');
                }
            } else if (ch == '\\' && !inSingleQuotes && !inDoubleQuotes) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            } else if (ch == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
            } else if (ch == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
            } else if (Character.isWhitespace(ch) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(ch);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args;
    }

    private static void ensureFileExists(String path) throws Exception {
        File f = new File(path);
        if (f.getParentFile() != null) {
            f.getParentFile().mkdirs();
        }
        if (!f.exists()) {
            f.createNewFile();
        }
    }

    private static TreeSet<String> getMatchingCommands(String prefix) {
        TreeSet<String> matches = new TreeSet<>();
        if (prefix == null) return matches;

        String[] builtins = {"echo", "exit", "pwd", "cd", "type"};
        for (String builtin : builtins) {
            if (builtin.startsWith(prefix)) {
                matches.add(builtin);
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
                        for (File file : files) {
                            if (file.isFile() && file.canExecute() && file.getName().startsWith(prefix)) {
                                matches.add(file.getName());
                            }
                        }
                    }
                }
            }
        }
        return matches;
    }

    public static void main(String[] args) throws Exception {
        File currentDirectory = new File(System.getProperty("user.dir"));

        Terminal terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        terminal.enterRawMode();
        NonBlockingReader reader = terminal.reader();

        StringBuilder inputBuffer = new StringBuilder();
        int tabCount = 0;

        System.out.print("$ ");
        System.out.flush();

        while (true) {
            int readChar = reader.read();
            if (readChar == -1) {
                break;
            }

            char ch = (char) readChar;

            if (ch == '\r' || ch == '\n') {
                System.out.print("\r\n");
                System.out.flush();

                String input = inputBuffer.toString().trim();
                inputBuffer.setLength(0);
                tabCount = 0;

                if (input.isEmpty()) {
                    System.out.print("$ ");
                    System.out.flush();
                    continue;
                }

                List<String> tokens = new ArrayList<>(parseCommand(input));
                if (tokens.isEmpty()) {
                    System.out.print("$ ");
                    System.out.flush();
                    continue;
                }

                String commandName = tokens.get(0);

                if (commandName.equals("exit")) {
                    break;
                }

                String redirectFile = null;
                String errorRedirectFile = null;
                boolean isAppend = false;
                boolean isErrorAppend = false;

                for (int i = 0; i < tokens.size(); i++) {
                    String token = tokens.get(i);
                    if ((token.equals(">") || token.equals("1>")) && i + 1 < tokens.size()) {
                        redirectFile = tokens.get(i + 1);
                        isAppend = false;
                        tokens.remove(i + 1);
                        tokens.remove(i);
                        i--;
                    } else if ((token.equals(">>") || token.equals("1>>")) && i + 1 < tokens.size()) {
                        redirectFile = tokens.get(i + 1);
                        isAppend = true;
                        tokens.remove(i + 1);
                        tokens.remove(i);
                        i--;
                    } else if (token.equals("2>") && i + 1 < tokens.size()) {
                        errorRedirectFile = tokens.get(i + 1);
                        isErrorAppend = false;
                        tokens.remove(i + 1);
                        tokens.remove(i);
                        i--;
                    } else if (token.equals("2>>") && i + 1 < tokens.size()) {
                        errorRedirectFile = tokens.get(i + 1);
                        isErrorAppend = true;
                        tokens.remove(i + 1);
                        tokens.remove(i);
                        i--;
                    }
                }

                if (redirectFile != null) ensureFileExists(redirectFile);
                if (errorRedirectFile != null) ensureFileExists(errorRedirectFile);

                if (commandName.equals("pwd")) {
                    String output = currentDirectory.getCanonicalPath();
                    if (redirectFile != null) {
                        try (PrintStream ps = new PrintStream(new FileOutputStream(redirectFile, isAppend))) {
                            ps.println(output);
                        }
                    } else {
                        System.out.println(output);
                    }
                } 
                else if (commandName.equals("cd")) {
                    if (tokens.size() >= 2) {
                        String path = tokens.get(1);
                        File newDir = path.equals("~") ? new File(System.getenv("HOME")) 
                                     : path.startsWith("/") ? new File(path) 
                                     : new File(currentDirectory, path);

                        if (newDir.exists() && newDir.isDirectory()) {
                            currentDirectory = newDir.getCanonicalFile();
                        } else {
                            String err = "cd: " + path + ": No such file or directory";
                            if (errorRedirectFile != null) {
                                try (PrintStream ps = new PrintStream(new FileOutputStream(errorRedirectFile, isErrorAppend))) {
                                    ps.println(err);
                                }
                            } else {
                                System.err.println(err);
                            }
                        }
                    }
                } 
                else if (commandName.equals("echo")) {
                    StringBuilder output = new StringBuilder();
                    for (int i = 1; i < tokens.size(); i++) {
                        if (i > 1) output.append(" ");
                        output.append(tokens.get(i));
                    }
                    if (redirectFile != null) {
                        try (PrintStream ps = new PrintStream(new FileOutputStream(redirectFile, isAppend))) {
                            ps.println(output);
                        }
                    } else {
                        System.out.println(output);
                    }
                } 
                else if (commandName.equals("type")) {
                    if (tokens.size() >= 2) {
                        String command = tokens.get(1);
                        String output;
                        if (command.equals("echo") || command.equals("exit") || command.equals("type") || command.equals("pwd") || command.equals("cd")) {
                            output = command + " is a shell builtin";
                        } else {
                            File executable = findExecutable(command);
                            output = (executable != null) ? command + " is " + executable.getAbsolutePath() : command + ": not found";
                        }

                        if (redirectFile != null) {
                            try (PrintStream ps = new PrintStream(new FileOutputStream(redirectFile, isAppend))) {
                                ps.println(output);
                            }
                        } else {
                            System.out.println(output);
                        }
                    }
                } 
                else {
                    File executable = findExecutable(commandName);
                    if (executable != null) {
                        ProcessBuilder pb = new ProcessBuilder(tokens);
                        pb.directory(currentDirectory);
                        
                        if (redirectFile != null) {
                            pb.redirectOutput(isAppend ? ProcessBuilder.Redirect.appendTo(new File(redirectFile)) : ProcessBuilder.Redirect.to(new File(redirectFile)));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }

                        if (errorRedirectFile != null) {
                            pb.redirectError(isErrorAppend ? ProcessBuilder.Redirect.appendTo(new File(errorRedirectFile)) : ProcessBuilder.Redirect.to(new File(errorRedirectFile)));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process process = pb.start();
                        process.waitFor();
                    } else {
                        String err = commandName + ": command not found";
                        if (errorRedirectFile != null) {
                            try (PrintStream ps = new PrintStream(new FileOutputStream(errorRedirectFile, isErrorAppend))) {
                                ps.println(err);
                            }
                        } else {
                            System.err.println(err);
                        }
                    }
                }

                System.out.print("$ ");
                System.out.flush();
            }
            else if (readChar == 127 || readChar == 8) {
                if (inputBuffer.length() > 0) {
                    inputBuffer.deleteCharAt(inputBuffer.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }
                tabCount = 0;
            }
            else if (ch == '\t') {
                tabCount++;
                String currentInput = inputBuffer.toString();
                
                if (!currentInput.contains(" ")) {
                    TreeSet<String> matches = getMatchingCommands(currentInput);

                    if (matches.size() == 1) {
                        String completed = matches.first();
                        String toAppend = completed.substring(currentInput.length()) + " ";
                        inputBuffer.append(toAppend);
                        System.out.print(toAppend);
                        System.out.flush();
                        tabCount = 0;
                    } 
                    else if (matches.size() > 1) {
                        if (tabCount == 1) {
                            System.out.print("\u0007");
                            System.out.flush();
                        } else if (tabCount >= 2) {
                            System.out.print("\r\n");
                            StringBuilder lineBuilder = new StringBuilder();
                            for (String match : matches) {
                                if (lineBuilder.length() > 0) {
                                    lineBuilder.append("  ");
                                }
                                lineBuilder.append(match);
                            }
                            System.out.println(lineBuilder.toString());
                            
                            System.out.print("$ " + currentInput);
                            System.out.flush();
                            tabCount = 0;
                        }
                    } else {
                        // Matches == 0: Ring the bell when nothing matches!
                        System.out.print("\u0007");
                        System.out.flush();
                        tabCount = 0;
                    }
                } else {
                    tabCount = 0;
                }
            }
            else {
                inputBuffer.append(ch);
                System.out.print(ch);
                System.out.flush();
                tabCount = 0;
            }
        }
    }
}