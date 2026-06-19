import java.io.*;
import java.util.*;
import java.nio.file.*;

public class Main {

    // Global registry for programmable completions
    private static final Map<String, String> completionRegistry = new HashMap<>();
    
    // Class to track running background jobs
    static class BackgroundJob {
        int id;
        Process process;
        String commandStr;
        String status; // "Running" or "Done"

        BackgroundJob(int id, Process process, String commandStr) {
            this.id = id;
            this.process = process;
            this.commandStr = commandStr;
            this.status = "Running";
        }
    }
    
    // List to keep track of active background jobs
    private static final List<BackgroundJob> activeJobs = new ArrayList<>();

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
                
                int compPoint = currentStr.length();
                int lastSpaceIdx = currentStr.lastIndexOf(' ');
                boolean isCommandMode = (lastSpaceIdx == -1);
                
                String firstWord = isCommandMode ? currentStr : currentStr.substring(0, currentStr.indexOf(' '));
                String prefix = isCommandMode ? currentStr : currentStr.substring(lastSpaceIdx + 1);
                
                List<String> matches = new ArrayList<>();

                if (!isCommandMode && completionRegistry.containsKey(firstWord)) {
                    matches = findProgrammableMatches(firstWord, currentStr, compPoint, prefix);
                } else if (isCommandMode) {
                    matches = findCommandMatches(prefix);
                } else {
                    matches = findFileMatches(prefix);
                }

