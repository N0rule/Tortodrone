package org.tortodrone.world;

import java.util.Map;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.InputBits;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.SceneClock;
import org.tortodrone.core.world.SceneContent;
import org.tortodrone.core.world.ScenePetState;

    /** Battle-sprite state owned by one member: skill cooldowns, shield and follow pose. */
final class PetCompanion {
    final SceneMembership member;
    final SceneSimulation simulation;
    final int id;
    final int[] nextSkill=new int[3];
    int skill,knightSkill,started,rearm,clear,lastInput,shield,shieldMaximum;
    String shieldPower;
    SceneContent.Spec shieldDefense=new SceneContent.Spec(Map.of());
    float x,y,rotation;
    boolean follow;

    PetCompanion(SceneMembership member,SceneSimulation simulation) {
        this.member = member;
        this.simulation = simulation;
        this.id = simulation.nextPetId++;
    }
    ActorState actor(){
        var sprite=member.sprite;
        if(simulation.instance.map().town()||!member.entered||sprite==null)return null;
        var state=new ScenePetState(member.appearance.id(),sprite,member.harness,follow,java.util.Arrays.stream(nextSkill).boxed().toList(),shield,shieldMaximum,shieldPower);
        return new ActorState(id,0,"",0,member.appearance.created(),skill==0||follow?member.movement.x():x,skill==0||follow?member.movement.y():y,skill==0?member.movement.rotation():rotation,0,0,skill,started,new org.tortodrone.core.character.StarterCatalog.Model("Sprite/Base",Map.of()),false,null,null,null,null,null,state);
    }
    void advance(int stamp){
        var sprite=member.sprite;var harness=member.harness;
        if(sprite==null||simulation.instance.map().town())return;
        if(skill!=0&&SceneClock.reached(stamp,clear)){skill=0;started=clear;}
        else if(skill!=0&&SceneClock.reached(stamp,rearm))follow=true;
        int input=(member.movement.inputFlags()&InputBits.SPRITE_SKILL_MASK)>>>InputBits.SPRITE_SKILL_SHIFT;
        if(input==0){lastInput=0;return;}
        if(input==lastInput||skill!=0||!member.health.alive())return;
        lastInput=input;int selected=input-1;
        if(!org.tortodrone.core.town.SpriteRules.unlocked(sprite.level(),selected)||SceneClock.before(stamp,nextSkill[selected]))return;
        var def=simulation.town.sprite(sprite.config());if(def==null||selected>=def.skills().size())return;
        var ability=def.skills().get(selected);String name=ability.attacks().get(sprite.ultimates().get(selected));
        String damage=simulation.town.harnessDamage(harness==null?"Iron Harness":harness.config());if(damage==null)damage="NORMAL";
        var attack=simulation.petFires.find(name+"|"+sprite.powers().get(selected*3+1)+"|"+sprite.powers().get(selected*3+2)+"|"+damage,292);if(attack==null)return;
        int power=sprite.powers().get(selected*3),recharge=(int)(ability.recharge().get(power)*1000*simulation.town.harnessRecharge(harness==null?"Iron Harness":harness.config()));
        if(!member.movement.spriteCommand(stamp))return;
        // Archive live observations write the USED skill's recharge to all three slots.
        recharge=Math.max(recharge,ability.minimum().get(power)*1000);
        java.util.Arrays.fill(nextSkill,stamp+recharge);
        knightSkill=selected;skill=input;started=stamp;clear=stamp+Math.max(1,((Number)attack.attack().arguments().getOrDefault("clear",1000)).intValue());follow=ability.follow();x=member.movement.x();y=member.movement.y();rotation=member.movement.rotation();
        rearm=stamp+((Number)attack.attack().arguments().getOrDefault("rearm",clear-stamp)).intValue();
        int land=((Number)attack.attack().arguments().getOrDefault("land",0)).intValue();
        simulation.weapons.attack(id,new KnightCombat.AttackStart(attack.weaponConfig(),292,stamp,land,clear-stamp),attack);
    }
    /** Sprite swaps cancel the skill and shield but preserve recharge deadlines. */
    void changeSprite(){simulation.weapons.cancel(id);skill=shield=shieldMaximum=0;shieldPower=null;follow=false;}
    void cancel(){simulation.weapons.cancel(id);}
    void startShield(int health,String power){shield=shieldMaximum=health;shieldPower=power;}
    void shieldDefense(SceneContent.Spec defense){shieldDefense=defense;}
    void endShield(){shield=shieldMaximum=0;shieldPower=null;}
}
