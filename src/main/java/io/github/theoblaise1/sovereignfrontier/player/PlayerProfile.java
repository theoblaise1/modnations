package io.github.theoblaise1.sovereignfrontier.player;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Données persistantes propres au joueur (DataAttachment). Au jalon J1 : date de première
 * connexion (epoch ms) et préférence d'affichage des entrées de territoire.
 * Les jalons suivants y ajouteront homes, cooldowns, série /daily, etc.
 */
public record PlayerProfile(long firstJoinEpochMs, boolean territoryNotifications) {
    public static final PlayerProfile EMPTY = new PlayerProfile(0L, true);

    public static final Codec<PlayerProfile> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.optionalFieldOf("first_join", 0L).forGetter(PlayerProfile::firstJoinEpochMs),
            Codec.BOOL.optionalFieldOf("territory_notifications", true).forGetter(PlayerProfile::territoryNotifications)
    ).apply(i, PlayerProfile::new));

    public PlayerProfile withFirstJoin(long epochMs) {
        return new PlayerProfile(epochMs, territoryNotifications);
    }

    public PlayerProfile withTerritoryNotifications(boolean enabled) {
        return new PlayerProfile(firstJoinEpochMs, enabled);
    }
}
