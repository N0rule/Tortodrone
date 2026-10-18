package org.tortodrone.support;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import org.tortodrone.storage.*;

/** Only disposable test databases may use this in-place installation of a reviewed candidate. */
public final class SchemaFixture {
    private SchemaFixture() { }
    public static void depotBefore15(Connection c)throws SQLException {
        try(var s=c.createStatement()) {
            try(var r=s.executeQuery("SELECT 1 FROM pragma_table_info('town_auctions') WHERE name='available_at'")){if(r.next()){try(var alter=c.createStatement()){alter.execute("ALTER TABLE town_auctions DROP COLUMN available_at");}}}
            s.execute("DROP TABLE town_depot_promotions");
            s.execute("ALTER TABLE town_depot_offers DROP COLUMN promotion_only");
            s.execute("DELETE FROM metadata WHERE key IN ('supply-depot-initialized','supply-depot-seed-version')");
        }
    }
    public static Database upgrade(Path data)throws IOException {
        Path root=data.toAbsolutePath().normalize();
        if(!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath())||!root.toString().contains("tortodrone-"))throw new IOException("Disposable test path required");
        Path candidate=root.resolveSibling(root.getFileName()+"-upgrade");
        EconomyUpgrade.export(root,candidate,DepotSeed.bundled());
        try {
            Files.move(candidate.resolve("tortodrone.db"),root.resolve("tortodrone.db"),StandardCopyOption.REPLACE_EXISTING);
            return new Database(root,false);
        }finally{ContentFixture.delete(candidate);}
    }
}
