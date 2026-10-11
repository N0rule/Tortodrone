package org.tortodrone.server;

import java.io.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.tortodrone.account.AccountStore;
import org.tortodrone.character.Currency;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

/** Local server terminal. Commands never accept passwords as arguments or echo input to logs. */
final class ServerConsole implements AutoCloseable {
    private final AccountStore accounts;
    private final ServerLog log;
    private final Runnable stop;
    private final BooleanSupplier running;
    private final ConsoleInput input;
    private volatile boolean closed;
    private final ServerControl control;
    private final Object executionLock=new Object();
    private final Prompts prompts;
    interface Prompts { char[] password(String prompt) throws IOException; String confirmation(int limit) throws IOException; }

    ServerConsole(AccountStore accounts, ServerLog log, Runnable stop, BooleanSupplier running) {
        this(accounts,log,stop,running,null,true);
    }
    ServerConsole(AccountStore accounts,ServerLog log,Runnable stop,BooleanSupplier running,ServerControl control,boolean terminal) {
        this.accounts=accounts;this.log=log;this.stop=stop;this.running=running;this.control=control;
        input=terminal?new ConsoleInput(log):null;
        prompts=new Prompts() {
            public char[] password(String prompt) throws IOException {
                if(input==null)throw new IOException("Open the account manager to enter passwords in graphical mode");
                return input.password(prompt,log);
            }
            public String confirmation(int limit) throws IOException {
                if(input==null)throw new IOException("Open the account manager for confirmed deletion in graphical mode");
                return input.confirmation(limit);
            }
        };
    }

