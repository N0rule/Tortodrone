package org.tortodrone.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import org.tortodrone.core.WireTypes;
import org.tortodrone.core.character.ItemCatalog;
import org.tortodrone.core.character.Knight;
import org.tortodrone.core.character.StarterCatalog;
import org.tortodrone.core.character.WorldProfile;
import org.tortodrone.core.town.TownState;
import org.tortodrone.core.world.FireCatalog;

/** Native item-instance, gift and loadout records. Kept out of the shared {@link WireWriter}
 *  base so a non-inventory codec cannot grow a dependency on the item/equipment model; every
 *  codec that needs these records composes one instance over the same {@link WireContext}. */
final class ItemWire extends WireWriter {
    ItemWire(WireContext context, ItemCatalog catalog) { super(context, catalog); }

    void items(DataOutputStream out, Knight knight, WorldProfile profile) throws IOException {
        type(out, WireTypes.DSET);
        out.writeInt(profile.items().size());
        for (var item : profile.items()) item(out, knight, profile.worldId(), item);
    }

    void item(DataOutputStream out, Knight knight, int worldId, WorldProfile.Item item) throws IOException {
        item(out, knight == null ? null : knight.costume(), worldId, item);
    }

    void item(DataOutputStream out, String costume, int worldId, WorldProfile.Item item) throws IOException {
        var definition = itemCatalog.find(item.config());
        boolean seeded = item.slot() == WorldProfile.Slot.OTHER && definition != null && definition.variant() == ItemCatalog.Variant.SEED_ITEM;
        boolean gift = definition != null && definition.type().equals("GiftBox");
        String subtype = gift ? "GiftBoxItem" : item.slot() != WorldProfile.Slot.OTHER ? "LevelItem" : definition == null ? "Item" : switch (definition.variant()) {
            case ITEM -> "Item"; case LEVEL_ITEM -> "LevelItem"; case SEED_ITEM -> "SeedItem"; case UPGRADE_ITEM -> "UpgradingItem"; case FURNITURE_ITEM -> "FurniItem";
        };
        // Inactive UpgradingItem adds no fields to the common Item payload.
        type(out, "com.threerings.projectx.item.data." + subtype);
        if (definition != null && definition.variant() == ItemCatalog.Variant.FURNITURE_ITEM) out.writeInt(0); // native undeployed count
        if (gift) {
            var record = item.gift();
            out.writeBoolean(record != null);
            if (record != null) {
                out.writeInt(record.contents().size());
                for (var content : record.contents()) { value(out, giftReference(content)); out.writeInt(content.count()); }
            }
            string(out, record == null ? null : record.message());
            out.writeInt(record == null ? 0 : record.wrapperId());
            string(out, record == null ? null : record.wrapperName());
        }
        if (seeded) out.writeLong(definition.seed());
        boolean level = item.slot() != WorldProfile.Slot.OTHER || definition != null && definition.variant() == ItemCatalog.Variant.LEVEL_ITEM;
        if (level) {
            out.writeInt(item.level()); out.writeFloat(item.heatProgress()); out.writeFloat(0);
            if (item.variants().isEmpty()) out.writeShort(0);
            else {
                type(out, "[Lcom.threerings.config.ConfigReference;"); out.writeInt(item.variants().size());
                for (var variant : item.variants()) value(out, variant);
            }
            var accessories = item.nativeAccessories(costume);
            out.writeBoolean(!accessories.isEmpty());
            if (!accessories.isEmpty()) {
                out.writeInt(accessories.size());
                for (String accessory : accessories) value(out, new StarterCatalog.Model(accessory, Map.of()));
            }
        }
        // Stock Item.o / LevelItem.a compare this configuration ID, not the
        // config name. A shared zero leaves the previous weapon's sprite and
        // attack animations active after equipping a different weapon.
        out.writeLong(item.id()); out.writeInt(worldId); out.writeInt(definition == null ? 0 : definition.stockId());
        out.writeInt(item.count()); value(out, item.expiresAt());
        // Stock Item.writeObject: bit 2 is the mutable binding flag (aTY),
        // tested by Cs(). Stock cv(true) sets both permanent and binding flags.
        out.writeByte((item.bound() || definition != null && definition.locked() ? 2 : 0) | (item.permanent() ? 1 : 0) | (item.cursed() ? 4 : 0));
    }

