package org.tortodrone.server;

import java.io.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.tortodrone.account.AccountStore;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

/** Local server terminal. Commands never accept passwords as arguments or echo input to logs. */
final class ServerConsole implements AutoCloseable {
    private final AccountStore accounts;
    private final ServerLog log;
    private final Runnable stop;
    private final BooleanSupplier running;
    private final ConsoleInput input;
    private boolean closed;

    ServerConsole(AccountStore accounts, ServerLog log, Runnable stop, BooleanSupplier running) {
        this.accounts = accounts; this.log = log; this.stop = stop; this.running = running;
        input = new ConsoleInput(log);
    }

    void start() {
        log.setConsoleOutput(input::printAbove);
        Thread thread = new Thread(this::readCommands, "ServerConsole");
        thread.setDaemon(true); thread.start();
    }

    private void readCommands() {
        try {
            while (running.getAsBoolean()) {
                try {
                    String line = input.command();
                    if (line == null) { log.debug("Terminal input closed; server remains running"); return; }
                    if (!running.getAsBoolean()) return;
                    line = line.trim(); if (line.isEmpty()) continue;
                    if (line.startsWith("/")) line = line.substring(1);
                    execute(line.split("\\s+"));
                } catch (IOException failure) {
                    log.warn(failure.getMessage());
                }
            }
        } catch (UserInterruptException interrupted) {
            log.info("Stopping server…"); stop.run();
        } catch (EndOfFileException ended) {
            log.debug("Terminal input closed; server remains running");
        } catch (IOError failed) {
            log.warn("Terminal input failed; server remains running");
        } finally { close(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        log.setConsoleOutput(null);
        input.close();
    }

    private void execute(String[] words) throws IOException {
        String command = words[0].toLowerCase(Locale.ROOT);
        if (words.length == 1 && command.equals("help")) {
            log.info("Commands: help | status | stop | account list | account create <username> [admin]");
            log.info("knight list <username> | knight info <username> <knight-name>");
            log.info("account password <username> | account check <username> | account admin <username> <true|false> | account delete <username>");
        } else if (words.length == 1 && command.equals("status")) {
            log.info("Server running. Accounts: " + accounts.list().size());
        } else if (words.length == 1 && command.equals("stop")) {
            log.info("Stopping server…"); stop.run();
        } else if (command.equals("knight")) {
            knight(words);
        } else if (command.equals("account")) {
            account(words);
        } else throw new IOException("Unknown command or arguments. Type help for commands.");
    }

    private void knight(String[] words) throws IOException {
        if (words.length == 3 && words[1].equals("list")) {
            var knights = accounts.knights(words[2]);
            log.info("ID | Knight");
            for (var knight : knights) log.info(knight.id() + " | " + knight.name());
            log.info("Knights: " + knights.size());
        } else if (words.length == 4 && words[1].equals("info")) {
            var profile = accounts.worldProfile(words[2],words[3]);
            log.info("Knight " + words[3] + ": account=" + profile.accountId() + ", roster ID=" + profile.knightId() + ", world ID=" + profile.worldId());
            for (var item : profile.items()) log.info(item.slot() + " | item=" + item.id() + " | " + item.config()
                + (item.accessory() == null ? "" : " | accessory=" + item.accessory()) + " | heat=" + item.heat());
        } else throw new IOException("Invalid knight command. Type help for commands.");
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
            if (!selected.username().equals(input.confirmation(32))) throw new IOException("Deletion confirmation did not match");
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
        char[] password = input.password("Password: ",log), confirmation = null;
        try {
            if (check) {
                if (running.getAsBoolean()) log.info(accounts.checkPassword(name, password) ? "Password matches." : "Password does not match.");
                return;
            }
            confirmation = input.password("Confirm password: ",log);
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

}
