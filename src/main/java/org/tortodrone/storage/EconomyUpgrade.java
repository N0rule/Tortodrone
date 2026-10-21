package org.tortodrone.storage;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.ItemCatalog;
import org.tortodrone.resources.*;
import org.tortodrone.town.TownCatalog;
import static org.tortodrone.resources.Yaml.*;

/** Explicit offline 9..15 -> 16 candidate. Source DB, content and keys are never replaced. */
public final class EconomyUpgrade {
    private EconomyUpgrade() { }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: EconomyUpgrade /stopped-deployment /fresh-candidate-data-directory");
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        export(root.resolve("data"),Path.of(args[1]),()->deploymentLegacy(root));
        System.out.println("Exported schema-16 candidate database. Original is unchanged. Back up the entire stopped deployment, review the candidate and remove only the legacy depot section from a COPY of shops.yml. Retain original keys when installing the reviewed database.");
    }
    private static DepotSeed.Data deploymentLegacy(Path root)throws IOException {
        var items=ItemYaml.load(root.resolve("content/items"));
        var data=TownYaml.migrationData(root.resolve("content/town"));
        var rules=TownCatalog.fromData(data);
        Path exported=root.resolve("legacy-depot-import.json");
        if(!data.containsKey("depot")&&Files.exists(exported)){ContentFiles.regular(exported);data.put("depot",map(Json.parseCatalog(Files.readString(exported)),"legacy import").get("depot"));}
        return data.containsKey("depot")?legacy(list(data.get("depot"),"legacy Depot"),items,rules):null;
    }
    public static DepotSeed.Data legacy(List<?> rows,ItemCatalog items,TownCatalog rules)throws IOException {
        var offers=new ArrayList<DepotStore.Offer>();var unsupported=new LinkedHashMap<Integer,String>();int id=0;
        for(Object raw:rows) {
            var r=map(raw,"legacy Depot offer #"+id);fields(r,"legacy Depot offer","item","count","price","energy","limit","featured");
            String config=text(r.get("item"),"item");var item=items.find(config);if(item==null)throw new IOException("Missing legacy Depot item #"+id+": "+config);
            int source=integer(r.get("price"),"price");if(source< -1)throw new IOException("Unknown legacy price sentinel #"+id);
            var good=new TownCatalog.Good(config,integer(r.get("count"),"count"),source<0?rules.price(config,item.rarity(),true):source,integer(r.get("energy"),"energy"),integer(r.get("limit"),"limit"),"",0);
            DepotStore.values(good);
            offers.add(new DepotStore.Offer(id,good,flag(r.get("featured"),"featured"),item.grantable()));
            if(!item.grantable())unsupported.put(id,config+": "+item.unavailableReason());id++;
        }
        return new DepotSeed.Data(0,"Explicit import of the deployment's effective YAML Depot",offers,unsupported);
    }
    public static Database export(Path sourceData,Path candidate,DepotSeed.Data legacy)throws IOException {return export(sourceData,candidate,()->legacy);}
    @FunctionalInterface private interface LegacySource {DepotSeed.Data load()throws IOException;}
    private static Database export(Path sourceData,Path candidate,LegacySource legacySource)throws IOException {
        Path source=sourceData.toAbsolutePath().normalize(),output=candidate.toAbsolutePath().normalize();
        ContentFiles.safe(source);ContentFiles.safe(output);ContentFiles.regular(source.resolve("tortodrone.db"));
        if(output.startsWith(source)||source.startsWith(output)||Files.exists(output,LinkOption.NOFOLLOW_LINKS))throw new IOException("Candidate directory must be new and separate from source data");
        try{Class.forName("org.sqlite.JDBC");}catch(ClassNotFoundException missing){throw new IOException(missing);}
        Files.createDirectories(output.getParent());
        try(var channel=FileChannel.open(source.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
            if(lock==null)throw new IOException("Stop the server before exporting an economy upgrade");
            Path staging=Files.createTempDirectory(output.getParent(),".economy-upgrade-");
            try {
                DepotSeed.Data legacy;
                try(var c=DriverManager.getConnection("jdbc:sqlite:file:"+source.resolve("tortodrone.db")+"?mode=ro");var s=c.createStatement()) {
                    int version;try(var r=s.executeQuery("PRAGMA user_version")){r.next();version=r.getInt(1);}
                    if(version<9||version>15)throw new IOException("EconomyUpgrade accepts only schemas 9–15; found "+version);
                    long revision=0;if(version>=11)try(var r=s.executeQuery("SELECT value FROM metadata WHERE key='supply-depot-revision'")){if(r.next())revision=r.getLong(1);}
                    legacy=revision==0?legacySource.load():null;
                    if(revision==0&&legacy==null)throw new IOException("YAML-backed Depot requires the original shops.yml Depot section; refusing to substitute stock defaults");
                    try(var vacuum=c.prepareStatement("VACUUM INTO ?")){vacuum.setString(1,staging.resolve("tortodrone.db").toString());vacuum.execute();}
                }
                if(Files.getFileStore(staging).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(staging.resolve("tortodrone.db"),java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
                var upgraded=new Database(staging,true,true,legacy);
                upgraded.read(c->{try(var s=c.createStatement();var r=s.executeQuery("PRAGMA integrity_check")){if(!r.next()||!r.getString(1).equals("ok"))throw new IOException("Candidate integrity check failed");}return null;});
                Files.writeString(staging.resolve("economy-upgrade.txt"),"Source schema 9–15 exported without replacement; candidate schema 16. Purchase ledger, managed offers/revision, escrow, mail and player records retained. Keys are intentionally not copied.\n"+(legacy==null?"Managed SQLite Depot retained.\n":legacy.provenance()+"\n"+legacy.unsupported()+"\n"));
                Files.move(staging,output,StandardCopyOption.ATOMIC_MOVE);
            } finally {if(Files.exists(staging))try(var paths=Files.walk(staging)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        }catch(OverlappingFileLockException held){throw new IOException("Stop the server before exporting an economy upgrade",held);}catch(SQLException failure){throw new IOException("Economy upgrade failed; original retained",failure);}
        return new Database(output,false);
    }
}
