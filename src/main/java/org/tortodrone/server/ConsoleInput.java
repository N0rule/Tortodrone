package org.tortodrone.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/** Interactive command editing, with a separate history-free reader for sensitive prompts. */
final class ConsoleInput implements AutoCloseable {
    private final Console console = System.console();
    private final BufferedReader redirected = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private final Terminal terminal;
    private final LineReader commands;
    private final LineReader prompts;
    private volatile LineReader active;
    private boolean closed;

    ConsoleInput(ServerLog log) {
        Terminal opened = null;
        if (console != null) {
            try { opened = TerminalBuilder.builder().name("Tortodrone").system(true).dumb(false).ffm(false).build(); }
            catch (IOException | IllegalStateException failure) { log.warn("Terminal editing unavailable; using plain input"); }
        }
        terminal = opened;
        commands = terminal == null ? null : commandReader(terminal);
        prompts = terminal == null ? null : promptReader(terminal);
        active = commands;
    }

    static LineReader commandReader(Terminal terminal) {
        LineReader reader = reader(terminal).history(new CommandHistory()).variable(LineReader.HISTORY_SIZE, CommandHistory.LIMIT)
            .option(LineReader.Option.HISTORY_INCREMENTAL, false)
            .option(LineReader.Option.HISTORY_IGNORE_DUPS, false).build();
        bindCursorKeys(reader);
        return reader;
    }

    private static void bindCursorKeys(LineReader reader) {
        // Terminals emit either CSI or application cursor sequences. Accept both.
        var keys = reader.getKeyMaps().get(LineReader.EMACS);
        keys.bind(new Reference(LineReader.UP_LINE_OR_HISTORY), "\033[A", "\033OA");
        keys.bind(new Reference(LineReader.DOWN_LINE_OR_HISTORY), "\033[B", "\033OB");
        keys.bind(new Reference(LineReader.BACKWARD_CHAR), "\033[D", "\033OD");
        keys.bind(new Reference(LineReader.FORWARD_CHAR), "\033[C", "\033OC");
        reader.setKeyMap(LineReader.EMACS);
    }

    static LineReader promptReader(Terminal terminal) {
        LineReader reader = reader(terminal).variable(LineReader.DISABLE_HISTORY, true).build();
        bindCursorKeys(reader);
        return reader;
    }

    private static LineReaderBuilder reader(Terminal terminal) {
        // Commands and passwords are literal text, without shell quotes or history expansion.
        DefaultParser parser = new DefaultParser().quoteChars(new char[0]).escapeChars(new char[0]);
        return LineReaderBuilder.builder().terminal(terminal).appName("Tortodrone").parser(parser)
            .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true);
    }

    String command() throws IOException { return line(4096, true); }
    String confirmation(int limit) throws IOException { return line(limit, false); }

    private String line(int limit, boolean command) throws IOException {
        if (commands != null) {
            LineReader reader = command ? commands : prompts; active = reader;
            try {
                String value = reader.readLine(command ? "> " : "Confirm: ");
                if (value.length() > limit) throw new IOException("Terminal input is too long");
                return value;
            } finally { active = commands; if (!command) prompts.getBuffer().clear(); }
        }
        if (console != null) {
            String value = console.readLine();
            if (value != null && value.length() > limit) throw new IOException("Terminal input is too long");
            return value;
        }
        StringBuilder value = new StringBuilder(); boolean overflow = false; int character;
        while ((character = redirected.read()) != -1 && character != '\n') {
            if (character == '\r') continue;
            if (value.length() == limit) overflow = true; else value.append((char)character);
        }
        if (overflow) throw new IOException("Terminal input is too long");
        return character == -1 && value.isEmpty() ? null : value.toString();
    }

    char[] password(String prompt, ServerLog log) throws IOException {
        if (prompts != null) {
            active = prompts;
            try { return prompts.readLine(prompt, Character.valueOf('\0')).toCharArray(); }
            finally { prompts.getBuffer().clear(); active = commands; }
        }
        if (console != null) {
            char[] value = console.readPassword("%s", prompt);
            if (value == null) throw new IOException("Password input ended");
            return value;
        }
        log.info(prompt.trim());
        char[] buffer = new char[129]; int length = 0; boolean overflow = false;
        try {
            int value;
            while ((value = redirected.read()) != -1 && value != '\n') {
                if (value == '\r') continue;
                if (length == 128) overflow = true; else buffer[length++] = (char)value;
            }
            if (value == -1) throw new IOException("Password input ended; supply complete lines");
            if (overflow) throw new IOException("Password exceeds 128 characters");
            return Arrays.copyOf(buffer, length);
        } finally { Arrays.fill(buffer, '\0'); }
    }

    void printAbove(String message) {
        LineReader reader = active;
        if (reader != null) reader.printAbove(message);
        else System.out.println(message);
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (terminal != null) try { terminal.close(); } catch (IOException ignored) { }
    }
}
