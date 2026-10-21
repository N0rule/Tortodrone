package org.tortodrone.storage;

import java.sql.*;

/** The sole supported in-place upgrade: existing schema-9 auction escrow is copied intact. */
final class AuctionSchema {
    private AuctionSchema() { }
    static void create(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.execute("CREATE TABLE town_auctions(id INTEGER PRIMARY KEY AUTOINCREMENT,seller INTEGER REFERENCES world_characters(world_id),item TEXT NOT NULL,start_bid INTEGER NOT NULL,buy_price INTEGER NOT NULL,bid INTEGER NOT NULL,bidder INTEGER REFERENCES world_characters(world_id),expires INTEGER NOT NULL,ended INTEGER NOT NULL DEFAULT 0,cancelled INTEGER NOT NULL DEFAULT 0,fee INTEGER NOT NULL DEFAULT 0,created INTEGER NOT NULL DEFAULT 0,featured_total INTEGER NOT NULL DEFAULT 0 CHECK(featured_total BETWEEN 0 AND 32767),featured_remaining INTEGER NOT NULL DEFAULT 0 CHECK(featured_remaining BETWEEN 0 AND featured_total),CHECK((seller IS NULL AND featured_total>0) OR (seller IS NOT NULL AND featured_total=0)))");
            s.execute("CREATE INDEX town_auction_expiry ON town_auctions(ended,expires)");
        }
    }
    static void economyUpgrade(Connection c)throws SQLException {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT 1 FROM pragma_table_info('town_auctions') WHERE name='available_at'")){if(r.next())return;}
        try(var s=c.createStatement()){s.execute("ALTER TABLE town_auctions ADD COLUMN available_at INTEGER NOT NULL DEFAULT 0 CHECK(available_at>=0)");}
    }
    // The caller disables foreign keys before BEGIN, checks them before COMMIT, and restores them.
    static void upgrade(Connection c)throws SQLException {
        try(var s=c.createStatement()){
            s.execute("ALTER TABLE town_auctions RENAME TO town_auctions_v9");
            create(c);
            s.execute("INSERT INTO town_auctions(id,seller,item,start_bid,buy_price,bid,bidder,expires,ended,cancelled,fee) SELECT id,seller,item,start_bid,buy_price,bid,bidder,expires,ended,cancelled,fee FROM town_auctions_v9");
            // Rebuild the referencing table so its FK names the replacement, not the renamed table.
            s.execute("ALTER TABLE town_auction_bids RENAME TO town_auction_bids_v9");
            s.execute("CREATE TABLE town_auction_bids(auction INTEGER NOT NULL REFERENCES town_auctions(id),world_id INTEGER NOT NULL REFERENCES world_characters(world_id),watching INTEGER NOT NULL DEFAULT 0,has_bid INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(auction,world_id))");
            s.execute("INSERT INTO town_auction_bids SELECT * FROM town_auction_bids_v9");
            s.execute("DROP TABLE town_auction_bids_v9");
            s.execute("UPDATE sqlite_sequence SET seq=MAX(seq,COALESCE((SELECT seq FROM sqlite_sequence WHERE name='town_auctions_v9'),0)) WHERE name='town_auctions'");
            s.execute("DROP TABLE town_auctions_v9");
            s.execute("ALTER TABLE town_mail ADD COLUMN auction INTEGER REFERENCES town_auctions(id)");
        }
    }
    /** Schema 16 adds player-to-player mail beside auction settlement and friend requests.
     *  Every step is guarded because a schema-9 fixture may already carry the newer columns. */
    static void mailColumns(Connection c)throws SQLException {
        for(String[] column:new String[][]{
            {"sender","INTEGER"},
            {"energy","INTEGER NOT NULL DEFAULT 0 CHECK(energy BETWEEN 0 AND 2147483647)"},
            {"attachments","TEXT"},
            {"mail_type","INTEGER NOT NULL DEFAULT 3 CHECK(mail_type BETWEEN 0 AND 4)"}}){
            try(var check=c.createStatement();var r=check.executeQuery("SELECT 1 FROM pragma_table_info('town_mail') WHERE name='"+column[0]+"'")){
                if(r.next())continue;
            }
            try(var s=c.createStatement()){s.execute("ALTER TABLE town_mail ADD COLUMN "+column[0]+" "+column[1]);}
        }
        // Existing friend requests predate the stored type column.
        try(var s=c.prepareStatement("UPDATE town_mail SET mail_type=1 WHERE id IN(SELECT CAST(substr(key,16) AS INTEGER) FROM metadata WHERE key LIKE 'social.request.%')")){s.executeUpdate();}
    }
}