    void start() {
        if(input==null)return;
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
                    executeLine(line);
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

    @Override public void close() {
        if(input==null){synchronized(executionLock){closeInput();}}else closeInput();
    }
    private synchronized void closeInput() {
        if (closed) return;
        closed = true;
        log.setConsoleOutput(null);
        if(input!=null)input.close();
    }

    void executeLine(String line) throws IOException {
        if(input==null){synchronized(executionLock){dispatch(line);}}else dispatch(line);
    }
    private void dispatch(String line) throws IOException {
        if(closed||!running.getAsBoolean())throw new IOException("Server is not running");
        line=line.strip();if(line.startsWith("/"))line=line.substring(1);
        if(line.isEmpty())return;
        if(line.length()>4096)throw new IOException("Command is too long");
        execute(line.split("\\s+"));
    }

    private void execute(String[] words) throws IOException {
        String command = ConsoleHelp.canonical(words[0]);
        words[0]=command;
        if(command.equals("help")) {
            if(words.length==1||words.length==2&&words[1].equalsIgnoreCase("--help"))showHelp(ConsoleHelp.overview());
            else if(words.length<=3)showHelp(ConsoleHelp.topic(words[1],words.length==3?words[2]:""));
            else showHelp(ConsoleHelp.topic("help",""));
            return;
        }
        boolean group=command.equals("account")||command.equals("knight")||command.equals("currency");
        if(ConsoleHelp.known(command) && words.length==1 && !command.equals("items")
                && !command.equals("status") && !command.equals("stop") && !command.equals("players")) {
            showHelp(ConsoleHelp.topic(command,""));return;
        }
        if(ConsoleHelp.known(command)&&words.length==2&&words[1].equalsIgnoreCase("--help")) {
            showHelp(ConsoleHelp.topic(command,""));return;
        }
        if(group&&words.length>1) {
            words[1]=words[1].toLowerCase(Locale.ROOT);
            if(words[1].equals("help")&&words.length<=3) {
                showHelp(ConsoleHelp.topic(command,words.length==3?words[2]:""));return;
            }
            if(words.length==3&&words[2].equalsIgnoreCase("--help")) {
                showHelp(ConsoleHelp.topic(command,words[1]));return;
            }
        }
        if(command.equals("giveitem")||command.equals("items")) {
            try {
                log.info(ItemCommands.execute(String.join(" ",words),accounts.catalog(),
                    (user,name,config,count)->accounts.grantItems(user,name,config,count,-1,-1)));
            } catch(IOException failure) {
                if(failure.getMessage().startsWith("Usage:"))throw new IOException(ConsoleHelp.usage(words));
                throw failure;
            }
        } else if (words.length == 1 && command.equals("status")) {
            if(control==null)log.info("Server running. Accounts: " + accounts.list().size());
            else {
                var status=control.snapshot();var metrics=status.metrics();
                log.info("Tortodrone "+status.version()+" | "+status.state()+" | "+status.endpoint()+" | uptime="+status.uptimeSeconds()+"s");
                log.info("Sessions: "+status.sessions().size()+" | Players: "+status.sessions().stream().filter(p->p.knightId()!=null).count()+" | Maps: "+status.maps()+" | Items: "+status.items()+" | Instances: "+status.scenes().size());
                log.info("Heap: "+RuntimeMetrics.mib(metrics.heapUsed())+" / "+RuntimeMetrics.mib(metrics.heapMax())+" MiB | Accounts: "+accounts.list().size());
            }
        } else if (words.length == 1 && command.equals("stop")) {
            log.info("Stopping server…"); stop.run();
        } else if(command.equals("grant")||command.equals("set")) {
            if(words.length!=4)throw new IOException(ConsoleHelp.usage(words));
            long amount;try{amount=Long.parseLong(words[3]);}catch(NumberFormatException invalid){throw new IOException("Amount must be a whole number");}
            var result=command.equals("grant")?accounts.currency(null,words[1],Currency.parse(words[2]),amount,-1,-1)
                :accounts.setCurrency(null,words[1],Currency.parse(words[2]),amount,-1,-1);
            log.info("Currency "+command+" | knight="+words[1]+" (#"+result.worldId()+") | accountId="+result.accountId()+" | Crowns: "+result.crowns()+" | Energy: "+result.energy());
        } else if(command.equals("players")&&words.length==1) {
            if(control==null)throw new IOException("Session monitoring unavailable");
            log.info("Session | Account | Knight | Scene | Duration | State");
            for(var player:control.sessions().snapshots())log.info(player.id()+" | "+player.username()+" (#"+player.accountId()+") | "+player.knight()+" (#"+player.knightId()+") | "+player.scene()+" (template="+player.sceneId()+", instance="+player.instanceId()+") | "+duration(player.connectedAt())+" | "+player.state());
            log.info("Sessions: "+control.sessions().snapshots().size());
        } else if(command.equals("session")&&words.length==2) {
            if(control==null)throw new IOException("Session monitoring unavailable");
            long id;try{id=Long.parseLong(words[1]);}catch(NumberFormatException invalid){throw new IOException("Session ID must be a whole number");}
            var player=control.sessions().find(id);if(player==null)throw new IOException("No such active session");
            log.info(player.toString());
        } else if(command.equals("inspect")&&words.length==2) {
            var profile=accounts.inspectKnight(words[1]);
            log.info("Knight "+words[1]+" | knightId="+profile.worldId()+" | rosterId="+profile.knightId()+" | accountId="+profile.accountId()+" | Crowns: "+profile.crowns()+" | Energy: "+profile.energy()+" | Items: "+profile.items().size());
            if(control!=null)control.sessions().snapshots().stream().filter(p->java.util.Objects.equals(p.knightId(),profile.worldId())).forEach(p->log.info("Online | session="+p.id()+" | scene="+p.scene()+" | instanceId="+p.instanceId()));
        } else if (command.equals("currency")) {
            currency(words);
        } else if (command.equals("knight")) {
            knight(words);
        } else if (command.equals("gm")) {
            if(!(words.length==2||words.length==3&&(words[2].equals("true")||words[2].equals("false"))))
                throw new IOException(ConsoleHelp.usage(words));
            String[] accountWords = new String[words.length+1];
            accountWords[0]="account";accountWords[1]="gm";
            System.arraycopy(words,1,accountWords,2,words.length-1);
            account(accountWords);
        } else if (command.equals("account") || command.equals("ac")) {
            account(words);
        } else if(ConsoleHelp.known(command))throw new IOException(ConsoleHelp.usage(words));
        else throw new IOException("Unknown command. Type help to see commands, or press Up to correct your input.");
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
            log.info("Crowns: " + profile.crowns() + " | Energy: " + profile.energy());
            for (var item : profile.items()) log.info(item.slot() + " | item=" + item.id() + " | " + item.config().substring(item.config().lastIndexOf('/')+1) + " | path=" + item.config()
                + (item.accessory() == null ? "" : " | accessory=" + item.accessory()) + " | heat=" + item.heat() + " | equipped=" + item.equippedSlot());
        } else if(words.length==4&&words[1].equals("delete")) {
            var profile=accounts.worldProfile(words[2],words[3]);
            var name=accounts.knights(words[2]).stream().filter(k->k.id()==profile.knightId()).findFirst().orElseThrow(()->new IOException("Knight changed; refresh"));
            log.info("Delete "+name.name()+" and all its items/balances? Type the exact knight name to confirm:");
            if(!name.name().equals(prompts.confirmation(16)))throw new IOException("Deletion confirmation did not match");
            if(!running.getAsBoolean())return;
            accounts.deleteKnight(words[2],name.name(),profile.accountId(),profile.worldId());
            log.info("Knight deleted: "+name.name()+"; this account's sessions will disconnect.");
        } else throw new IOException(ConsoleHelp.usage(words));
    }

