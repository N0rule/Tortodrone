package org.tortodrone.admin;

import java.awt.GraphicsEnvironment;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import javax.swing.*;
import org.tortodrone.account.AccountStore;

/** Local desktop account manager; shares only the database with the game server. */
public final class AdminApplication {
    public static void main(String[] args) {
        try {
            String[] launchArguments = args.clone();
            if (args.length == 1 && args[0].equals("--help")) {
                System.out.println("Usage: java -jar tortodrone-admin.jar [--data path/to/server/data] [command]");
                System.out.println("No command opens the desktop account manager.");
                System.out.println("Commands: --list-accounts | --create-account username [--gm] | --set-password username | --check-password username | --set-gm username true|false | --delete-account username | --version");
                System.out.println("Knights: --list-knights username | --delete-knight username knight-name (exact name confirmation)");return;
            }
            if (args.length == 1 && args[0].equals("--version")) {
                try (var stream = AdminApplication.class.getResourceAsStream("/version.txt")) {
                    System.out.println("Tortodrone Game Master " + new String(stream.readAllBytes(),StandardCharsets.UTF_8).trim());
                } return;
            }
            Path location = Path.of(AdminApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path directory = (Files.isDirectory(location) ? location : location.getParent()).resolve("data");
            if (args.length >= 2 && args[0].equals("--data")) { directory = Path.of(args[1]); args = Arrays.copyOfRange(args,2,args.length); }
            if (args.length == 0) {
                Integer desktopExit = DesktopBootstrap.relaunchIfNeeded(location,launchArguments);
                if (desktopExit != null) { if (desktopExit != 0) System.exit(desktopExit); return; }
                if (GraphicsEnvironment.isHeadless()) throw new IOException("A graphical desktop is required. Run on the server PC's desktop, or use --help for terminal commands.");
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                Path data = directory;
                SwingUtilities.invokeLater(() -> new AdminWindow(data).setVisible(true));
            } else try (AccountStore accounts = new AccountStore(directory)) { command(accounts,args); }
        } catch (Exception failure) {
            System.err.println("Account manager: " + failure.getMessage()); System.exit(1);
        }
    }
    private static void command(AccountStore accounts,String[] args) throws IOException {
        if (args.length == 1 && args[0].equals("--list-accounts")) {
            System.out.println("ID\tUsername\tGame Master\tCharacters");
            for (var a : accounts.list()) System.out.println(a.id()+"\t"+a.username()+"\t"+a.gameMaster()+"\t"+a.knights()); return;
        }
        if (args.length < 2) throw new IOException("Use --help for account commands");
        String username = args[1]; var input = new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
        if(args.length==2&&args[0].equals("--list-knights")) {
            System.out.println("Roster ID\tWorld ID\tKnight\tCrowns\tEnergy");
            for(var knight:accounts.knights(username)) {
                var profile=accounts.worldProfile(username,knight.name());
                System.out.println(knight.id()+"\t"+profile.worldId()+"\t"+knight.name()+"\t"+profile.crowns()+"\t"+profile.energy());
            }
            return;
        }
        if(args.length==3&&args[0].equals("--delete-knight")) {
            var account=selected(accounts,username);var profile=accounts.worldProfile(username,args[2]);
            var knight=accounts.knights(username).stream().filter(k->k.id()==profile.knightId()).findFirst().orElseThrow(()->new IOException("Knight changed; refresh"));
            String confirmation=System.console()==null?input.readLine():System.console().readLine("Type the exact knight name to delete it and its items/balances: ");
            if(!knight.name().equals(confirmation))throw new IOException("Deletion confirmation did not match");
            accounts.deleteKnight(username,knight.name(),account.id(),profile.worldId());System.out.println("Knight deleted.");return;
        }
        if (args.length == 2 && args[0].equals("--delete-account")) {
            var selected = selected(accounts,username);
            String confirmation = System.console() == null ? input.readLine() : System.console().readLine("Type the account username to delete it and its characters: ");
            if (!selected.username().equals(confirmation)) throw new IOException("Deletion confirmation did not match"); accounts.delete(username,selected.id()); System.out.println("Account deleted."); return;
        }
        if (args.length == 3 && (args[0].equals("--set-gm") || args[0].equals("--set-admin")) && (args[2].equals("true") || args[2].equals("false"))) {
            accounts.setGameMaster(username,Boolean.parseBoolean(args[2])); System.out.println("Game Master status changed."); return;
        }
        boolean create = args[0].equals("--create-account") && (args.length == 2 || args.length == 3 && (args[2].equals("--gm") || args[2].equals("--admin")));
        boolean reset = args[0].equals("--set-password") && args.length == 2;
        boolean check = args[0].equals("--check-password") && args.length == 2;
        if (!create && !reset && !check) throw new IOException("Use --help for account commands");
        int expectedId = create ? -1 : selected(accounts,username).id();
        char[] password = password(input,"Password: "), confirmation = null;
        try {
            if (check) { System.out.println(accounts.checkPassword(username,password) ? "Password matches." : "Password does not match."); return; }
            confirmation = password(input,"Confirm password: ");
            if (!Arrays.equals(password,confirmation)) throw new IOException("Passwords do not match");
            if (create) accounts.create(username,password,args.length == 3); else accounts.setPassword(username,password,expectedId);
            System.out.println(create ? "Account created." : "Password changed.");
        } finally { Arrays.fill(password,'\0'); if (confirmation != null) Arrays.fill(confirmation,'\0'); }
    }
    private static AccountStore.Summary selected(AccountStore accounts,String username) throws IOException {
        return accounts.list().stream().filter(a -> a.username().equalsIgnoreCase(username)).findFirst().orElseThrow(() -> new IOException("No such account"));
    }
    private static char[] password(BufferedReader input,String prompt) throws IOException {
        if (System.console() != null) { char[] password = System.console().readPassword("%s",prompt); if (password == null) throw new IOException("Password input ended"); return password; }
        char[] buffer = new char[129]; int length = 0;
        try {
            int value;
            while ((value = input.read()) != -1 && value != '\n') {
                if (value == '\r') { if (input.read() != '\n') throw new IOException("Invalid password line ending"); return Arrays.copyOf(buffer,length); }
                if (length == buffer.length) throw new IOException("Password exceeds 128 characters"); buffer[length++] = (char)value;
            }
            if (value == -1) throw new IOException("Password input ended; supply complete lines"); return Arrays.copyOf(buffer,length);
        } finally { Arrays.fill(buffer,'\0'); }
    }
}
