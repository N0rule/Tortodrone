# Starter equipment and preview verification

> Historical evidence. For current behavior and workflow, read [the research index](README.md)
> and [AGENTS.md](../../AGENTS.md). Past client experiments are not current instructions.

Verified on 2026-10-07 against the locally installed Spiral Knights networking
and configuration classes. This is protocol/reference evidence; no client
implementation or game assets are copied into the server.

## Confirmed wire and configuration behavior

- Knight service method 4 carries a `Name`, optional string and Confirm listener.
- The creator offers five helmet types and five armor types in seven costume
  colors: Blue, Gold, Green, Heavy, Pink, Purple and Red (70 item references).
- Its two accessory selectors resolve 56 color-specific references. The field
  called `armorAccessory` in Tortodrone currently corresponds to the creator’s
  second selector; the installed configurations attach those models to the
  helmet back. Preserve the transmitted choice instead of assuming an armor slot.
- Preview `ConfigReference` / `ArgumentMap` values are nested. Recolor arguments
  can contain `ColorizationConfig$Normal`, with its integer colorization field.
- Player color uses class 1 plus the saved color byte. Height uses five string
  values. Eye ordinals 0–46 provide face texture references; BLANK, CIRCUIT and
  BRIGHT also override the closed-eye texture.
- The named Proto Sword, Proto Gun and Proto Shield configurations exist. Granting
  them together here is Tortodrone’s development policy; this check does not
  establish official introductory-mission reward/progression behavior.

`src/main/resources/starter-models.txt` contains only configuration identifiers,
model reference names and the parameters required for these creator previews.
It is a bounded compatibility catalog, not an export of game configurations.

## Reproduce the preview check

```sh
scripts/check-client-previews.sh "/path/to/Spiral Knights"
```

Requires the normal server build tools and an existing game installation. The
optional Java fixture runs from temporary state, reads installed classes/resources
without launching or modifying the game, and compares each decoded server model
to the installed model builder’s result. All 126 starter references and all 47
eye choices passed (173 fixtures), including nested recolors, accessories and
closed-eye overrides. Ordinary `make test` has no game dependency.

A separate disposable server check used unchanged installed `Client`, normal
credentials and Knight marshallers. Login, PlayerObject subscription, create/edit,
delete, same-account live roster updates and account isolation passed with the
new preview payloads. This does not claim graphical world entry or gameplay.
