import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Read-only installed-client exporters/checks, with disposable home/preferences and Java 25 compilation. */
public final class StockTools {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: java scripts/StockTools.java previews|items|weapons|fires|equipment|scene|haven|town|town-check|recipes|craft-check game-directory [arguments]");
        Path project = Path.of("").toAbsolutePath(), game = Path.of(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(game.resolve("code/projectx-pcode.jar"))) throw new IOException("Missing installed stock game JAR");
        String fixture;
        var sources=new ArrayList<Path>();
        var fixtureArgs = new ArrayList<String>(List.of(game.toString()));
        switch (args[0]) {
            case "webui-presentation" -> {
                if(args.length!=4)throw new IllegalArgumentException("webui-presentation expects game-directory content-directory fresh-output-file");
                fixture="WebUiPresentationExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());
            }
            case "webui-assets" -> {
                if(args.length!=3)throw new IllegalArgumentException("webui-assets expects game-directory fresh-output-directory");
                fixture="WebUiAssetsExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
            }
            case "action-graphs-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("action-graphs-check expects game-directory");fixture="ActionGraphCompatibility";
                for(String source:List.of("ActionGraphCompatibility","FireCatalogExport","SceneStatusExport"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "scene-actions-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("scene-actions-check expects game-directory");fixture="SceneActionCompatibility";
                for(String source:List.of("SceneActionCompatibility","SceneContentExport","SceneItemExport","ScenePlayerStatsExport","SceneStatusExport","SceneMonsterExport","FireCatalogExport"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "item-actions" -> {
                if(args.length!=3)throw new IllegalArgumentException("item-actions expects game-directory fresh-output-file");
                fixture="SceneItemExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                for(String source:List.of("SceneItemExport","SceneContentExport","ScenePlayerStatsExport","SceneStatusExport","SceneMonsterExport","FireCatalogExport"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "gear-stats-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("gear-stats-check expects game-directory");fixture="GearStatsCompatibility";
            }
            case "gear-stats" -> {
                if(args.length!=4)throw new IllegalArgumentException("gear-stats expects game-directory content-directory fresh-output-file");
                fixture="GearStatsExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());
            }
            case "animated-items-check" -> {if(args.length!=2)throw new IllegalArgumentException("animated-items-check expects game-directory");fixture="AnimatedItemsCompatibility";}
            case "dialogue-check" -> {if(args.length!=2)throw new IllegalArgumentException("dialogue-check expects game-directory");fixture="DialogueCompatibility";}
            case "exchange-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("exchange-check expects game-directory");fixture="ExchangeCompatibility";
            }
            case "previews" -> {
                if (args.length != 2) throw new IllegalArgumentException("previews expects only a game directory");
                fixture = "PreviewCompatibility";
                fixtureArgs.add(project.resolve("src/main/resources/starter-models.txt").toString());
                if (!Files.isRegularFile(project.resolve("build/tortodrone.jar")))
                    throw new IOException("Build the server JAR before checking previews: ./gradlew buildServer");
            }
            case "items" -> {
                if (args.length > 3) throw new IllegalArgumentException("items expects game directory [fresh-output-directory]");
                fixture = "ItemCatalogExport";
                fixtureArgs.add(args.length == 3 ? Path.of(args[2]).toAbsolutePath().toString() : project.resolve("build/exports/items").toString());
                if(!Files.isRegularFile(project.resolve("build/tortodrone.jar")))throw new IOException("Build the server JAR before exporting items: ./gradlew buildServer");
            }
            case "gear-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("gear-check expects game-directory");fixture="GearProgressionCompatibility";
            }
            case "gear-progression" -> {
                if(args.length!=3)throw new IllegalArgumentException("gear-progression expects game-directory fresh-output-directory");
                fixture="GearProgressionExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
            }
            case "pets" -> {
                if(args.length!=3)throw new IllegalArgumentException("pets expects game-directory fresh-output-directory");
                fixture="PetCatalogExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                sources.add(project.resolve("scripts/compat/PetCatalogExport.java"));sources.add(project.resolve("scripts/compat/SceneStatusExport.java"));sources.add(project.resolve("scripts/compat/FireCatalogExport.java"));
            }
            case "pet-timings" -> {
                if(args.length!=4)throw new IllegalArgumentException("pet-timings expects game-directory existing-catalogue fresh-output-directory");
                fixture="PetCatalogExport";fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                for(String source:List.of("PetCatalogExport","SceneStatusExport","FireCatalogExport"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "fragmentation-fires", "vortex-fires", "status-bombs", "sealed-fires" -> {
                if(args.length!=4)throw new IllegalArgumentException(args[0]+" expects game-directory existing-catalogue fresh-output-directory");
                fixture="FireCatalogExport";sources.add(project.resolve("scripts/compat/FireCatalogExport.java"));sources.add(project.resolve("scripts/compat/SceneStatusExport.java"));
                fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                if(args[0].equals("vortex-fires"))fixtureArgs.add("--vortex");
                if(args[0].equals("status-bombs"))fixtureArgs.add("--status-bombs");
                if(args[0].equals("sealed-fires"))fixtureArgs.add("--sealed");
            }
            case "gameplay-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("gameplay-check expects game-directory");fixture="GameplayCompatibility";
            }
            case "gameplay-parity-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("gameplay-parity-check expects game-directory");fixture="GameplayParityCompatibility";
                for(String source:List.of("GameplayParityCompatibility","FireCompatibility"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "restoration-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("restoration-check expects game-directory");fixture="RestorationCompatibility";
                for(String source:List.of("RestorationCompatibility","FireCompatibility"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "custom-weapon-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("custom-weapon-check expects game-directory");fixture="CustomWeaponCompatibility";
            }
            case "restoration" -> {
                if(args.length!=3)throw new IllegalArgumentException("restoration expects game-directory fresh-output-file");
                fixture="RestorationExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                for(String source:List.of("RestorationExport","FireCatalogExport","SceneStatusExport"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "fires" -> {
                if(args.length!=3)throw new IllegalArgumentException("fires expects game-directory fresh-output-directory");
                fixture="FireCatalogExport";sources.add(project.resolve("scripts/compat/FireCatalogExport.java"));sources.add(project.resolve("scripts/compat/SceneStatusExport.java")); fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
            }
            case "fire-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("fire-check expects game-directory");fixture="FireCompatibility";
                fixtureArgs.clear();fixtureArgs.add(project.resolve("content/_internal/fires.json").toString());
            }
            case "weapons" -> {
                if(args.length!=3)throw new IllegalArgumentException("weapons expects game-directory fresh-output-directory");
                fixture="WeaponCatalogExport";
                fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
            }
            case "equipment" -> {
                if(args.length!=4)throw new IllegalArgumentException("equipment expects game-directory grant-catalog.json fresh-output-directory");
                fixture="EquipmentCatalogExport";
                fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());
            }
            case "town-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("town-check expects game-directory");fixture="TownCompatibility";
            }
            case "recipes" -> {
                if(args.length!=3)throw new IllegalArgumentException("recipes expects game-directory fresh-output-directory");
                fixture="RecipeCatalogExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                sources.add(project.resolve("scripts/compat/RecipeCatalogExport.java"));
            }
            case "recipes-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("recipes-check expects game-directory");
                fixture="RecipeCatalogExport";fixtureArgs.add("--verify-content");sources.add(project.resolve("scripts/compat/RecipeCatalogExport.java"));
            }
            case "craft-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("craft-check expects game-directory");fixture="CraftCompatibility";
                sources.add(project.resolve("scripts/compat/CraftCompatibility.java"));
            }
            case "training-check" -> {
                if(args.length!=2)throw new IllegalArgumentException("training-check expects game-directory");fixture="TrainingCompatibility";
                fixtureArgs.clear();fixtureArgs.add(project.resolve("content/maps/advanced_training").toString());
                sources.add(project.resolve("scripts/compat/TrainingCompatibility.java"));sources.add(project.resolve("scripts/compat/FireCompatibility.java"));
            }
            case "statuses" -> {
                if(args.length!=3)throw new IllegalArgumentException("statuses expects game-directory fresh-output-file");fixture="StatusRuntimeExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                for(String source:List.of("StatusRuntimeExport","SceneStatusExport","ScenePlayerStatsExport","FireCatalogExport"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "town-services" -> {
                if(args.length!=4)throw new IllegalArgumentException("town-services expects game-directory maps-directory fresh-output-directory");
                fixture="TownServicesExport";fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());
                for(String source:List.of("TownServicesExport","SceneStatusExport","FireCatalogExport"))sources.add(project.resolve("scripts/compat/"+source+".java"));
            }
            case "town" -> {
                if(args.length!=4)throw new IllegalArgumentException("town expects game-directory maps-directory fresh-output-directory");
                fixture="TownSceneExport";fixtureArgs.add(args[2].equals("--accessories")?args[2]:Path.of(args[2]).toAbsolutePath().toString());fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());
                sources.add(project.resolve("scripts/compat/TownSceneExport.java"));
            }
            case "haven" -> {
                if(args.length!=4)throw new IllegalArgumentException("haven expects game-directory captured-binaries-directory fresh-output-directory");
                fixture="HavenSceneExport";
                fixtureArgs.add(Path.of(args[2]).toAbsolutePath().toString());
                fixtureArgs.add(Path.of(args[3]).toAbsolutePath().toString());
                sources.add(project.resolve("scripts/compat/ScenePackExport.java"));
                sources.add(project.resolve("scripts/compat/HavenSceneExport.java"));
            }
            case "scene" -> {
                int count=args.length-(args[args.length-1].equals("--content")?1:0);
                if (count != 6 && count != 8) throw new IllegalArgumentException("scene expects game-directory archived-scene output-directory zone-id zone-name [spawn-x spawn-y] [--content]");
                fixture = "ScenePackExport";
                fixtureArgs.addAll(Arrays.asList(args).subList(2, args.length));
                sources.add(project.resolve("scripts/compat/ScenePackExport.java"));
            }
            default -> throw new IllegalArgumentException("Unknown stock tool");
        }
        if(args[0].equals("scene")||args[0].equals("haven")||args[0].equals("town")){
            sources.add(project.resolve("scripts/compat/SceneContentExport.java"));
            sources.add(project.resolve("scripts/compat/SceneCollisionExport.java"));
            sources.add(project.resolve("scripts/compat/ScenePlayerStatsExport.java"));
            sources.add(project.resolve("scripts/compat/SceneMonsterExport.java"));
            sources.add(project.resolve("scripts/compat/SceneItemExport.java"));
            sources.add(project.resolve("scripts/compat/SceneStatusExport.java"));
            sources.add(project.resolve("scripts/compat/FireCatalogExport.java"));
        }
        if(sources.isEmpty())sources.add(project.resolve("scripts/compat/" + fixture + ".java"));
        Path scratch = Files.createTempDirectory("tortodrone-stock-tool-");
        try {
            String cp = game.resolve("code/projectx-pcode.jar") + File.pathSeparator + game.resolve("code/*")
                + File.pathSeparator + Path.of(System.getProperty("tortodrone.deployment",project.resolve("build").toString())).resolve("tortodrone.jar");
            var compile=new ArrayList<>(List.of(executable("javac"), "--release", "25", "-cp", cp, "-d", scratch.toString()));
            for(Path source:sources)compile.add(source.toString());
            run(compile);
            var command = new ArrayList<>(List.of(executable("java"), "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dno_unpack_resources=true", "-Dtortodrone.deployment="+System.getProperty("tortodrone.deployment",project.resolve("build").toString()),
                "-Duser.home=" + scratch.resolve("home"), "-Djava.util.prefs.userRoot=" + scratch.resolve("prefs"),
                "-Djava.util.prefs.systemRoot=" + scratch.resolve("system-prefs"), "-cp", scratch + File.pathSeparator + cp, fixture));
            command.addAll(fixtureArgs);
            run(command);
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static String executable(String name) { return Path.of(System.getProperty("java.home"), "bin", name).toString(); }
    private static void run(List<String> command) throws Exception {
        Process child = new ProcessBuilder(command).inheritIO().start();
        Thread cleanup = new Thread(() -> stop(child));
        Runtime.getRuntime().addShutdownHook(cleanup);
        try {
            if (!child.waitFor(180, TimeUnit.SECONDS)) throw new IOException("Stock tool timed out");
            if (child.exitValue() != 0) throw new IOException("Stock tool failed with exit " + child.exitValue());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } finally {
            stop(child);
            try { Runtime.getRuntime().removeShutdownHook(cleanup); }
            catch (IllegalStateException shuttingDown) { /* The registered hook is already stopping the child. */ }
        }
    }
    private static void stop(Process child) {
        boolean interrupted = Thread.interrupted();
        try {
            if (!child.isAlive()) return;
            child.destroy();
            try {
                if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly();
            } catch (InterruptedException e) {
                interrupted = true;
                child.destroyForcibly();
            }
            interrupted = awaitExit(child, interrupted);
        } finally {
            if (Thread.interrupted()) interrupted = true;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private static boolean awaitExit(Process child, boolean interrupted) {
        while (child.isAlive()) {
            try { child.waitFor(); }
            catch (InterruptedException e) {
                interrupted = true;
                child.destroyForcibly();
            }
        }
        return interrupted;
    }
}
