import java.util.Scanner;
import java.io.File;
import java.io.PrintStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

// JLine Imports
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

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

    public static void main(String[] args) throws Exception {
        File currentDirectory = new File(System.getProperty("user.dir"));

        // Build terminal interface compatible with CodeCrafters test runner environment
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .dumb(true) 
                .build();

        // Standardize string capture to keep JLine from dropping backslashes early
        DefaultParser parser = new DefaultParser();
        parser.setEscapeChars(new char[0]); 

        // Custom completer that handles builtins and paths dynamically
        Completer pathCompleter = new Completer() {
            @Override
            public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
                // Autocomplete only makes sense for the first token (the command)
                if (line.wordIndex() != 0) {
                    return;
                }

                String word = line.word();
                TreeSet<String> matches = new TreeSet<>();

                // 1. Match Builtins
                String[] builtins = {"echo", "exit", "pwd", "cd", "type"};
                for (String builtin : builtins) {
                    if (builtin.startsWith(word)) {
                        matches.add(builtin);
                    }
                }

                // 2. Match Executables from PATH environment variable
                String pathEnv = System.getenv("PATH");
                if (pathEnv != null) {
                    String[] paths = pathEnv.split(File.pathSeparator);
                    for (String path : paths) {
                        File dir = new File(path);
                        if (dir.exists() && dir.isDirectory()) {
                            File[] files = dir.listFiles();
                            if (files != null) {
                                for (File file : files) {
                                    if (file.isFile() && file.canExecute() && file.getName().startsWith(word)) {
                                        matches.add(file.getName());
                                    }
                                }
                            }
                        }
                    }
                }

                // Populate candidates list with matches found
                for (String match : matches) {
                    candidates.add(new Candidate(match));
                }
            }
        };

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .completer(pathCompleter)
                .parser(parser)
                .build();

        // Enforce clean layout setups: complete word, append a space
        reader.setOpt(LineReader.Option.AUTO_MENU);
        reader.setOpt(LineReader.Option.AUTO_LIST);
        reader.setVariable(LineReader.DISABLE_COMPLETION, false);

        while (true) {
            String input;
            try {
                input = reader.readLine("$ ");
            } catch (org.jline.reader.EndOfFileException | org.jline.reader.UserInterruptException e) {
                break;
            }

            if (input == null) {
                break;
            }

            input = input.trim();
            if (input.isEmpty()) {
                continue;
            }

            List<String> tokens = new ArrayList<>(parseCommand(input));

            String redirectFile = null;
            String errorRedirectFile = null;
            boolean isAppend = false;
            boolean isErrorAppend = false;

            // Scan ALL tokens — handles >, 1>, >>, 1>>, 2>, and 2>>
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

            // Ensure redirect target files exist
            if (redirectFile != null) {
                ensureFileExists(redirectFile);
            }
            if (errorRedirectFile != null) {
                ensureFileExists(errorRedirectFile);
            }

            if (tokens.isEmpty()) {
                continue;
            }

            String commandName = tokens.get(0);

            if (commandName.equals("exit")) {
                break;
            }

            else if (commandName.equals("pwd")) {
                String output = currentDirectory.getCanonicalPath();
                if (redirectFile != null) {
                    PrintStream ps = new PrintStream(new FileOutputStream(redirectFile, isAppend));
                    ps.println(output);
                    ps.close();
                } else {
                    System.out.println(output);
                }
            }

            else if (commandName.equals("cd")) {
                if (tokens.size() < 2) continue;

                String path = tokens.get(1);
                File newDir;

                if (path.equals("~")) {
                    newDir = new File(System.getenv("HOME"));
                } else if (path.startsWith("/")) {
                    newDir = new File(path);
                } else {
                    newDir = new File(currentDirectory, path);
                }

                if (newDir.exists() && newDir.isDirectory()) {
                    currentDirectory = newDir.getCanonicalFile();
                } else {
                    String err = "cd: " + path + ": No such file or directory";
                    if (errorRedirectFile != null) {
                        PrintStream ps = new PrintStream(new FileOutputStream(errorRedirectFile, isErrorAppend));
                        ps.println(err);
                        ps.close();
                    } else {
                        System.err.println(err);
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
                    PrintStream ps = new PrintStream(new FileOutputStream(redirectFile, isAppend));
                    ps.println(output);
                    ps.close();
                } else {
                    System.out.println(output);
                }
            }

            else if (commandName.equals("type")) {
                if (tokens.size() < 2) continue;

                String command = tokens.get(1);
                String output;

                if (command.equals("echo") || command.equals("exit")
                        || command.equals("type") || command.equals("pwd")
                        || command.equals("cd")) {
                    output = command + " is a shell builtin";
                } else {
                    File executable = findExecutable(command);
                    if (executable != null) {
                        output = command + " is " + executable.getAbsolutePath();
                    } else {
                        output = command + ": not found";
                    }
                }

                if (redirectFile != null) {
                    PrintStream ps = new PrintStream(new FileOutputStream(redirectFile, isAppend));
                    ps.println(output);
                    ps.close();
                } else {
                    System.out.println(output);
                }
            }

            else {
                File executable = findExecutable(commandName);

                if (executable != null) {
                    ProcessBuilder pb = new ProcessBuilder(tokens);
                    pb.directory(currentDirectory);

                    if (redirectFile != null) {
                        if (isAppend) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(redirectFile)));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.to(new File(redirectFile)));
                        }
                    } else {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }

                    if (errorRedirectFile != null) {
                        if (isErrorAppend) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(errorRedirectFile)));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.to(new File(errorRedirectFile)));
                        }
                    } else {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();
                    process.waitFor();

                } else {
                    String err = commandName + ": command not found";
                    if (errorRedirectFile != null) {
                        PrintStream ps = new PrintStream(new FileOutputStream(errorRedirectFile, isErrorAppend));
                        ps.println(err);
                        ps.close();
                    } else {
                        System.err.println(err);
                    }
                }
            }
        }
    }
}