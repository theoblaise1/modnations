package io.github.theoblaise1.sovereignfrontier.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Partie « recherche » du {@link NationSnapshot}.
 *
 * @param completed           paliers terminés par la nation
 * @param active              palier en cours, s'il y en a un
 * @param activeRemainingMs   temps restant au moment de l'envoi (le client décompte à partir de la réception)
 * @param activeTotalMs       durée totale de la recherche en cours
 * @param points              points de recherche de la nation
 * @param effectivePopulation habitants effectifs (bonus inclus), comparés à {@code min_population}
 * @param chestCounts         nombre d'objets « bruts » par identifiant d'objet dans le coffre national
 * @param bonuses             somme des bonus par ordinal de {@code ResearchStat}
 */
public record ResearchView(
        List<ResourceLocation> completed,
        Optional<ResourceLocation> active,
        long activeRemainingMs,
        long activeTotalMs,
        long points,
        int effectivePopulation,
        Map<ResourceLocation, Integer> chestCounts,
        double[] bonuses) {

    public static final ResearchView EMPTY = new ResearchView(List.of(), Optional.empty(), 0L, 0L, 0L, 0, Map.of(),
            new double[0]);

    public void write(FriendlyByteBuf buf) {
        buf.writeCollection(completed, FriendlyByteBuf::writeResourceLocation);
        buf.writeOptional(active, FriendlyByteBuf::writeResourceLocation);
        buf.writeVarLong(activeRemainingMs);
        buf.writeVarLong(activeTotalMs);
        buf.writeVarLong(points);
        buf.writeVarInt(effectivePopulation);
        buf.writeMap(chestCounts, FriendlyByteBuf::writeResourceLocation, FriendlyByteBuf::writeVarInt);
        buf.writeVarInt(bonuses.length);
        for (double b : bonuses) {
            buf.writeDouble(b);
        }
    }

    public static ResearchView read(FriendlyByteBuf buf) {
        List<ResourceLocation> completed = buf.readList(FriendlyByteBuf::readResourceLocation);
        Optional<ResourceLocation> active = buf.readOptional(FriendlyByteBuf::readResourceLocation);
        long remaining = buf.readVarLong();
        long total = buf.readVarLong();
        long points = buf.readVarLong();
        int population = buf.readVarInt();
        Map<ResourceLocation, Integer> counts = buf.readMap(HashMap::new, FriendlyByteBuf::readResourceLocation, FriendlyByteBuf::readVarInt);
        int n = Math.min(buf.readVarInt(), 64);
        double[] bonuses = new double[n];
        for (int i = 0; i < n; i++) {
            bonuses[i] = buf.readDouble();
        }
        return new ResearchView(completed, active, remaining, total, points, population, counts, bonuses);
    }
}
