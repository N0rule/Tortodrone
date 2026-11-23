package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.core.character.WorldProfile;

/** Guild treasury well, hall state and expansion tickets. */
final class GuildHallStore {
    private final Database database;
    GuildHallStore(Database database){this.database=database;}

    /** {@code GuildMarshaller} id 17. Opening the hall is refused until hall scenes are authored. */
    TownStore.Result setHallOpen(int account,int knight,long auth,long guildId,boolean open)throws IOException{
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            var guild=GuildStore.requireMember(c,GuildStore.guildOf(c,profile.worldId()));
            if(guild.id()!=guildId)throw new IOException("m.access_denied");
            var member=GuildStore.membership(c,guild.id(),profile.worldId());
            if(member==null||member.rank()>1)throw new IOException("m.access_denied");
            try(var s=c.prepareStatement("UPDATE guilds SET hall_open=?,hall_open_at=? WHERE id=?")){
                s.setInt(1,open?1:0);s.setLong(2,open?System.currentTimeMillis():0);s.setLong(3,guild.id());s.executeUpdate();
            }
            GuildStore.log(c,guild.id(),open?32:33,member.name(),System.currentTimeMillis(),null);
            TownLedger.touch(c,profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),GuildStore.view(c,guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 5: deposit crowns into the guild's energy well. */
    TownStore.Result depositEnergy(int account,int knight,long auth,long guildId,int amount)throws IOException{
        if(amount<GuildStore.MIN_DEPOSIT||amount>100_000)throw new IOException("m.internal_error");
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            var guild=GuildStore.requireMember(c,GuildStore.guildOf(c,profile.worldId()));
            if(guild.id()!=guildId)throw new IOException("m.access_denied");
            if(profile.energy()<amount)throw new IOException("m.nsf_energy");
            int total=Math.addExact(guild.energy(),amount);
            try(var s=c.prepareStatement("UPDATE guilds SET energy=? WHERE id=?")){
                s.setInt(1,total);s.setLong(2,guild.id());s.executeUpdate();
            }
            TownLedger.pay(c,profile,0,amount);
            GuildStore.log(c,guild.id(),9,null,System.currentTimeMillis(),List.of("~"+amount));
            TownLedger.touch(c,profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),GuildStore.view(c,guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 22: take {@code min(100, well)} back to the knight. */
    TownStore.Result withdrawEnergy(int account,int knight,long auth,long guildId)throws IOException{
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            var guild=GuildStore.requireMember(c,GuildStore.guildOf(c,profile.worldId()));
            var member=GuildStore.membership(c,guild.id(),profile.worldId());
            if(guild.id()!=guildId||member==null)throw new IOException("m.access_denied");
            long now=System.currentTimeMillis();
            if(member.lastWithdrawal()>0&&now-member.lastWithdrawal()<GuildStore.WITHDRAWAL_INTERVAL)
                throw new IOException("e.already_withdrew");
            int amount=Math.min(GuildStore.WITHDRAWAL_LIMIT,guild.energy());
            if(amount<=0)throw new IOException("e.nsf_energy_in_well");
            try(var s=c.prepareStatement("UPDATE guilds SET energy=energy-? WHERE id=? AND energy>=?")){
                s.setInt(1,amount);s.setLong(2,guild.id());s.setInt(3,amount);
                if(s.executeUpdate()!=1)throw new IOException("e.nsf_energy_in_well");
            }
            try(var s=c.prepareStatement("UPDATE guild_members SET last_withdrawal=? WHERE world_id=?")){
                s.setLong(1,now);s.setInt(2,profile.worldId());s.executeUpdate();
            }
            TownLedger.credit(c,profile.worldId(),0,amount);
            GuildStore.log(c,guild.id(),10,member.name(),now,List.of("~"+amount));
            TownLedger.touch(c,profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),GuildStore.view(c,guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 1. Prices are the shipped expansion tickets. */
    TownStore.Result buyExpansion(int account,int knight,long auth,long guildId,int expansion)throws IOException{
        int price=expansionPrice(expansion);
        if(price<0)throw new IOException("m.internal_error");
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            var guild=GuildStore.requireMember(c,GuildStore.guildOf(c,profile.worldId()));
            if(guild.id()!=guildId)throw new IOException("m.access_denied");
            var member=GuildStore.membership(c,guild.id(),profile.worldId());
            if(member==null||member.rank()>1)throw new IOException("m.access_denied");
            try(var s=c.prepareStatement("SELECT 1 FROM guild_expansions WHERE guild_id=? AND expansion=?")){
                s.setLong(1,guild.id());s.setInt(2,expansion);
                try(var r=s.executeQuery()){if(r.next())throw new IOException("m.internal_error");}
            }
            if(profile.crowns()<price)throw new IOException("m.nsf_crowns");
            TownLedger.pay(c,profile,price,0);
            try(var s=c.prepareStatement("INSERT INTO guild_expansions(guild_id,expansion) VALUES(?,?)")){
                s.setLong(1,guild.id());s.setInt(2,expansion);s.executeUpdate();
            }
            try(var s=c.prepareStatement("UPDATE guilds SET upkeep_cost=upkeep_cost+? WHERE id=?")){
                s.setInt(1,expansionUpkeep(expansion));s.setLong(2,guild.id());s.executeUpdate();
            }
            GuildStore.log(c,guild.id(),21,member.name(),System.currentTimeMillis(),List.of("m.exp."+expansion));
            TownLedger.touch(c,profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),GuildStore.view(c,guild.id()));
        });
    }

    /** {@code GuildMarshaller} id 14; removal refunds nothing, as the archive's own rule. */
    TownStore.Result removeExpansion(int account,int knight,long auth,long guildId,int expansion)throws IOException{
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            var guild=GuildStore.requireMember(c,GuildStore.guildOf(c,profile.worldId()));
            if(guild.id()!=guildId)throw new IOException("m.access_denied");
            var member=GuildStore.membership(c,guild.id(),profile.worldId());
            if(member==null||member.rank()>1)throw new IOException("m.access_denied");
            try(var s=c.prepareStatement("DELETE FROM guild_expansions WHERE guild_id=? AND expansion=?")){
                s.setLong(1,guild.id());s.setInt(2,expansion);
                if(s.executeUpdate()!=1)throw new IOException("m.internal_error");
            }
            try(var s=c.prepareStatement("UPDATE guilds SET upkeep_cost=MAX(0,upkeep_cost-?) WHERE id=?")){
                s.setInt(1,expansionUpkeep(expansion));s.setLong(2,guild.id());s.executeUpdate();
            }
            GuildStore.log(c,guild.id(),22,member.name(),System.currentTimeMillis(),List.of("m.exp."+expansion));
            TownLedger.touch(c,profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),GuildStore.view(c,guild.id()));
        });
    }

