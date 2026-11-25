/** Reserved guild hall.
 *
 * Contract: guild hall scene entry, the hall editor and the guild-hall services that hang
 * off a hall scene. Guild identity, membership, ranks, the record, the energy well and the
 * expansion/theme settings are already implemented in {@code org.tortodrone.storage} and
 * served by {@code org.tortodrone.server.network.GuildHandler}; this package owns only the
 * hall scene itself, which does not exist yet.
 *
 * Evidence still required: the stock hall scenes and their scene ids, the hall-editor
 * request layout, and the guild storage/shop service payloads. {@code GuildObject} still
 * publishes {@code hallSceneId} and {@code designSceneId} as zero and the guild attribute
 * set as empty precisely because those payloads are not captured yet. */
package org.tortodrone.gameplay.guildhall;
