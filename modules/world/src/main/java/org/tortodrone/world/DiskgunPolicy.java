package org.tortodrone.world;

import java.util.Map;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.world.KnightCombat;

/** Custom forward-only Diskgun compatibility policy.
 *
 * <p>The shipped Fiery/Static Diskguns carry the old Mixer carrier/orbital prototype. Their
 * item data stays untouched; only the resolved shot graph and pierce budget are adapted
 * here, keeping that reconstruction out of the generic fire-action interpreter. */
final class DiskgunPolicy {
    private DiskgunPolicy() { }

    /** Normal shots pass through one enemy and expire on the next; charged shots pass
     *  through every enemy until their fuse. */
    record PiercePolicy(int extraPierces,boolean singleProjectile,boolean forwardOnly) {
        static final PiercePolicy NONE=new PiercePolicy(0,false,false);
        static final PiercePolicy ONE=new PiercePolicy(1,true,true);
        static final PiercePolicy ALL=new PiercePolicy(Integer.MAX_VALUE,true,true);
        boolean pierces(){return extraPierces>0;}
    }

    private static final Map<String,String> FORWARD_DISKGUNS=Map.of(
        "Weapon/Handgun/Fiery Diskgun","Weapon/Handgun/Celestial Orbitgun",
        "Weapon/Handgun/Static Diskgun","Weapon/Handgun/Overcharged Mixmaster");

    /** The resolved fire graph for an unused Diskgun, or the stock config unchanged. */
    static String resolvedFire(String config){return FORWARD_DISKGUNS.getOrDefault(config,config);}

    static PiercePolicy policy(String config,int activity){
        if(!FORWARD_DISKGUNS.containsKey(config))return PiercePolicy.NONE;
        return activity==KnightCombat.CHARGED?PiercePolicy.ALL:PiercePolicy.ONE;
    }

    /** Keep the native orbital disk moving outward for its whole life instead of entering
     *  the return/home phase authored at {@code outDuration}. */
    static FireCatalog.ActorSpec forwardOnly(FireCatalog.ActorSpec spec){
        var o=spec.orbit();int out=Math.max(o.outDuration(),Math.max(spec.fuse(),o.lifespan()));
        var orbit=new FireCatalog.OrbitSpec(o.radius(),o.angularSpeed(),o.startingRotation(),o.flightSpeed(),out,o.lifespan(),o.follow(),o.alternateSpin(),o.secondOrbitRadius(),o.secondSpeed(),o.secondStartingRotation());
        return new FireCatalog.ActorSpec(spec.nativeType(),spec.config(),spec.fuse(),spec.speed(),spec.maxStep(),spec.flags(),spec.offsetX(),spec.offsetY(),spec.height(),spec.detonation(),orbit,spec.collisionMask(),spec.collisionShape(),spec.timers(),spec.shutdown(),spec.startup(),spec.attachment(),spec.activation(),spec.activatesAttachments());
    }
}
