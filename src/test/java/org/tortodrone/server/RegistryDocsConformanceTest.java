package org.tortodrone.server;

import static org.tortodrone.support.TestWire.check;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Holdings the documentation to the registries it describes.
 *
 * <p>Every defect this guards against was real: the supported-scope table called guilds
 * unimplemented while {@code GuildHandler} served eighteen of the stock calls, the command
 * reference listed guild chat as unavailable while it was dispatched and rate-limited, and a
 * content file declared a station kind no handler matched. Prose cannot be checked by a compiler,
 * so the mechanical claims are checked here: documented console commands, authored station kinds
 * and local documentation links.
 */
public final class RegistryDocsConformanceTest {
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern STATION_KIND = Pattern.compile("^\\s+kind:\\s*(\\S+)\\s*$");

    public static void main(String[] args) throws Exception {
        consoleCommandsAreDocumented();
        stationKindsAreKnown();
        documentationLinksResolve();
        System.out.println(
                "PASS: documented console commands, authored station kinds and local documentation links match the registries");
    }

    /** Every console command has help, and every help entry is in the console table. */
    private static void consoleCommandsAreDocumented() throws IOException {
        var documented = documentedConsoleCommands();
        var implemented = new TreeSet<>(ConsoleHelp.commands());
        check(
                documented.equals(implemented),
                "docs/commands.md console table and ConsoleHelp disagree: documented-only="
                        + difference(documented, implemented) + " implemented-only="
                        + difference(implemented, documented));
    }

    private static TreeSet<String> documentedConsoleCommands() throws IOException {
        var names = new TreeSet<String>();
        boolean section = false;
        for (String line : Files.readAllLines(Path.of("docs/commands.md"))) {
            if (line.startsWith("## ")) {
                section = line.equals("## Local server console");
                continue;
            }
            if (!section || !line.startsWith("| `")) continue;
            int end = line.indexOf('`', 3);
            if (end < 0) continue;
            String syntax = line.substring(3, end);
            int space = syntax.indexOf(' ');
            names.add(space < 0 ? syntax : syntax.substring(0, space));
        }
        check(!names.isEmpty(), "the console table was not found in docs/commands.md");
        return names;
    }

    /** A station kind the protocol and handler do not dispatch would load as a dead NPC. */
    private static void stationKindsAreKnown() throws IOException {
        var kinds = new TreeSet<String>();
        for (String line : Files.readAllLines(Path.of("content/town/stations.yml"))) {
            var match = STATION_KIND.matcher(line);
            if (match.matches()) kinds.add(match.group(1));
        }
        check(kinds.size() > 5, "content/town/stations.yml declares no station kinds");
        for (String kind : kinds)
            check(
                    org.tortodrone.core.town.TownCatalog.STATION_KINDS.contains(kind),
                    "content/town/stations.yml declares a kind no handler dispatches: " + kind);
    }

    /** Local links in the tracked documentation must resolve from the file that carries them. */
    private static void documentationLinksResolve() throws IOException {
        checkLinks(Path.of("README.md"));
        checkLinks(Path.of("CONTRIBUTING.md"));
        try (var files = Files.list(Path.of("docs"))) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".md")).sorted().toList()) checkLinks(file);
        }
    }

    private static void checkLinks(Path file) throws IOException {
        Path base = file.getParent() == null ? Path.of("") : file.getParent();
        for (String line : Files.readAllLines(file)) {
            var match = LINK.matcher(line);
            while (match.find()) {
                String target = match.group(1);
                if (target.startsWith("http") || target.startsWith("mailto:") || target.startsWith("#")) continue;
                int fragment = target.indexOf('#');
                String relative = fragment < 0 ? target : target.substring(0, fragment);
                if (relative.isBlank()) continue;
                Path resolved = base.resolve(relative).normalize();
                check(Files.exists(resolved), file + " links to a missing path: " + target);
            }
        }
    }

    private static String difference(TreeSet<String> left, TreeSet<String> right) {
        var only = new TreeSet<>(left);
        only.removeAll(right);
        return only.toString();
    }
}
