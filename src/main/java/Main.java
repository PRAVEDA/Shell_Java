import java.util.Scanner;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Main {

    private static File findExecutable(String command) {
        String pathEnv = System.getenv("PATH");
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
        }

        else if (ch == '\\' && !inSingleQuotes && !inDoubleQuotes) {

            if (i + 1 < input.length()) {
                current.append(input.charAt(i + 1));
                i++;
            }
        }

        else if (ch == '\'' && !inDoubleQuotes) {
            inSingleQuotes = !inSingleQuotes;
        }

        else if (ch == '"' && !inSingleQuotes) {
            inDoubleQuotes = !inDoubleQuotes;
        }

        else if (Character.isWhitespace(ch)
                && !inSingleQuotes
                && !inDoubleQuotes) {

            if (current.length() > 0) {
                args.add(current.toString());
                current.setLength(0);
            }
        }

        else {
            current.append(ch);
        }
    }

    if (current.length() > 0) {
        args.add(current.toString());
    }

    return args;
}
    public static void main(String[] args) throws Exception {

        Scanner scanner = new Scanner(System.in);
        File currentDirectory = new File(System.getProperty("user.dir"));

        while (true) {

            System.out.print("$ ");

            String input = scanner.nextLine();

            List<String> tokens = parseCommand(input);

            if (tokens.isEmpty()) {
                continue;
            }

            String commandName = tokens.get(0);

            if (commandName.equals("exit")) {
                break;
            }

            else if (commandName.equals("pwd")) {
                System.out.println(currentDirectory.getCanonicalPath());
            }

            else if (commandName.equals("cd")) {

                if (tokens.size() < 2) {
                    continue;
                }

                String path = tokens.get(1);

                File newDir;

                if (path.equals("~")) {
                    newDir = new File(System.getenv("HOME"));
                }
                else if (path.startsWith("/")) {
                    newDir = new File(path);
                }
                else {
                    newDir = new File(currentDirectory, path);
                }

                if (newDir.exists() && newDir.isDirectory()) {
                    currentDirectory = newDir.getCanonicalFile();
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }
            }

            else if (commandName.equals("echo")) {

                StringBuilder output = new StringBuilder();

                for (int i = 1; i < tokens.size(); i++) {
                    if (i > 1) {
                        output.append(" ");
                    }
                    output.append(tokens.get(i));
                }

                System.out.println(output);
            }

            else if (commandName.equals("type")) {

                if (tokens.size() < 2) {
                    continue;
                }

                String command = tokens.get(1);

                if (command.equals("echo")
                        || command.equals("exit")
                        || command.equals("type")
                        || command.equals("pwd")
                        || command.equals("cd")) {

                    System.out.println(command + " is a shell builtin");
                }
                else {

                    File executable = findExecutable(command);

                    if (executable != null) {
                        System.out.println(command + " is " + executable.getAbsolutePath());
                    } else {
                        System.out.println(command + ": not found");
                    }
                }
            }

            else {

                File executable = findExecutable(commandName);

                if (executable != null) {

                    ProcessBuilder pb = new ProcessBuilder(tokens);
                    pb.directory(currentDirectory);
                    pb.inheritIO();

                    Process process = pb.start();
                    process.waitFor();

                } else {
                    System.out.println(commandName + ": command not found");
                }
            }
        }
    }
}