                if (!matches.isEmpty()) {
                    String commonPrefix = findLongestCommonPrefix(matches);
                    
                    if (commonPrefix.length() > prefix.length()) {
                        String autoCompletedSegment = commonPrefix.substring(prefix.length());
                        buffer.append(autoCompletedSegment);
                        
                        if (matches.size() == 1 && !commonPrefix.endsWith("/")) {
                            buffer.append(" ");
                        }
                        
                        consecutiveTabs = 0;
                        System.out.print("\r\33[K$ " + buffer.toString());
                    } else {
                        if (consecutiveTabs == 1) {
                            System.out.print("\007");
                        } else if (consecutiveTabs >= 2) {
                            System.out.print("\r\n");
                            StringBuilder optionsLine = new StringBuilder();
                            for (int i = 0; i < matches.size(); i++) {
                                String cleanDisplay = matches.get(i);
                                if (isCommandMode || completionRegistry.containsKey(firstWord)) {
                                    optionsLine.append(cleanDisplay);
                                } else if (cleanDisplay.contains("/")) {
                                    boolean trailingSlash = cleanDisplay.endsWith("/");
                                    String[] parts = cleanDisplay.split("/");
                                    if (parts.length > 0) {
                                        cleanDisplay = parts[parts.length - 1] + (trailingSlash ? "/" : "");
                                    }
                                    optionsLine.append(cleanDisplay);
                                } else {
                                    optionsLine.append(cleanDisplay);
                                }
                                if (i < matches.size() - 1) optionsLine.append("  ");
                            }
                            System.out.print(optionsLine.toString() + "\r\n$ " + buffer.toString());
                            consecutiveTabs = 0;
                        }
                    }
                } else {
                    System.out.print("\007");
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
                    executeCommandLine(input);
                } else {
                    checkAndReportBackgroundJobs(false);
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

    static class Redirect {
        String type;
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
            if (t.equals("2>>") || t.equals("1>>") || t.equals(">>") || t.equals("2>") || t.equals("1>") || t.equals(">")) {
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

    private static void checkAndReportBackgroundJobs(boolean forcePrintAll) {
        List<BackgroundJob> toPrint = new ArrayList<>();
        
        for (BackgroundJob job : activeJobs) {
            if (job.status.equals("Running") && !job.process.isAlive()) {
                job.status = "Done";
                toPrint.add(job);
            } else if (forcePrintAll) {
                toPrint.add(job);
            }
        }

        for (BackgroundJob job : toPrint) {
            int idx = activeJobs.indexOf(job);
            char statusChar = ' ';
            if (idx == activeJobs.size() - 1) {
                statusChar = '+';
            } else if (idx == activeJobs.size() - 2) {
                statusChar = '-';
            }
            
            String formattedStatus = String.format("%-24s", job.status);
            String suffix = job.status.equals("Running") ? " &" : "";
            System.out.print("[" + job.id + "]" + statusChar + "  " + formattedStatus + job.commandStr + suffix + "\r\n");
        }

        activeJobs.removeIf(job -> job.status.equals("Done"));
    }

    private static List<String> splitByPipes(String input) {
        List<String> stages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && !inSingle) {
                current.append(c);
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }
            if (c == '|' && !inSingle && !inDouble) {
                stages.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        stages.add(current.toString().trim());
        return stages;
    }

    private static void executeCommandLine(String input) throws Exception {
        boolean isBackgroundJob = false;
        String workingInput = input.trim();
        if (workingInput.endsWith("&")) {
            isBackgroundJob = true;
            workingInput = workingInput.substring(0, workingInput.length() - 1).trim();
        }

        List<String> pipeStages = splitByPipes(workingInput);

        if (pipeStages.size() == 1) {
            executeSingleCommand(pipeStages.get(0), isBackgroundJob, input);
        } else {
            executePipeline(pipeStages, isBackgroundJob, input);
        }
    }

    private static String evaluateBuiltin(String command, List<String> args) throws Exception {
        if (command.equals("echo")) {
            return args.size() > 1 ? String.join(" ", args.subList(1, args.size())) : "";
        } else if (command.equals("pwd")) {
            return System.getProperty("user.dir");
        } else if (command.equals("type")) {
            if (args.size() > 1) {
                String targetCommand = args.get(1);
                Set<String> builtinSet = new HashSet<>(Arrays.asList("complete","jobs","exit","type","echo","pwd","cd"));
                if (builtinSet.contains(targetCommand)) {
                    return targetCommand + " is a shell builtin";
                } else {
                    String found = findInPath(targetCommand);
                    return found != null ? targetCommand + " is " + found : targetCommand + ": not found";
                }
            }
            return "";
        }
        return null;
    }

    private static void executePipeline(List<String> stages, boolean isBackgroundJob, String originalInput) throws Exception {
        Set<String> builtins = new HashSet<>(Arrays.asList("echo", "pwd", "type", "cd", "exit", "jobs"));

        int n = stages.size();
        List<Process> processes = new ArrayList<>();

        // builtinInput holds bytes produced by a builtin stage to feed into the next stage
        byte[] builtinInput = new byte[0];
        // prevProcess is the last external process started (its stdout feeds the next stage)
        Process prevProcess = null;
        Process lastProcess = null;

        for (int i = 0; i < n; i++) {
            ParsedCommand parsed = parseCommand(stages.get(i));
            List<String> args = parsed.args;
            List<Redirect> redirects = parsed.redirects;
            if (args.isEmpty()) continue;

            String command = args.get(0);
            boolean isLast = (i == n - 1);

            String stdoutFile = null; boolean stdoutAppend = false;
            String stderrFile = null; boolean stderrAppend = false;
            for (Redirect r : redirects) {
                if (r.type.equals(">"))   { stdoutFile = r.file; stdoutAppend = false; }
                if (r.type.equals(">>"))  { stdoutFile = r.file; stdoutAppend = true;  }
                if (r.type.equals("2>"))  { stderrFile = r.file; stderrAppend = false; }
                if (r.type.equals("2>>")) { stderrFile = r.file; stderrAppend = true;  }
            }

            if (builtins.contains(command)) {
                // Evaluate builtin synchronously; its output becomes input for the next stage
                String result = evaluateBuiltin(command, args);
                if (result == null) result = "";
                String out = result.isEmpty() ? "" : result + "\n";

                if (isLast) {
                    if (stdoutFile != null) {
                        ensureParentDirs(stdoutFile);
                        try (FileOutputStream fos = new FileOutputStream(stdoutFile, stdoutAppend)) {
                            fos.write(out.getBytes());
                        }
                    } else {
                        System.out.print(out);
                        System.out.flush();
                    }
                } else {
                    builtinInput = out.getBytes();
                }

            } else {
                String execPath = findInPath(command);
                if (execPath == null) {
                    System.out.print(command + ": command not found\r\n");
                    System.out.flush();
                    for (Process p : processes) p.destroyForcibly();
                    checkAndReportBackgroundJobs(false);
                    System.out.print("$ ");
                    System.out.flush();
                    return;
                }

                ProcessBuilder pb = new ProcessBuilder(args);
                pb.directory(new File(System.getProperty("user.dir")));

                // stderr
                if (stderrFile != null) {
                    ensureParentDirs(stderrFile);
                    pb.redirectError(stderrAppend
                        ? ProcessBuilder.Redirect.appendTo(new File(stderrFile))
                        : ProcessBuilder.Redirect.to(new File(stderrFile)));
                } else {
                    pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                }

                // stdout: last stage can redirect to file or terminal; intermediate stages pipe
                if (isLast && stdoutFile != null) {
                    ensureParentDirs(stdoutFile);
                    pb.redirectOutput(stdoutAppend
                        ? ProcessBuilder.Redirect.appendTo(new File(stdoutFile))
                        : ProcessBuilder.Redirect.to(new File(stdoutFile)));
                } else if (isLast) {
                    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                } else {
                    pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
                }

                // stdin always via PIPE so we can feed from prev process or builtin bytes
                pb.redirectInput(ProcessBuilder.Redirect.PIPE);

                Process p = pb.start();
                processes.add(p);
                lastProcess = p;

                // Capture references for the feeder thread
                final Process thisProcess = p;
                final Process sourcePrev = prevProcess;
                final byte[] sourceBuiltin = builtinInput;
                builtinInput = new byte[0];

                // Feeder thread: pipes data into this process's stdin concurrently
                Thread feeder = new Thread(() -> {
                    try (OutputStream os = thisProcess.getOutputStream()) {
                        if (sourcePrev != null) {
                            // Stream from previous process's stdout
                            try (InputStream is = sourcePrev.getInputStream()) {
                                byte[] buf = new byte[4096];
                                int read;
                                while ((read = is.read(buf)) != -1) {
                                    os.write(buf, 0, read);
                                    os.flush();
                                }
                            }
                        } else if (sourceBuiltin.length > 0) {
                            os.write(sourceBuiltin);
                            os.flush();
                        }
                        // Closing os signals EOF to the process
                    } catch (IOException e) {
                        // Broken pipe is normal when downstream exits early (e.g. head -n 5)
                    }
                });
                feeder.setDaemon(true);
                feeder.start();

                prevProcess = p;
            }
        }

        // Wait for all processes to finish (unless background)
        if (!isBackgroundJob) {
            for (Process p : processes) {
                p.waitFor();
            }
        } else if (lastProcess != null) {
            int currentJobNumber = 1;
            while (true) {
                boolean idTaken = false;
                for (BackgroundJob job : activeJobs) {
                    if (job.id == currentJobNumber) { idTaken = true; break; }
                }
                if (!idTaken) break;
                currentJobNumber++;
            }
            System.out.print("[" + currentJobNumber + "] " + lastProcess.pid() + "\r\n");
            System.out.flush();
            activeJobs.add(new BackgroundJob(currentJobNumber, lastProcess, originalInput.replace("&", "").trim()));
        }

        checkAndReportBackgroundJobs(false);
        System.out.print("$ ");
        System.out.flush();
    }

    private static void executeSingleCommand(String stageInput, boolean isBackgroundJob, String originalInput) throws Exception {
        String[] rawParts = stageInput.trim().split("\\s+");
        ParsedCommand parsed = parseCommand(stageInput);
        List<String> args = parsed.args;
        List<Redirect> redirects = parsed.redirects;

        if (args.isEmpty()) {
            checkAndReportBackgroundJobs(false);
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

        if (command.equals("complete") || (rawParts.length > 0 && rawParts[0].equals("complete"))) {
            String result = "";
            String[] activeArgs = (rawParts.length > args.size()) ? rawParts : args.toArray(new String[0]);
            
            if (activeArgs.length == 1) {
                List<String> lines = new ArrayList<>();
                for (Map.Entry<String, String> entry : completionRegistry.entrySet()) {
                    lines.add("complete -C '" + entry.getValue() + "' " + entry.getKey());
                }
                Collections.sort(lines);
                result = String.join("\r\n", lines);
            } else if (activeArgs.length == 3 && activeArgs[1].equalsIgnoreCase("-r")) {
                completionRegistry.remove(activeArgs[2]);
            } else if (activeArgs.length == 4 && activeArgs[1].equalsIgnoreCase("-c")) {
                completionRegistry.put(activeArgs[3], activeArgs[2]);
            } else if (activeArgs.length == 3 && activeArgs[1].equalsIgnoreCase("-p")) {
                String targetCmd = activeArgs[2];
                if (completionRegistry.containsKey(targetCmd)) {
                    result = "complete -C '" + completionRegistry.get(targetCmd) + "' " + targetCmd;
                } else {
                    result = "complete: " + targetCmd + ": no completion specification";
                }
            } else if (activeArgs.length == 2) {
                String targetCmd = activeArgs[1];
                if (completionRegistry.containsKey(targetCmd)) {
                    result = "complete -C '" + completionRegistry.get(targetCmd) + "' " + targetCmd;
                } else {
                    result = "complete: " + targetCmd + ": no completion specification";
                }
            } else {
                result = "complete: usage: complete -c command completion_script or complete -r command";
            }

            ensureRedirectFile(stderrFile, stderrAppend);
            if (stdoutFile != null) {
                ensureParentDirs(stdoutFile);
                try (PrintWriter pw = new PrintWriter(new FileWriter(stdoutFile, stdoutAppend))) { 
                    if (!result.isEmpty()) pw.println(result); 
                }
            } else {
                if (!result.isEmpty()) System.out.print(result + "\r\n");
            }
            checkAndReportBackgroundJobs(false);
            System.out.print("$ ");
            System.out.flush();
            return;
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
            checkAndReportBackgroundJobs(false);
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
            checkAndReportBackgroundJobs(false);
            System.out.print("$ ");
            System.out.flush();

        } else if (command.equals("type")) {
            String result = "";
            if (args.size() > 1) {
                String targetCommand = args.get(1);
                Set<String> builtinSet = new HashSet<>(Arrays.asList("complete","jobs","exit","type","echo","pwd","cd"));
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
            checkAndReportBackgroundJobs(false);
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
            checkAndReportBackgroundJobs(false);
            System.out.print("$ ");
            System.out.flush();

        } else if (command.equals("jobs")) {
            checkAndReportBackgroundJobs(true);
            System.out.print("$ ");
            System.out.flush();
            return;
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
                checkAndReportBackgroundJobs(false);
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
            
            if (isBackgroundJob) {
                int currentJobNumber = 1;
                while (true) {
                    boolean idTaken = false;
                    for (BackgroundJob job : activeJobs) {
                        if (job.id == currentJobNumber) {
                            idTaken = true;
                            break;
                        }
                    }
                    if (!idTaken) break;
                    currentJobNumber++;
                }

                long pid = p.pid();
                System.out.print("[" + currentJobNumber + "] " + pid + "\r\n");
                activeJobs.add(new BackgroundJob(currentJobNumber, p, originalInput.replace("&", "").trim()));
            } else {
                p.waitFor();
            }
            
            checkAndReportBackgroundJobs(false);
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
        String[] builtins = {"complete", "exit", "jobs", "type", "echo", "pwd", "cd"};
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
                            if (f.isFile() && f.canExecute() && f.getName().startsWith(prefix) && !matches.contains(f.getName())) {
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

    private static List<String> findFileMatches(String prefix) {
        List<String> matches = new ArrayList<>();
        String baseDirStr = System.getProperty("user.dir");
        String searchPrefix = prefix;

        if (prefix.contains("/")) {
            int lastSlash = prefix.lastIndexOf('/');
            String pathSegment = prefix.substring(0, lastSlash + 1);
            searchPrefix = prefix.substring(lastSlash + 1);
            
            File resolvedDir = new File(pathSegment).isAbsolute() ? new File(pathSegment) : new File(baseDirStr, pathSegment);
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
                        String fullMatchPath = prefix.contains("/") ? prefix.substring(0, prefix.lastIndexOf('/') + 1) + matchedName : matchedName;
                        if (f.isDirectory()) fullMatchPath += "/";
                        matches.add(fullMatchPath);
                    }
                }
            }
        }
        Collections.sort(matches);
        return matches;
    }

    private static List<String> findProgrammableMatches(String cmd, String currentLine, int compPoint, String prefix) {
        List<String> matches = new ArrayList<>();
        String scriptPath = completionRegistry.get(cmd);
        if (scriptPath == null) return matches;

        try {
            List<String> cmdTokens = tokenize(currentLine);
            List<String> processArgs = new ArrayList<>();
            processArgs.add(scriptPath);
            processArgs.add(cmd);
            processArgs.add(prefix);
            if (cmdTokens.size() > 1) {
                processArgs.add(cmdTokens.get(cmdTokens.size() - 2));
            } else {
                processArgs.add("");
            }

            ProcessBuilder pb = new ProcessBuilder(processArgs);
            pb.directory(new File(System.getProperty("user.dir")));

            Map<String, String> env = pb.environment();
            env.put("COMP_LINE", currentLine);
            env.put("COMP_POINT", String.valueOf(compPoint));

            Process p = pb.start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && line.startsWith(prefix)) {
                        matches.add(line);
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) { }

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