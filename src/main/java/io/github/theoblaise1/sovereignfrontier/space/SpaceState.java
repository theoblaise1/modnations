package io.github.theoblaise1.sovereignfrontier.space;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * État spatial d'un joueur : attachement {@code sovereignfrontier:space}, persistant et copié à la mort
 * (docs/conception/J5.md §2.2). Structure du socle (WP0) ; la fusée (WP2) et l'environnement (WP3) l'écrivent, l'accès
 * aux dimensions (WP1) le lit. Les horodatages sont en epoch ms (ils survivent aux redémarrages). Les valeurs absentes
 * du NBT valent 0 ou rien.
 *
 * @param oxygen         secondes d'oxygène restantes de la bouteille entamée
 * @param cold           secondes d'exposition au froid
 * @param heat           charge restante du Plastron chauffant (secondes)
 * @param graceUntil     fin de l'acclimatation (epoch ms)
 * @param lastLoginGrace dernière acclimatation accordée à la connexion (epoch ms)
 * @param nextFlight     premier instant d'un nouveau vol en fusée (epoch ms)
 * @param returnPoint    dernier point de départ terrestre (retour et secours)
 * @param lastPlanet     dimension de la dernière planète atteinte (secours si elle disparaît du serveur)
 */
public record SpaceState(int oxygen, int cold, int heat, long graceUntil, long lastLoginGrace, long nextFlight,
                         Optional<GlobalPos> returnPoint, Optional<ResourceLocation> lastPlanet) {
    public static final SpaceState EMPTY = new SpaceState(0, 0, 0, 0L, 0L, 0L, Optional.empty(), Optional.empty());

    public static final Codec<SpaceState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("oxygen", 0).forGetter(SpaceState::oxygen),
            Codec.INT.optionalFieldOf("cold", 0).forGetter(SpaceState::cold),
            Codec.INT.optionalFieldOf("heat", 0).forGetter(SpaceState::heat),
            Codec.LONG.optionalFieldOf("grace_until", 0L).forGetter(SpaceState::graceUntil),
            Codec.LONG.optionalFieldOf("last_login_grace", 0L).forGetter(SpaceState::lastLoginGrace),
            Codec.LONG.optionalFieldOf("next_flight", 0L).forGetter(SpaceState::nextFlight),
            GlobalPos.CODEC.optionalFieldOf("return_point").forGetter(SpaceState::returnPoint),
            ResourceLocation.CODEC.optionalFieldOf("last_planet").forGetter(SpaceState::lastPlanet)
    ).apply(i, SpaceState::new));

    public SpaceState {
        oxygen = Math.max(0, oxygen);
        cold = Math.max(0, cold);
        heat = Math.max(0, heat);
        returnPoint = returnPoint == null ? Optional.empty() : returnPoint;
        lastPlanet = lastPlanet == null ? Optional.empty() : lastPlanet;
    }

    public SpaceState withOxygen(int value) {
        return new SpaceState(value, cold, heat, graceUntil, lastLoginGrace, nextFlight, returnPoint, lastPlanet);
    }

    public SpaceState withCold(int value) {
        return new SpaceState(oxygen, value, heat, graceUntil, lastLoginGrace, nextFlight, returnPoint, lastPlanet);
    }

    public SpaceState withHeat(int value) {
        return new SpaceState(oxygen, cold, value, graceUntil, lastLoginGrace, nextFlight, returnPoint, lastPlanet);
    }

    public SpaceState withGraceUntil(long epochMs) {
        return new SpaceState(oxygen, cold, heat, epochMs, lastLoginGrace, nextFlight, returnPoint, lastPlanet);
    }

    public SpaceState withLastLoginGrace(long epochMs) {
        return new SpaceState(oxygen, cold, heat, graceUntil, epochMs, nextFlight, returnPoint, lastPlanet);
    }

    public SpaceState withNextFlight(long epochMs) {
        return new SpaceState(oxygen, cold, heat, graceUntil, lastLoginGrace, epochMs, returnPoint, lastPlanet);
    }

    public SpaceState withReturnPoint(Optional<GlobalPos> pos) {
        return new SpaceState(oxygen, cold, heat, graceUntil, lastLoginGrace, nextFlight, pos, lastPlanet);
    }

    public SpaceState withLastPlanet(Optional<ResourceLocation> dimension) {
        return new SpaceState(oxygen, cold, heat, graceUntil, lastLoginGrace, nextFlight, returnPoint, dimension);
    }

    /** État après une réapparition (§2.2) : oxygène, froid et charge du plastron repartent de 0, le reste est gardé. */
    public SpaceState afterRespawn() {
        return new SpaceState(0, 0, 0, graceUntil, lastLoginGrace, nextFlight, returnPoint, lastPlanet);
    }

    public boolean inGrace(long nowMs) {
        return nowMs < graceUntil;
    }
}
