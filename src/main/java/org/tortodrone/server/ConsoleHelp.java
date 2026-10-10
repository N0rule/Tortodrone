package org.tortodrone.server;

import java.util.List;
import java.util.Locale;

/** Local terminal syntax, descriptions and examples; help never performs an action. */
final class ConsoleHelp {
    private record Entry(String command, String action, String syntax, String description, String example) { }
    private static final List<Entry> ENTRIES = List.of(
        new Entry("help", "", "help [command] [action]", "Show all commands or help for one command.", "help account create"),
        new Entry("status", "", "status", "Show server status and account count.", "status"),
        new Entry("stop", "", "stop", "Shut down the server cleanly.", "stop"),
        new Entry("account", "list", "account list", "List accounts and Game Master roles.", "ac list"),
        new Entry("account", "create", "account create <username> [gm]", "Create an account; enter its password at the prompts.", "account create Alice"),
        new Entry("account", "password", "account password <username>", "Change a password; existing sessions disconnect.", "ac password Alice"),
        new Entry("account", "check", "account check <username>", "Check a password entered at the prompt.", "ac check Alice"),
        new Entry("account", "gm", "account gm <username> [true|false]", "Toggle the Game Master role, or set it explicitly.", "ac gm Alice true"),
        new Entry("account", "delete", "account delete <username>", "Delete an account and its knights after confirmation.", "account delete Alice"),
        new Entry("gm", "", "gm <username> [true|false]", "Toggle the Game Master role, or set it explicitly.", "gm Alice"),
        new Entry("knight", "list", "knight list <username>", "List an account's saved knights.", "knight list Alice"),
        new Entry("knight", "info", "knight info <username> <knight-name>", "Show a knight's identity, balances and inventory.", "knight info Alice Jane"),
        new Entry("knight", "delete", "knight delete <username> <knight-name>", "Delete a knight after exact-name confirmation.", "knight delete Alice Jane"),
        new Entry("currency", "give", "currency give <username> <knight-name> <crowns|energy> <amount>", "Add currency to a knight.", "currency give Alice Jane crowns 100"),
        new Entry("currency", "withdraw", "currency withdraw <username> <knight-name> <crowns|energy> <amount>", "Withdraw currency; the balance cannot become negative.", "currency withdraw Alice Jane energy 5"),
        new Entry("items", "", "items [search]", "Search the item catalog; narrow the search for more results.", "items Proto Sword"),
        new Entry("giveitem", "", "giveitem <knight-name> <1-20> <catalogue ID or exact item config>", "Grant unequipped items by unique knight name. The account-qualified form also works.", "giveitem Jane 2 Weapon/Sword/Proto Sword")
    );

    static String canonical(String command) {
        command=command.toLowerCase(Locale.ROOT);
        return command.equals("ac") ? "account" : command;
    }

    static boolean known(String command) {
        String name=canonical(command);
        return ENTRIES.stream().anyMatch(e->e.command().equals(name));
    }

    static List<String> overview() {
        var lines=new java.util.ArrayList<String>();
        lines.add("Server console commands:");
        lines.add("  help [command] [action]  Show detailed usage and an example.");
        lines.add("  status | stop           Show status or shut down cleanly.");
        lines.add("  account (alias: ac)     List, create, check, reset or delete accounts; manage GM roles.");
        lines.add("  gm                      Toggle or set an account's Game Master role.");
        lines.add("  knight                  List, inspect or delete saved knights.");
        lines.add("  currency                Give or withdraw crowns/energy.");
        lines.add("  items | giveitem        Find items or grant them to a knight.");
        lines.add("Try: help account create. You can also type account, account help, or account create --help.");
        lines.add("<...> is required; [...] is optional. Passwords are entered only at prompts.");
        lines.add("Up/Down recalls the last 100 typed commands, including typos. A leading / is optional.");
        return List.copyOf(lines);
    }

    static List<String> topic(String command, String action) {
        String name=canonical(command), sub=action.toLowerCase(Locale.ROOT);
        var entries=ENTRIES.stream().filter(e->e.command().equals(name)
            && (sub.isEmpty() || e.action().isEmpty() || e.action().equals(sub))).toList();
        if(entries.isEmpty()) {
            if(known(name)) return topic(name, "");
            return List.of("Unknown help topic. Try help to see the available commands.");
        }
        var lines=new java.util.ArrayList<String>();
        if(name.equals("account"))lines.add("Account commands (alias: ac):");
        for(var entry:entries) {
            lines.add("Usage: " + entry.syntax());
            lines.add("  " + entry.description());
            lines.add("  Example: " + entry.example());
        }
        return List.copyOf(lines);
    }

    static String usage(String[] words) {
        String action=words.length>1?words[1]:"";
        // An unknown action falls back to the command's available forms, without echoing input.
        return String.join(" ", topic(words[0], action));
    }
}
