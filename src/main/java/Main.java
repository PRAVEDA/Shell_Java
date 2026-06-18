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
                
                // Determine if we are auto-completing the first word (command) or a subsequent word (argument)
                int lastSpaceIdx = currentStr.lastIndexOf(' ');
                boolean isCommandMode = (lastSpaceIdx == -1);
                
                String prefix = isCommandMode ? currentStr : currentStr.substring(lastSpaceIdx + 1);
                List<String> matches;

                if (isCommandMode) {
                    matches = findCommandMatches(prefix);
                } else {
                    matches = findFileMatches(prefix);
                }

                if (!matches.isEmpty()) {
                    String commonPrefix = findLongestCommonPrefix(matches);
                    
                    // If there's an extended common prefix we can auto-fill right away
                    if (commonPrefix.length() > prefix.length()) {
                        String autoCompletedSegment = commonPrefix.substring(prefix.length());
                        buffer.append(autoCompletedSegment);
                        
                        // If it's a unique match and NOT an open directory, add a finishing trailing space
                        if (matches.size() == 1 && !commonPrefix.endsWith("/")) {
                            buffer.append(" ");
                        }
                        
                        consecutiveTabs = 0; // reset tab sequence
                        System.out.print("\r\33[K$ " + buffer.toString());
                    } else {
                        // No extra text can be uniquely deduced
                        if (consecutiveTabs == 1) {
                            System.out.print("\007"); // Ring bell
                        } else if (consecutiveTabs >= 2) {
                            // Double tab display options
                            System.out.print("\r\n");
                            StringBuilder optionsLine = new StringBuilder();
                            for (int i = 0; i < matches.size(); i++) {
                                String cleanDisplay = matches.get(i);
                                // Strip parent paths out if presenting file/dir options to match shell UX
                                if (!isCommandMode && cleanDisplay.contains("/")) {
                                    boolean trailingSlash = cleanDisplay.endsWith("/");
                                    String[] parts = cleanDisplay.split("/");
                                    if (parts.length > 0) {
                                        cleanDisplay = parts[parts.length - 1] + (trailingSlash ? "/" : "");
                                    }
                                }
                                optionsLine.append(cleanDisplay);
                                if (i < matches.size() - 1) optionsLine.append("  ");
                            }
                            System.out.print(optionsLine.toString() + "\r\n$ " + buffer.toString());
                            consecutiveTabs = 0;
                        }
                    }
                } else {
                    System.out.print("\007"); // No matches found -> Ring bell
                    consecutiveTabs = 0;
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

    static class ParsedCommand {
        List<String> args;
        List<Redirect> redirects;
        ParsedCommand(List<String> args, List<Redirect> redirects) {
            this.args = args; this.redirects = redirects;
        }
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && !inSingle) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    private static ParsedCommand parseCommand(String input) {
        List<String> tokens = tokenize(input);
        List<String> args = new ArrayList<>();
        List<Redirect> redirects = new ArrayList<>();

        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("2>>") || t.equals("1>>") || t.equals(">>")
                    || t.equals("2>") || t.equals("1>") || t.equals(">")) {
                if (i + 1 < tokens.size()) {
                    String type = t.replace("1>>", ">>").replace("1>", ">");
                    redirects.add(new Redirect(type, tokens.get(++i)));
                }
            } else if (t.startsWith("2>>")) { redirects.add(new Redirect("2>>", t.substring(3)));
            } else if (t.startsWith("2>")) {  redirects.add(new Redirect("2>", t.substring(2)));
            } else if (t.startsWith("1>>")) { redirects.add(new Redirect(">>", t.substring(3)));
            } else if (t.startsWith("1>")) {  redirects.add(new Redirect(">", t.substring(2)));
            } else if (t.startsWith(">>")) {  redirects.add(new Redirect(">>", t.substring(2)));
            } else if (t.startsWith(">")) {   redirects.add(new Redirect(">", t.substring(1)));
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

    private static void ensureRedirectFile(String filePath, boolean append) throws IOException {
        if (filePath == null) return;
        ensureParentDirs(filePath);
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

        if (command.equals("exit")) {
            restoreMode();
            System.exit(0);

        } else if (command.equals("echo")) {
            String output = args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : "";
            ensureRedirectFile(stderrFile, stderrAppend);
            if (stdoutFile != null) {
                ensureParentDirs(stdoutFile);
                try (PrintWriter pw = new PrintWriter(new FileWriter(stdoutFile, stdoutAppend))) { pw.println(output); }
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
                try (PrintWriter pw = new PrintWriter(new FileWriter(stdoutFile, stdoutAppend))) { pw.println(output); }
            } else {
                System.out.print(output + "\r\n");
            }
            System.out.print("$ ");
            System.out.flush();

        } else if (command.equals("type")) {
            String result = "";
            if (args.size() > 1) {
                String targetCommand = args.get(1);
                Set<String> builtinSet = new HashSet<>(Arrays.asList("jobs","exit","type","echo","pwd","cd"));
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
                try (PrintWriter pw = new PrintWriter(new FileWriter(stdoutFile, stdoutAppend))) { pw.println(result); }
            } else {
                System.out.print(result + "\r\n");
            }
            System.out.print("$ ");
            System.out.flush();

        } else if (command.equals("cd")) {
            String home = System.getenv("HOME");
            if (home == null) home = System.getProperty("user.home");
            String target = (args.size() <= 1) ? home : args.get(1);
            if (target.equals("~")) target = home;

            File dir = new File(target).isAbsolute() ? new File(target) : new File(System.getProperty("user.dir"), target);
            if (dir.exists() && dir.isDirectory()) {
                System.setProperty("user.dir", dir.getCanonicalPath());
            } else {
                System.out.print("cd: " + target + ": No such file or directory\r\n");
            }
            System.out.print("$ ");
            System.out.flush();

        } else if (command.equals("jobs")) {
            // Stage #af3 target empty implementation
            System.out.print("$ ");
            System.out.flush();
        } else {
            String execPath = findInPath(command);
            if (execPath == null) {
                String errMsg = command + ": command not found";
                if (stderrFile != null) {
                    ensureParentDirs(stderrFile);
                    try (PrintWriter pw = new PrintWriter(new FileWriter(stderrFile, stderrAppend))) { pw.println(errMsg); }
                } else {
                    System.out.print(errMsg + "\r\n");
                }
                System.out.print("$ ");
                System.out.flush();
                return;
            }

            ProcessBuilder pb = new ProcessBuilder(args);
            pb.directory(new File(System.getProperty("user.dir")));

            if (stdoutFile != null) {
                ensureParentDirs(stdoutFile);
                pb.redirectOutput(stdoutAppend ? ProcessBuilder.Redirect.appendTo(new File(stdoutFile)) : ProcessBuilder.Redirect.to(new File(stdoutFile)));
            } else {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            }

            if (stderrFile != null) {
                ensureParentDirs(stderrFile);
                pb.redirectError(stderrAppend ? ProcessBuilder.Redirect.appendTo(new File(stderrFile)) : ProcessBuilder.Redirect.to(new File(stderrFile)));
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
        String[] builtins = {"exit", "jobs", "type", "echo", "pwd", "cd"};
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

    // ── FILE AND DIRECTORY COMPLETION ENGINE ─────────────────────────────────
    private static List<String> findFileMatches(String prefix) {
        List<String> matches = new ArrayList<>();
        
        // Target directory defaults to current shell working directory
        String baseDirStr = System.getProperty("user.dir");
        String searchPrefix = prefix;

        // If the argument has path separators (nested matching), slice it up
        if (prefix.contains("/")) {
            int lastSlash = prefix.lastIndexOf('/');
            String pathSegment = prefix.substring(0, lastSlash + 1);
            searchPrefix = prefix.substring(lastSlash + 1);
            
            File resolvedDir = new File(pathSegment).isAbsolute() 
                ? new File(pathSegment) 
                : new File(baseDirStr, pathSegment);
                
            try {
                baseDirStr = resolvedDir.getCanonicalPath();
            } catch (IOException e) {
                return matches;
            }
        }

        File dir = new File(baseDirStr);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.getName().startsWith(searchPrefix)) {
                        String matchedName = f.getName();
                        
                        // Reconstruct original structure representation context
                        String fullMatchPath = prefix.contains("/") 
                            ? prefix.substring(0, prefix.lastIndexOf('/') + 1) + matchedName 
                            : matchedName;
                            
                        // Append directory visual hints inside matches 
                        if (f.isDirectory()) {
                            fullMatchPath += "/";
                        }
                        matches.add(fullMatchPath);
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