package org.tortodrone.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.account.Passwords;
import org.tortodrone.character.*;
import static org.tortodrone.support.TestWire.check;

/** Unsupported saved schemas are refused, never rewritten, imported or reset. */
public final class DatabaseSchemaTest {
    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("tortodrone-schema-");
        try {
            Path current = home.resolve("current");
            var db = new Database(current, false);
            db.create("Alice", new Passwords.Protection(new byte[16], new byte[32]), true);
            var characters = new CharacterStore(db, 1, "Alice");
            var knight = characters.create(new Knight(0,"Saved-Knight","Gear/Helm/Spiral Sallet (Blue)",
                "Gear/Armor/Spiral Culet (Blue)",null,null,"Blue",1,2,0,0));
            var profile = characters.worldProfile(knight.name(), 1);
            check(profile.items().size()==5 && profile.items().stream().allMatch(WorldProfile.Item::bound)
                && Arrays.stream(profile.equipment()).filter(id->id!=0).count()==5, "direct schema creates exactly one equipped bound starter kit");
            try (var channel=FileChannel.open(current.resolve("server.lock"),StandardOpenOption.WRITE);var lock=channel.lock()) {
                check(new Database(current,false).worldProfile(1,1,1).equals(profile), "current schema can open alongside a live server");
            }
            for (int version : new int[]{0,1,2,3,4,5,6,7,99}) {
                db.write(c->{try(var s=c.createStatement()){s.execute("PRAGMA user_version="+version);}return null;});
                byte[] saved=Files.readAllBytes(db.file());
                for(boolean locked:List.of(false,true)) {
                    try{new Database(current,locked);throw new AssertionError("Unsupported schema accepted");}
                    catch(IOException expected){check(expected.getMessage().contains("Unsupported database schema"),"actionable schema refusal");}
                    check(Arrays.equals(saved,Files.readAllBytes(db.file())) && db.worldProfile(1,1,1).equals(profile)
                        && db.identity("Alice").gameMaster(), "refusal preserves schema, credentials, identities, roles and inventory");
                }
            }
            db.write(c->{try(var s=c.createStatement()){s.execute("PRAGMA user_version="+Database.SCHEMA_VERSION);}return null;});
            check(new Database(current,false).worldProfile(1,1,1).equals(profile), "current schema reopens without regranting or re-equipping");
            Path blocked=home.resolve("blocked");Files.createDirectories(blocked);
            try(var channel=FileChannel.open(blocked.resolve("server.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.lock()) {
                try{new Database(blocked,false);throw new AssertionError("Initialized while locked");}catch(IOException expected){}
                check(!Files.exists(blocked.resolve("tortodrone.db")),"refused initialization creates no database");
            }
            check(new Database(blocked,false).list().isEmpty(),"fresh initialization succeeds after lock release");
            Path properties=home.resolve("properties");Files.createDirectories(properties.resolve("accounts"));
            Path savedProfile=properties.resolve("accounts/1.properties");Files.writeString(savedProfile,"obsolete profile");
            check(new Database(properties,false).list().isEmpty() && Files.readString(savedProfile).equals("obsolete profile"),
                "property files have no reader, importer or automatic development account");
            System.out.println("PASS: direct current schema, initialization lock, live reopen, unsupported-schema refusal without data changes and no property import");
        } finally {try(var paths=Files.walk(home)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