    StarterCatalog.Model giftReference(WorldProfile.Item item) {
        var args = new java.util.TreeMap<String, Object>();
        if (item.slot() != WorldProfile.Slot.OTHER) { args.put("_setLevel", item.level()); args.put("_setHeat", item.heatProgress()); }
        if (!item.variants().isEmpty()) args.put("_setVariants", new FireCatalog.ListValue(WireTypes.CONFIG_REFERENCE, new java.util.ArrayList<>(item.variants())));
        var accessories = item.nativeAccessories("");
        if (!accessories.isEmpty()) args.put("_setAccessories", new FireCatalog.ListValue(WireTypes.CONFIG_REFERENCE, accessories.stream().map(a -> (Object) new StarterCatalog.Model(a, Map.of())).toList()));
        if (item.bound()) args.put("_createLocked", true);
        return new StarterCatalog.Model(item.config(), args);
    }

    void currentLoadout(DataOutputStream out, WorldProfile profile) throws IOException {
        type(out, WireTypes.DSET);
        out.writeInt(1 + profile.town().loadouts().size());
        loadout(out, profile);
        for (var saved : profile.town().loadouts().stream().sorted(java.util.Comparator.comparing(l -> l.name().toLowerCase(java.util.Locale.ROOT))).toList()) loadout(out, saved, profile);
    }

    void loadoutUpdated(DataOutputStream out, int oid, WorldProfile profile) throws IOException {
        type(out, WireTypes.ENTRY_UPDATED_EVENT); out.writeInt(oid); string(out, "loadouts");
        loadout(out, profile);
    }

    void loadout(DataOutputStream out, TownState.Loadout saved, WorldProfile profile) throws IOException {
        loadout(out, saved.name(), saved.equipment().stream().mapToLong(Long::longValue).toArray(), saved.sprite(), saved.harness(), saved.perk(), saved.pvp(), loadoutRarity(profile, saved.equipment().stream().mapToLong(Long::longValue).toArray()));
    }

    void loadout(DataOutputStream out, WorldProfile profile) throws IOException {
        var sprite = profile.town().sprites().stream().filter(s -> s.id() == profile.town().equippedSprite()).findFirst().orElse(null);
        loadout(out, "", profile.equipment(), profile.town().equippedSprite(), sprite == null ? 0 : sprite.harness(), sprite == null ? null : sprite.perk(), false, loadoutRarity(profile, profile.equipment()));
    }

    int loadoutRarity(WorldProfile profile, long[] equipment) {
        int rarity = 0;
        for (long id : equipment) if (id != 0) for (var item : profile.items()) if (item.id() == id) { var definition = itemCatalog.find(item.config()); if (definition != null) rarity = Math.max(rarity, definition.rarity()); }
        return rarity;
    }

    void loadout(DataOutputStream out, String name, long[] equipment, int sprite, long harness, Integer perk, boolean pvp, int rarity) throws IOException {
        type(out, "com.threerings.projectx.data.Loadout");
        int[] slots = {0, 1, 2, 3, 4, 15, 6, 7, 8, 9, 10, 11};
        out.writeBoolean(true); out.writeInt(slots.length); for (int slot : slots) out.writeLong(equipment[slot]);
        out.writeBoolean(pvp); out.writeInt(rarity); out.writeInt(sprite); out.writeLong(harness);
        value(out, perk == null ? null : new org.tortodrone.core.wire.EnumValue(WireTypes.SPRITE_PERK, perk)); string(out, name);
    }
}
