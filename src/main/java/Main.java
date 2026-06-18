import java.io.*;
import java.util.*;
import java.nio.file.*;

public class Main {

    private static void setRawMode() {
        try {
            new ProcessBuilder("stty", "-icanon", "-echo", "min", "1", "time", "0")
                .inheritIO()
                .start()
                .waitFor();
        } catch (Exception e) { }
    }

    private static void restoreMode() {
        try {
            new ProcessBuilder("stty", "icanon", "echo")
                .inheritIO()
                .start()
                .waitFor();
        } catch (Exception e) { }
    }

    public static void main(String[] args) throws Exception {
        setRawMode();
        Runtime.getRuntime().addShutdownHook(new Thread(Main::restoreMode));

        System.out.print("$ ");
        System.out.flush();

        InputStream in = new FileInputStream(FileDescriptor.in);
        StringBuilder buffer = new StringBuilder();
        int consecutiveTabs = 0;

        while (true) {
            int readByte = in.read();
            if (readByte == -1) break;

            char ch = (char) readByte;

            if (ch == '\t') {
                consecutiveTabs++;
                String currentStr = buffer.toString();
                int lastSpaceIdx = currentStr.lastIndexOf(' ');

                if (lastSpaceIdx == -1) {
                    String prefix = currentStr;
                    if (!prefix.isEmpty()) {
                        List<String> commandMatches = findCommandMatches(prefix);
                        if (!commandMatches.isEmpty()) {
                            String commonPrefix = findLongestCommonPrefix(commandMatches);
                            if (commonPrefix.length() > prefix.length()) {
                                buffer.setLength(0);
                                buffer.append(commonPrefix);
                                if (commandMatches.size() == 1) buffer.append(" ");
                                consecutiveTabs = 0;
                                System.out.print("\r\33[K$ " + buffer.toString());
                            } else {
                                if (consecutiveTabs == 1) {
                                    System.out.print("\007");
                                } else if (consecutiveTabs >= 2) {
                                    System.out.print("\r\n");
                                    StringBuilder optionsLine = new StringBuilder();
                                    for (int i = 0; i < commandMatches.size(); i++) {
                                        optionsLine.append(commandMatches.get(i));
                                        if (i < commandMatches.size() - 1) optionsLine.append("  ");
                                    }
                                    System.out.print(optionsLine.toString() + "\r\n$ " + buffer.toString());
                                    consecutiveTabs = 0;
                                }
                            }
                        } else {
                            System.out.print("\007");
                        }
                    } else {
                        System.out.print("\007");
                    }
                } else {
                    String prefix = currentStr.substring(lastSpaceIdx + 1);
                    if (!prefix.isEmpty()) {
                        File currentDir = new File(System.getProperty("user.dir"));
                        File[] files = currentDir.listFiles();
                        List<String> matches = new ArrayList<>();
                        if (files != null) {
                            for (File file : files) {
                                if (file.getName().startsWith(prefix)) matches.add(file.getName());
                            }
                        }
                        if (matches.size() == 1) {
                            String remainder = matches.get(0).substring(prefix.length()) + " ";
                            buffer.append(remainder);
                            consecutiveTabs = 0;
                            System.out.print("\r\33[K$ " + buffer.toString());
                        } else if (matches.size() > 1) {
                            if (consecutiveTabs == 1) {
                                System.out.print("\007");
                            } else if (consecutiveTabs >= 2) {
                                Collections.sort(matches);
                                System.out.print("\r\n");
                                StringBuilder optionsLine = new StringBuilder();
                                for (int i = 0; i < matches.size(); i++) {
                                    optionsLine.append(matches.get(i));
                                    if (i < matches.size() - 1) optionsLine.append("  ");
                                }
                                System.out.print(optionsLine.toString() + "\r\n$ " + buffer.toString());
                                consecutiveTabs = 0;
                            }
                        } else {
                            System.out.print("\007");
                        }
                    } else {
                        System.out.print("\007");
                    }
                }
                System.out.flush();

            } else if (ch == '\n' || ch == '\r') {
                consecutiveTabs = 0;
                String input = buffer.toString().trim();
                buffer.setLength(0);
                System.out.print("\r\n");
                System.out.flush();
                if (!input.isEmpty()) {
                    executeCommand(input);
                } else {
                    System.out.print("$ ");
                    System.out.flush();
                }

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
                System.out.print(ch);
                System.out.flush();
            }
        }
    }

