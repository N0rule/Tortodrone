# Guildhall (reserved)

A guild hall scene is reserved. The folder is intentionally README-only.

Guild identity, membership, ranks, invitations, the record page, the energy well and the
expansion/theme settings are already implemented in `modules/gameplay` and served by
`GuildHandler`; what is missing is the hall scene itself.

Hall scenes are not authored before the stock hall scene ids, the hall-editor request
layout and the guild storage/shop payloads are verified, so `GuildObject` publishes
`hallSceneId` and `designSceneId` as zero and an empty guild attribute set.

The matching `package-info.java` records the contract and the evidence still needed.
