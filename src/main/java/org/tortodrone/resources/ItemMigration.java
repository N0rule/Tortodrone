package org.tortodrone.resources;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static org.tortodrone.resources.Yaml.*;

/** Offline, candidate-only conversion of effective customized unified item content. Never opens SQLite. */
public final class ItemMigration {
    private ItemMigration() { }
    public static void main(String[] args)throws IOException {
        if(args.length!=2)throw new IllegalArgumentException("Usage: ItemMigration /old-unified-deployment /fresh-candidate");
        migrate(Path.of(args[0]),Path.of(args[1]));System.out.println("Validated candidate; review item-migration.txt. Original content and player state were not changed.");
    }
    public static void migrate(Path source,Path candidate)throws IOException {
        Path root=source.toAbsolutePath().normalize(),target=candidate.toAbsolutePath().normalize();ContentFiles.safe(root);ContentFiles.safe(target);
        if(root.startsWith(target)||target.startsWith(root)||Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("Use a fresh candidate outside the source deployment; nothing replaced");
        Path lockFile=root.resolve("data/server.lock");ContentFiles.safe(lockFile);
        try(var channel=Files.exists(lockFile)?FileChannel.open(lockFile,StandardOpenOption.WRITE):null;var lock=acquire(channel)) {
            var before=ContentManager.inputs(root);Path content=root.resolve("content");boolean readable=Files.exists(content.resolve("_internal/item-bindings.json"));
            int dodge=0;Path combat=root.resolve("config/combat.yml");
            if(Files.exists(combat)) {var c=Yaml.read(combat);fields(c,combat.toString(),"dodgeCooldownMs");dodge=integer(c.get("dodgeCooldownMs"),combat+" → dodgeCooldownMs");}
            var old=readable?ItemYaml.read(content.resolve("items"),dodge):LegacyItemYaml.read(content.resolve("items"),dodge);
            Files.createDirectories(target.getParent());Path staging=Files.createTempDirectory(target.getParent(),".item-migration-");Path prepared=staging.resolve("candidate");
            try {
                copy(content,prepared.resolve("content"));copy(root.resolve("config"),prepared.resolve("config"));
                if(!readable) {
                    Path next=prepared.resolve("content");ContentMigration.delete(next.resolve("items"));
                    for(String file:List.of("item-definitions.json","item-names.json","item-arguments.json"))Files.deleteIfExists(next.resolve("_internal/"+file));
                    var items=ReadableItemExport.write(old,next);ItemReferences.exportTown(next.resolve("town"),items);ItemReferences.exportGear(next,items);
                }
                var changed=ContentManager.load(prepared);equivalent(old,new ItemYaml.Definitions(changed.items(),changed.weapons(),changed.equipment()));
                var oldTown=TownYaml.data(content.resolve("town"),old.items());TownYaml.references(oldTown,old.items());
                var newTown=TownYaml.data(prepared.resolve("content/town"),changed.items());TownYaml.references(newTown,changed.items());
                if(!Json.stringify(oldTown).equals(Json.stringify(newTown)))throw new IOException("Town reference conversion changed normalized definitions; candidate refused");
                if(!before.equals(ContentManager.inputs(root)))throw new IOException("Source changed during migration; candidate refused");
                var ids=ItemIds.assign(old.items());long collisions=ids.values().stream().filter(id->id.matches(".*_s[0-9]+_[0-9a-f]{12}")).count();
                Files.writeString(prepared.resolve("item-migration.txt"),"Effective customized definitions preserved and compared field-for-field.\nItems: "+old.items().entries().size()+"\nWeapons: "+old.weapons().weapons().size()+"\nShields: "+old.weapons().shields().size()+"\nDisambiguated IDs: "+collisions+"\nNative configs, stock IDs, variants, model arguments, equipment membership/modifiers and combat records are identical.\nTown references normalize identically. SQLite, keys, Depot offers, inventory and other runtime state were neither copied nor opened.\nReview the candidate; install content/ and config/ while stopped, retaining data/.\n",StandardOpenOption.CREATE_NEW);
                Files.move(prepared,target,StandardCopyOption.ATOMIC_MOVE);
            } finally {ContentMigration.delete(staging);}
        }
    }
    static void equivalent(ItemYaml.Definitions a,ItemYaml.Definitions b)throws IOException {
        if(!a.items().entries().equals(b.items().entries())||!a.weapons().weapons().equals(b.weapons().weapons())||!a.weapons().shields().equals(b.weapons().shields())||a.weapons().dodgeCooldown()!=b.weapons().dodgeCooldown()||!a.equipment().entries().equals(b.equipment().entries()))throw new IOException("Migration changed normalized item/combat/equipment values; candidate refused");
        for(var e:a.items().entries()) {
            if(!a.items().displayName(e).equals(b.items().displayName(e)))throw new IOException("Migration changed item name: "+e.config());
            if(a.items().hasLabels(e)&&(!a.items().category(e).equals(b.items().category(e))||!a.items().subcategory(e).equals(b.items().subcategory(e))))throw new IOException("Migration changed item category labels: "+e.config());
        }
    }
    private static FileLock acquire(FileChannel channel)throws IOException {
        if(channel==null)return null;try{var lock=channel.tryLock();if(lock!=null)return lock;}catch(OverlappingFileLockException held){ }
        throw new IOException("Source deployment is running; stop it before migration");
    }
    private static void copy(Path source,Path target)throws IOException {
        ContentFiles.safe(source);try(var paths=Files.walk(source)) {for(Path path:paths.sorted().toList()) {ContentFiles.safe(path);Path dest=target.resolve(source.relativize(path));if(Files.isDirectory(path))Files.createDirectories(dest);else {ContentFiles.regular(path);Files.copy(path,dest);}}}
    }
}
