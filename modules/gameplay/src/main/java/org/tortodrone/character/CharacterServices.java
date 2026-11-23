package org.tortodrone.character;

import java.io.IOException;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.mission.MissionCatalog;
import org.tortodrone.core.town.TownCatalog;
import org.tortodrone.resources.BoxCatalog;
import org.tortodrone.storage.AuctionHouse;
import org.tortodrone.storage.BoxOpeningService;
import org.tortodrone.storage.Database;
import org.tortodrone.storage.DepotStore;
import org.tortodrone.storage.EnergyExchange;
import org.tortodrone.storage.FriendStore;
import org.tortodrone.storage.GuildStore;
import org.tortodrone.storage.OpeningMissionStore;
import org.tortodrone.storage.StyleStore;
import org.tortodrone.storage.TownStore;

/** Storage-facing services owned by one account's character domain. Keeping these
 *  constructors out of {@link CharacterStore} keeps the roster model free of
 *  {@code storage.*} service types; session handlers ask the services facade instead. */
public final class CharacterServices {
    private final Database database;
    private final ItemCatalog catalog;

    CharacterServices(Database database, ItemCatalog catalog) {
        this.database = database;
        this.catalog = catalog;
    }

    private Database require() throws IOException {
        if (database == null) throw new IOException("SQLite storage is required");
        return database;
    }

    public EnergyExchange exchange() throws IOException {
        return new EnergyExchange(require());
    }

    public FriendStore friends() throws IOException {
        return new FriendStore(require());
    }

    public DepotStore depot(TownCatalog rules) throws IOException {
        return new DepotStore(require(), catalog, rules);
    }

    public StyleStore styles() throws IOException {
        return new StyleStore(require(), catalog);
    }

    public AuctionHouse auction(TownCatalog rules) throws IOException {
        return new AuctionHouse(require(), catalog, rules);
    }

    public TownStore town(TownCatalog rules) throws IOException {
        return new TownStore(require(), catalog, rules);
    }

    public GuildStore guilds() throws IOException {
        return new GuildStore(require());
    }

    public BoxOpeningService boxes(BoxCatalog boxes) throws IOException {
        return new BoxOpeningService(require(), catalog, boxes);
    }

    public OpeningMissionStore openingMissions(TownCatalog rules, MissionCatalog missions) throws IOException {
        return new OpeningMissionStore(require(), catalog, rules, missions);
    }
}
