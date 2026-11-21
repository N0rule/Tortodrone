package org.tortodrone.protocol;

import static org.tortodrone.protocol.SessionCodec.*;
import static org.tortodrone.protocol.WireWriter.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.SlotUpgrades;
import org.tortodrone.core.world.KnightCombat;
import org.tortodrone.core.world.PlayerEmotes;
import org.tortodrone.core.world.MovementRules;
import org.tortodrone.core.world.ActorState;
import org.tortodrone.core.world.SceneSources;
import org.tortodrone.core.world.SceneSource;
import org.tortodrone.core.world.SceneInstanceView;
import org.tortodrone.core.world.SceneCatalogView;
import org.tortodrone.core.world.MapScene;
import org.tortodrone.core.world.SceneEffect;
import org.tortodrone.core.world.SceneCombatEvent;
import org.tortodrone.core.world.FireCatalog;
import org.tortodrone.core.party.PartyView;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.KnightProgress;
import org.tortodrone.core.character.KnightObjective;
import org.tortodrone.core.character.OpeningCompletion;
import org.tortodrone.core.character.OpeningMission;
import org.tortodrone.core.character.KnightMemory;
import org.tortodrone.core.mission.MissionCatalog;
import org.tortodrone.core.mission.MissionGates;
import org.tortodrone.core.world.SceneConversation;
import org.tortodrone.core.town.TownState;
import org.tortodrone.core.town.AuctionState;
import org.tortodrone.core.town.ExchangeState;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.core.world.EquipmentCatalog;
import org.tortodrone.core.world.InteractionState;
import org.tortodrone.core.world.ProjectileState;
import org.tortodrone.core.world.SceneChat;
import org.tortodrone.core.world.SceneHealth;
import org.tortodrone.core.world.SceneLiftState;
import org.tortodrone.core.world.SceneStatus;
import org.tortodrone.core.world.ReadyRoom;
import org.tortodrone.core.mission.OpeningGate;
import org.tortodrone.core.mission.TutorialRoute;
import org.tortodrone.core.party.PartyView;

/** Mission-owned downstream frames; shares the connection symbol tables. */
public final class MissionCodec extends WireWriter {
    public MissionCodec(WireContext context, ItemCatalog catalog){super(context,catalog);}
    public byte[] completedMissions(int oid,java.util.Collection<org.tortodrone.core.character.OpeningCompletion> completions)throws IOException {
        if(completions.size()>4096)throw new IOException("Too many mission receipts");
        return frame(out->{eventHeader(out,"AttributeChangedEvent",oid);string(out,"completedMissions");type(out,WireTypes.DSET);out.writeInt(completions.size());for(var receipt:completions.stream().sorted(java.util.Comparator.comparing(org.tortodrone.core.character.OpeningCompletion::missionId)).toList())missionCompletion(out,receipt);});
    }

    private void missionCompletion(DataOutputStream out,org.tortodrone.core.character.OpeningCompletion receipt)throws IOException {
        type(out,"com.threerings.projectx.mission.data.MissionCompletion");string(out,receipt.missionId());out.writeLong(receipt.completed());
        if(receipt.difficulty()==null)value(out,null);else{type(out,WireTypes.DIFFICULTY);out.writeByte(java.util.List.of("EASY","MEDIUM","HARD").indexOf(receipt.difficulty()));}
    }

    public byte[] missionSettlement(int oid,WorldProfile before,WorldProfile after,org.tortodrone.core.character.OpeningCompletion receipt,
            java.util.List<org.tortodrone.core.mission.MissionCatalog.Grant> rewards,int prestige,boolean immediate,long created)throws IOException {
        int total=rewards.stream().mapToInt(org.tortodrone.core.mission.MissionCatalog.Grant::count).sum();
        return frame(out->{eventHeader(out,"CompoundEvent",oid);type(out,WireTypes.ARRAY_LIST);out.writeInt(6);
            rank(out,oid,after);attribute(out,oid,"prestige",o->value(o,after.progress().prestige()));attribute(out,oid,"progressFlags",o->value(o,after.progress().progressFlags()));
            partyEntry(out,oid,"completedMissions","EntryAddedEvent");missionCompletion(out,receipt);
            attribute(out,oid,"sceneDialogCreated",o->value(o,created));
            attribute(out,oid,"sceneDialog",o->{
                type(o,"com.threerings.projectx.mission.data.MissionCompleteDialogInfo");
                o.writeShort(0);o.writeShort(0);o.writeFloat(0);o.writeShort(0);o.writeShort(0);
                string(o,receipt.missionId());o.writeBoolean(true);o.writeInt(total);
                for(var reward:rewards)for(int i=0;i<reward.count();i++)value(o,grantModel(reward));
                o.writeInt(prestige);o.writeBoolean(immediate);
                type(o,"com.threerings.projectx.mission.data.RankPair");o.writeInt(before.progress().rank());o.writeInt(before.progress().subrank());o.writeBoolean(true);
                string(o,"ui/icon/difficulty/"+(receipt.difficulty()==null?"hard":receipt.difficulty().toLowerCase(java.util.Locale.ROOT))+".png");
            });
        });
    }

}