    TownStore.Result setHallGlobal(int account,int knight,long auth,long guildId,int type,String ticket)throws IOException{
        return database.write(c->{
            var profile=WorldProfiles.load(c,account,knight,auth);
            var guild=GuildStore.requireMember(c,GuildStore.guildOf(c,profile.worldId()));
            if(guild.id()!=guildId)throw new IOException("m.access_denied");
            var member=GuildStore.membership(c,guild.id(),profile.worldId());
            if(member==null||member.rank()>1)throw new IOException("m.access_denied");
            if(type!=0&&type!=1)throw new IOException("m.internal_error");
            try(var s=c.prepareStatement("UPDATE guilds SET "+(type==0?"theme":"environment")+"=? WHERE id=?")){
                s.setString(1,ticket);s.setLong(2,guild.id());s.executeUpdate();
            }
            GuildStore.log(c,guild.id(),type==0?23:24,member.name(),System.currentTimeMillis(),
                ticket==null?null:List.of(ticket));
            TownLedger.touch(c,profile.worldId());
            return new TownStore.Result(WorldProfiles.load(c,account,knight,auth),GuildStore.view(c,guild.id()));
        });
    }

    /** Expansion ticket prices from {@code Guild/Expansion/*} in the shipped item catalogue. */
    static int expansionPrice(int expansion){
        return switch(expansion){
            case 0,1->15000;            // WEST_1F, EAST_1F
            case 2,3->30000;            // WEST_2F, EAST_2F
            case 4->45000;               // CENTER_2F
            case 5,6->60000;            // WEST_3F, EAST_3F
            case 7,8->75000;            // WEST_4F, EAST_4F
            case 9->90000;              // CENTER_4F
            case 10,11->105000;         // WEST_5F, EAST_5F
            default->-1;
        };
    }

    /** Per-expansion upkeep is not stated by a surviving table; zero keeps the well honest. */
    static int expansionUpkeep(int expansion){return 0;}
}