    // ── Redirection descriptor ──────────────────────────────────────────────
    static class Redirect {
        String type; // ">", ">>", "2>", "2>>"
        String file;
        Redirect(String type, String file) { this.type = type; this.file = file; }
    }

    // ── Parse tokens, extracting redirections ───────────────────────────────
    static class ParsedCommand {
        List<String> args;
        List<Redirect> redirects;
        ParsedCommand(List<String> args, List<Redirect> redirects) {
            this.args = args; this.redirects = redirects;
        }
    }

    private static ParsedCommand parseCommand(String input) {
        // Simple tokeniser (handles 2>>, 2>, >>, >)
        String[] tokens = input.split("\\s+");
        List<String> args = new ArrayList<>();
        List<Redirect> redirects = new ArrayList<>();

        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("2>>") || t.equals("1>>") || t.equals(">>")
                    || t.equals("2>") || t.equals("1>") || t.equals(">")) {
                if (i + 1 < tokens.length) {
                    // normalise 1>> -> >>  and  1> -> >
                    String type = t.replace("1>>", ">>").replace("1>", ">");
                    redirects.add(new Redirect(type, tokens[++i]));
                }
            } else if (t.startsWith("2>>")) {
                redirects.add(new Redirect("2>>", t.substring(3)));
            } else if (t.startsWith("2>")) {
                redirects.add(new Redirect("2>", t.substring(2)));
            } else if (t.startsWith("1>>")) {
                redirects.add(new Redirect(">>", t.substring(3)));
            } else if (t.startsWith("1>")) {
                redirects.add(new Redirect(">", t.substring(2)));
            } else if (t.startsWith(">>")) {
                redirects.add(new Redirect(">>", t.substring(2)));
            } else if (t.startsWith(">")) {
                redirects.add(new Redirect(">", t.substring(1)));
            } else {
                args.add(t);
            }
        }
        return new ParsedCommand(args, redirects);
    }

    private static void ensureParentDirs(String filePath) {
        File f = new File(filePath);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
    }

    // Create redirect file (and parent dirs) if path is non-null, even if nothing is written
    private static void ensureRedirectFile(String filePath, boolean append) throws IOException {
        if (filePath == null) return;
        ensureParentDirs(filePath);
        // Open in append mode so existing content is preserved; creates file if absent
        new FileWriter(filePath, append).close();
    }

    private static void executeCommand(String input) throws Exception {
        ParsedCommand parsed = parseCommand(input);
        List<String> args = parsed.args;
        List<Redirect> redirects = parsed.redirects;

        if (args.isEmpty()) {
            System.out.print("$ ");
            System.out.flush();
            return;
        }

        String command = args.get(0);

        // Resolve stdout/stderr targets from redirects
        String stdoutFile = null;
        boolean stdoutAppend = false;
        String stderrFile = null;
        boolean stderrAppend = false;

        for (Redirect r : redirects) {
            if (r.type.equals(">"))   { stdoutFile = r.file; stdoutAppend = false; }
            if (r.type.equals(">>"))  { stdoutFile = r.file; stdoutAppend = true;  }
            if (r.type.equals("2>"))  { stderrFile = r.file; stderrAppend = false; }
            if (r.type.equals("2>>")) { stderrFile = r.file; stderrAppend = true;  }
        }

        // ── BUILTINS ────────────────────────────────────────────────────────
        if (command.equals("exit")) {
            restoreMode();
            System.exit(0);

        } else if (command.equals("echo")) {
            String output = args.size() > 1
                ? String.join(" ", args.subList(1, args.size()))
                : "";
            ensureRedirectFile(stderrFile, stderrAppend); // create even if empty
            if (stdoutFile != null) {
                ensureParentDirs(stdoutFile);
                try (PrintWriter pw = new PrintWriter(new FileWriter(stdoutFile, stdoutAppend))) {
                    pw.println(output);
                }
            } else {
                System.out.print(output + "\r\n");
            }
            System.out.print("$ ");
            System.out.flush();

        } else if (command.equals("pwd")) {
            String output = System.getProperty("user.dir");
            ensureRedirectFile(stderrFile, stderrAppend);
            if (stdoutFile != null) {
                ensureParentDirs(stdoutFile);
                try (PrintWriter pw = new PrintWriter(new FileWriter(stdoutFile, stdoutAppend))) {
                    pw.println(output);
                }
            } else {
                System.out.print(output + "\r\n");
            }
            System.out.print("$ ");
            System.out.flush();

        } else if (command.equals("type")) {
            String result = "";
            if (args.size() > 1) {
                String targetCommand = args.get(1);
                Set<String> builtinSet = new HashSet<>(Arrays.asList("jobs","exit","type","echo","pwd"));
                if (builtinSet.contains(targetCommand)) {
                    result = targetCommand + " is a shell builtin";
                } else {
                    String found = findInPath(targetCommand);
                    result = found != null ? targetCommand + " is " + found : targetCommand + ": not found";
                }
            }
            ensureRedirectFile(stderrFile, stderrAppend);
            if (stdoutFile != null) {
                ensureParentDirs(stdoutFile);
                try (PrintWriter pw = new PrintWriter(new FileWriter(stdoutFile, stdoutAppend))) {
                    pw.println(result);
                }
            } else {
                System.out.print(result + "\r\n");
            }
            System.out.print("$ ");
            System.out.flush();

        } else if (command.equals("cd")) {
            String target = args.size() > 1 ? args.get(1) : System.getenv("HOME");
            if (target == null) target = System.getProperty("user.home");
            if (target.equals("~")) target = System.getProperty("user.home");
            File dir = new File(target).isAbsolute()
                ? new File(target)
                : new File(System.getProperty("user.dir"), target);
            if (dir.exists() && dir.isDirectory()) {
                System.setProperty("user.dir", dir.getCanonicalPath());
            } else {
                System.out.print("cd: " + target + ": No such file or directory\r\n");
            }
            System.out.print("$ ");
            System.out.flush();

        } else {
            // ── EXTERNAL COMMAND ────────────────────────────────────────────
            String execPath = findInPath(command);
            if (execPath == null) {
                String errMsg = command + ": command not found";
                if (stderrFile != null) {
                    ensureParentDirs(stderrFile);
                    try (PrintWriter pw = new PrintWriter(new FileWriter(stderrFile, stderrAppend))) {
                        pw.println(errMsg);
                    }
                } else {
                    System.out.print(errMsg + "\r\n");
                }
                System.out.print("$ ");
                System.out.flush();
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(new File(System.getProperty("user.dir")));

            // stdout
            if (stdoutFile != null) {
                ensureParentDirs(stdoutFile);
                pb.redirectOutput(stdoutAppend
                    ? ProcessBuilder.Redirect.appendTo(new File(stdoutFile))
                    : ProcessBuilder.Redirect.to(new File(stdoutFile)));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            // stderr
            if (stderrFile != null) {
                ensureParentDirs(stderrFile);
                pb.redirectError(stderrAppend
                    ? ProcessBuilder.Redirect.appendTo(new File(stderrFile))
                    : ProcessBuilder.Redirect.to(new File(stderrFile)));
            } else {
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            }

            Process p = pb.start();
            p.waitFor();
            System.out.print("$ ");
            System.out.flush();
        }
    }

    private static String findInPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            File f = new File(dir, command);
            if (f.isFile() && f.canExecute()) return f.getAbsolutePath();
        }
        return null;
    }

    private static List<String> findCommandMatches(String prefix) {
        List<String> matches = new ArrayList<>();
        String[] builtins = {"exit", "jobs", "type", "echo", "pwd"};
        for (String b : builtins) {
            if (b.startsWith(prefix) && !matches.contains(b)) matches.add(b);
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String path : pathEnv.split(File.pathSeparator)) {
                File dir = new File(path);
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && f.canExecute() && f.getName().startsWith(prefix)
                                && !matches.contains(f.getName())) {
                                matches.add(f.getName());
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
        if (strs == null || strs.isEmpty()) return "";
        String prefix = strs.get(0);
        for (int i = 1; i < strs.size(); i++) {
            while (strs.get(i).indexOf(prefix) != 0) {
                prefix = prefix.substring(0, prefix.length() - 1);
                if (prefix.isEmpty()) return "";
            }
        }
        return prefix;
    }
}