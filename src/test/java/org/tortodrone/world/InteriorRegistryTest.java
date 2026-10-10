package org.tortodrone.world;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.tortodrone.protocol.SessionCodec;
import static org.tortodrone.support.TestWire.check;

/** Regression for private interior identity, capacity, reclamation, and wire OID ranges. */
public final class InteriorRegistryTest {
    public static void main(String[] args)throws Exception {
        Path home=Files.createTempDirectory("tortodrone-interior-registry-");
        try {
            Path maps=home.resolve("maps");copyTree(Path.of("defaults/maps"),maps);
            rewriteKind(maps.resolve("bazaar/map.properties"),"town","training");
            rewriteKind(maps.resolve("arcade/map.properties"),"town","level");
            var catalog=SceneCatalog.load(maps);var registry=new SceneRegistry(catalog);
            int training=catalog.scenes().stream().filter(s->s.kind().equals("training")).findFirst().orElseThrow().id();
            int level=catalog.scenes().stream().filter(s->s.kind().equals("level")).findFirst().orElseThrow().id();

            var sameOwner=registry.destination(training,41);
            check(sameOwner==registry.destination(training,41),"same owner reuses the same private interior instance");
            var otherOwner=registry.destination(training,42);
            check(otherOwner!=sameOwner&&otherOwner.oid()!=sameOwner.oid(),"different owners receive separate interior objects");
            var otherMap=registry.destination(level,41);
            check(otherMap!=sameOwner&&otherMap.oid()!=sameOwner.oid(),"one owner receives separate instances for different interior maps");
            check(sameOwner.oid()>=140&&sameOwner.oid()<=499,"private interior OIDs stay in their reserved range");

            var town=catalog.entry();var sharedTown=registry.destination(town.id(),41);
            registry.release(sharedTown,41);
            check(registry.destination(town.id(),41)==sharedTown,"releasing a town destination leaves its shared static instance intact");
            check(registry.destination(town.id(),42)==registry.instance(town.id(),Math.floorMod(42, town.instances())),
                "town destinations retain the existing world-ID instance selection");

            registry.release(sameOwner,99);
            check(registry.destination(training,41)==sameOwner,"wrong-owner release cannot evict an interior");
            registry.release(sameOwner,41);
            var reallocated=registry.destination(training,41);
            check(reallocated!=sameOwner&&reallocated.oid()>=140&&reallocated.oid()<=499,
                "reallocation creates a fresh interior within the reserved OID pool");
            registry.release(sameOwner,41);
            check(registry.destination(training,41)==reallocated,"stale release cannot remove a replacement instance");

            var full=new SceneRegistry(catalog);var allocated=new ArrayList<SceneRegistry.Instance>();
            for(int owner=1;owner<=360;owner++)allocated.add(full.destination(training,owner));
            check(allocated.stream().map(SceneRegistry.Instance::oid).distinct().count()==360,
                "all 360 simultaneous interior instances have unique OIDs");
            check(allocated.getFirst().oid()==140&&allocated.getLast().oid()==499,
                "the pool includes both documented OID boundaries");
            boolean exhausted=false;
            try{full.destination(training,361);}catch(IOException expected){exhausted=expected.getMessage().contains("Interior capacity is full");}
            check(exhausted,"the 361st live interior fails with a clear capacity error");
            full.release(allocated.getFirst(),1);
            var recovered=full.destination(training,361);
            check(recovered.oid()==allocated.getFirst().oid(),"releasing an interior restores capacity and its OID");
            full.release(allocated.getFirst(),1);
            boolean fullAgain=false;
            try{full.destination(training,362);}catch(IOException expected){fullAgain=expected.getMessage().contains("Interior capacity is full");}
            check(fullAgain,"recovery restores one slot without exceeding capacity");

            int maxStatic=0;
            for(var map:catalog.scenes())for(int i=0;i<map.instances();i++) {
                var place=registry.instance(map.id(),i);maxStatic=Math.max(maxStatic,place.oid());
                check(place.oid()<140,"static place OID is disjoint from private interiors");
            }
            int party=SessionCodec.interiorPartyOid(reallocated);
            int player=1002; // ClientSession derives an even player OID as 1000 + 2 * connection ID.
            int trade=1001;  // PlayerTrades starts its odd OID sequence at 1001.
            check(maxStatic<140&&reallocated.oid()<500&&party>=640&&party<=999,
                "static places, interiors, and interior party objects occupy disjoint ranges");
            check(player>=1000&&(player&1)==0&&trade>=1001&&(trade&1)==1
                    &&party<player&&party<trade,"interior party OIDs remain disjoint from player and trade objects");
            System.out.println("PASS: private interior ownership, town sharing, stale-release protection, capacity recovery, and OID ranges");
        } finally {deleteTree(home);}
    }

    private static void rewriteKind(Path file,String oldKind,String newKind)throws IOException {
        String properties=Files.readString(file);
        String changed=properties.replace("kind="+oldKind,"kind="+newKind);
        if(changed.equals(properties))throw new IOException("Expected kind property in "+file);
        Files.writeString(file,changed);
    }
    private static void copyTree(Path source,Path target)throws IOException {
        try(var paths=Files.walk(source)) {
            for(Path path:paths.toList()) {
                Path destination=target.resolve(source.relativize(path));
                if(Files.isDirectory(path))Files.createDirectories(destination);
                else Files.copy(path,destination);
            }
        }
    }
    private static void deleteTree(Path root)throws IOException {
        if(!Files.exists(root))return;
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
}
