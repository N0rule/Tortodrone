package org.tortodrone.storage;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import org.tortodrone.character.CharacterStore;
import org.tortodrone.core.character.WorldProfile;

/** Knight rename window and ticket consumption. The rename ticket and globally unique
 *  identity commit in the same authenticated write as the rest of a town mutation. */
final class RenameStore {
    private final Database database;
    private final org.tortodrone.core.character.ItemCatalog items;
    RenameStore(Database database,org.tortodrone.core.character.ItemCatalog items){this.database=database;this.items=items;}

    /** Stock canRename: the window only opens inside the thirty-day rename interval. */
    void canRename(int account,int knight,long auth)throws IOException {
        database.read(c->{checkRenamable(c,account,knight,auth);return null;});
    }

    private static void checkRenamable(Connection c,int account,int knight,long auth)throws SQLException,IOException {
        WorldProfiles.load(c,account,knight,auth);
        long last;try(var s=c.prepareStatement("SELECT last_renamed FROM knights WHERE account_id=? AND id=?")){s.setInt(1,account);s.setInt(2,knight);try(var r=s.executeQuery()){if(!r.next())throw new IOException("m.internal_error");last=r.getLong(1);}}
        if(last>0&&System.currentTimeMillis()-last<TownStore.RENAME_INTERVAL)throw new IOException("m.rename_too_soon");
    }

    TownStore.Result rename(int account,int knight,long auth,String name)throws IOException {
        String error=CharacterStore.nameError(name);if(error!=null)throw new IOException(error);
        return TownMutations.change(database,items,account,knight,auth,(c,p)->{
            checkRenamable(c,account,knight,auth);
            var ticket=p.items().stream().filter(i->i.config().equals("Upgrade/Rename Knight")).findFirst().orElseThrow(()->new IOException("m.no_rename_item"));
            try(var st=c.prepareStatement("UPDATE knights SET name=?,name_key=?,last_renamed=? WHERE account_id=? AND id=? AND name_key<>?")){
                st.setString(1,name);st.setString(2,name.toLowerCase(Locale.ROOT));st.setLong(3,System.currentTimeMillis());st.setInt(4,account);st.setInt(5,knight);st.setString(6,name.toLowerCase(Locale.ROOT));
                if(st.executeUpdate()!=1)throw new IOException("Choose a different knight name");
            }
            TownLedger.consume(c,p,ticket.id(),1);
            try(var st=c.prepareStatement("UPDATE accounts SET revision=revision+1 WHERE id=?")){st.setInt(1,account);st.executeUpdate();}
            return name;
        });
    }
}