    private void currency(String[] words) throws IOException {
        if (words.length != 6 || !(words[1].equals("give") || words[1].equals("withdraw")))
            throw new IOException(ConsoleHelp.usage(words));
        long amount;
        try { amount = Long.parseLong(words[5]); }
        catch (NumberFormatException failure) { throw new IOException("Amount must be a positive whole number"); }
        if (amount <= 0 || amount > Integer.MAX_VALUE) throw new IOException("Amount must be between 1 and 2147483647");
        var result = accounts.currency(words[2],words[3],Currency.parse(words[4]),words[1].equals("give") ? amount : -amount,-1,-1);
        log.info(words[3] + " | Crowns: " + result.crowns() + " | Energy: " + result.energy());
    }

    private void account(String[] words) throws IOException {
        if (words.length == 2 && words[1].equals("list")) {
            log.info("ID | Username | Game Master | Characters");
            for (var account : accounts.list()) log.info(account.id() + " | " + account.username() + " | " + account.gameMaster() + " | " + account.knights());
            log.info("Accounts: " + accounts.list().size()); return;
        }
        if (words.length < 3) throw new IOException(ConsoleHelp.usage(words));
        boolean create = words[1].equals("create") && (words.length == 3 || words.length == 4 && words[3].equals("gm"));
        boolean reset = words[1].equals("password") && words.length == 3;
        boolean check = words[1].equals("check") && words.length == 3;
        boolean role = words[1].equals("gm") && (words.length == 3 || words.length == 4 && (words[3].equals("true")||words[3].equals("false")));
        boolean delete = words[1].equals("delete") && words.length == 3;
        if(!create&&!reset&&!check&&!role&&!delete)throw new IOException(ConsoleHelp.usage(words));
        String name = words[2]; AccountStore.validateUsername(name);
        if (words[1].equals("gm") && words.length == 3) {
            boolean enabled=accounts.toggleGameMaster(name);
            log.info("Game Master status changed: " + name + " = " + enabled); return;
        }
        if (words[1].equals("gm") && words.length == 4 && (words[3].equals("true") || words[3].equals("false"))) {
            accounts.setGameMaster(name, Boolean.parseBoolean(words[3])); log.info("Game Master status changed: " + name + " = " + words[3]); return;
        }
        if (words[1].equals("delete") && words.length == 3) {
            var selected = accounts.list().stream().filter(a -> a.username().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new IOException("No such account"));
            log.info("Delete " + selected.username() + " and " + selected.knights() + " character(s)? Type the exact username to confirm:");
            if (!selected.username().equals(prompts.confirmation(32))) throw new IOException("Deletion confirmation did not match");
            if (!running.getAsBoolean()) return;
            accounts.delete(name, selected.id()); log.info("Account deleted: " + selected.username()); return;
        }
        // Capture the selected identity before prompting; deletion/recreation must not redirect a reset.
        int expectedId = -1;
        if (!create) expectedId = accounts.list().stream().filter(a -> a.username().equalsIgnoreCase(name)).findFirst().orElseThrow(() -> new IOException("No such account")).id();
        char[] password = prompts.password("Password: "), confirmation = null;
        try {
            if (check) {
                if (running.getAsBoolean()) log.info(accounts.checkPassword(name, password) ? "Password matches." : "Password does not match.");
                return;
            }
            confirmation = prompts.password("Confirm password: ");
            if (!Arrays.equals(password, confirmation)) throw new IOException("Passwords do not match");
            if (!running.getAsBoolean()) return;
            if (create) accounts.create(name, password, words.length == 4);
            else accounts.setPassword(name, password, expectedId);
            log.info((create ? "Account created: " : "Password changed: ") + name);
        } finally {
            Arrays.fill(password, '\0'); if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
    }

    private static String duration(java.time.Instant start) {return start==null?"0s":java.time.Duration.between(start,java.time.Instant.now()).toSeconds()+"s";}

    private void showHelp(java.util.List<String> lines) { lines.forEach(log::info); }

}
