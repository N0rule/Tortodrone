package org.tortodrone.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.tortodrone.account.AccountStore;

/** Local server terminal. Commands never accept passwords as arguments or echo input to logs. */
final class ServerConsole {
    private final AccountStore accounts;
    private final ServerLog log;
    private final Runnable stop;
    private final BooleanSupplier running;
    private final Console terminal = System.console();
    private final BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    ServerConsole(AccountStore accounts, ServerLog log, Runnable stop, BooleanSupplier running) {
        this.accounts = accounts; this.log = log; this.stop = stop; this.running = running;
    }

    void start() {
        Thread thread = new Thread(this::readCommands, "ServerConsole");
        thread.setDaemon(true); thread.start();
    }

    private void readCommands() {
        while (running.getAsBoolean()) {
            try {
                String line = line(4096);
                if (line == null) { log.debug("Terminal input closed; server remains running"); return; }
                if (!running.getAsBoolean()) return;
                line = line.trim(); if (line.isEmpty()) continue;
                if (line.startsWith("/")) line = line.substring(1);
                execute(line.split("\\s+"));
            } catch (IOException failure) {
                log.warn(failure.getMessage());
            }
        }
    }

    private void execute(String[] words) throws IOException {
        String command = words[0].toLowerCase(Locale.ROOT);
        if (words.length == 1 && command.equals("help")) {
            log.info("Commands: help | status | stop | account list | account create <username> [admin]");
            log.info("account password <username> | account check <username> | account admin <username> <true|false> | account delete <username>");
        } else if (words.length == 1 && command.equals("status")) {
            log.info("Server running. Accounts: " + accounts.list().size());
        } else if (words.length == 1 && command.equals("stop")) {
            log.info("Stopping server…"); stop.run();
        } else if (command.equals("account")) {
            account(words);
        } else throw new IOException("Unknown command or arguments. Type help for commands.");
    }

    private void account(String[] words) throws IOException {
        if (words.length == 2 && words[1].equals("list")) {
            log.info("ID | Username | Admin | Characters");
            for (var account : accounts.list()) log.info(account.id() + " | " + account.username() + " | " + account.admin() + " | " + account.knights());
            log.info("Accounts: " + accounts.list().size()); return;
        }
        if (words.length < 3) throw usage();
        String name = words[2]; AccountStore.validateUsername(name);
        if (words[1].equals("admin") && words.length == 4 && (words[3].equals("true") || words[3].equals("false"))) {
            accounts.setAdmin(name, Boolean.parseBoolean(words[3])); log.info("Admin status changed: " + name); return;
        }
        if (words[1].equals("delete") && words.length == 3) {
            var selected = accounts.list().stream().filter(a -> a.username().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new IOException("No such account"));
            log.info("Delete " + selected.username() + " and " + selected.knights() + " character(s)? Type the exact username to confirm:");
            if (!selected.username().equals(line(32))) throw new IOException("Deletion confirmation did not match");
            if (!running.getAsBoolean()) return;
            accounts.delete(name, selected.id()); log.info("Account deleted: " + selected.username()); return;
        }
        boolean create = words[1].equals("create") && (words.length == 3 || words.length == 4 && words[3].equals("admin"));
        boolean reset = words[1].equals("password") && words.length == 3;
        boolean check = words[1].equals("check") && words.length == 3;
        if (!create && !reset && !check) throw usage();
        // Capture the selected identity before prompting; deletion/recreation must not redirect a reset.
        int expectedId = -1;
        if (!create) expectedId = accounts.list().stream().filter(a -> a.username().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new IOException("No such account")).id();
        char[] password = password("Password: "), confirmation = null;
        try {
            if (check) {
                if (running.getAsBoolean()) log.info(accounts.checkPassword(name, password) ? "Password matches." : "Password does not match.");
                return;
            }
            confirmation = password("Confirm password: ");
            if (!Arrays.equals(password, confirmation)) throw new IOException("Passwords do not match");
            if (!running.getAsBoolean()) return;
            if (create) accounts.create(name, password, words.length == 4);
            else accounts.setPassword(name, password, expectedId);
            log.info((create ? "Account created: " : "Password changed: ") + name);
        } finally {
            Arrays.fill(password, '\0'); if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private IOException usage() { return new IOException("Invalid account command. Type help for commands."); }

    private char[] password(String prompt) throws IOException {
        if (terminal != null) {
            char[] value = terminal.readPassword("%s", prompt);
            if (value == null) throw new IOException("Password input ended");
            return value;
        }
        // Redirected stdin supplies complete lines; terminal passwords use Console.readPassword above.
        log.info(prompt.trim());
        char[] buffer = new char[129]; int length = 0; boolean overflow = false;
        try {
            int value;
            while ((value = input.read()) != -1 && value != '\n') {
                if (value == '\r') continue;
                if (length == 128) overflow = true; else buffer[length++] = (char)value;
            }
            if (value == -1) throw new IOException("Password input ended; supply complete lines");
            if (overflow) throw new IOException("Password exceeds 128 characters");
            return Arrays.copyOf(buffer, length);
        } finally { Arrays.fill(buffer, '\0'); }
    }

    private String line(int limit) throws IOException {
        if (terminal != null) {
            String value = terminal.readLine();
            if (value != null && value.length() > limit) throw new IOException("Terminal input is too long");
            return value;
        }
        StringBuilder value = new StringBuilder(); boolean overflow = false; int character;
        while ((character = input.read()) != -1 && character != '\n') {
            if (character == '\r') continue;
            if (value.length() == limit) overflow = true; else value.append((char)character);
        }
        if (overflow) throw new IOException("Terminal input is too long");
        return character == -1 && value.isEmpty() ? null : value.toString();
    }
}
