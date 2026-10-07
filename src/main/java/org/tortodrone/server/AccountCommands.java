package org.tortodrone.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import org.tortodrone.account.AccountStore;

/** Offline account administration shares the deployment lock with the server. */
final class AccountCommands {
    static boolean matches(String[] args) {
        return args.length == 1 && args[0].equals("--list-accounts") || args.length == 2
            && (args[0].equals("--create-account") || args[0].equals("--set-password"));
    }

    static void run(String[] args, Path directory) throws IOException {
        if (args[0].equals("--list-accounts")) {
            for (var account : new AccountStore(directory).list()) {
                System.out.println(account.id() + "\t" + account.username());
            }
            return;
        }
        Console console = System.console();
        BufferedReader input = console == null ? new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)) : null;
        char[] password = readPassword(console, input, "Password: ");
        char[] confirmation = null;
        try {
            confirmation = readPassword(console, input, "Confirm password: ");
            if (!Arrays.equals(password, confirmation)) throw new IOException("Passwords do not match");
            AccountStore accounts = new AccountStore(directory);
            if (args[0].equals("--create-account")) accounts.create(args[1], password);
            else accounts.setPassword(args[1], password);
            System.out.println(args[0].equals("--create-account") ? "Account created." : "Password changed.");
        } finally {
            Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private static char[] readPassword(Console console, BufferedReader input, String prompt) throws IOException {
        if (console != null) {
            char[] value = console.readPassword("%s", prompt);
            if (value == null) throw new IOException("Password input ended");
            return value;
        }
        // Non-interactive automation supplies two newline-terminated UTF-8 lines on stdin.
        char[] buffer = new char[129]; int length = 0;
        try {
            int value;
            while ((value = input.read()) != -1 && value != '\n') {
                if (value == '\r') continue;
                if (length == buffer.length) throw new IOException("Password exceeds 128 characters");
                buffer[length++] = (char) value;
            }
            if (value == -1) throw new IOException("Password input ended; supply two complete lines");
            return Arrays.copyOf(buffer, length);
        } finally { Arrays.fill(buffer, '\0'); }
    }
}
