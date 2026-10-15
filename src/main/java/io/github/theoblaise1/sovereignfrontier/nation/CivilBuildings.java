package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Enregistrement des bâtiments civils (Registre civil) et revalidation tournante :
 * un seul bâtiment est revérifié par intervalle (compteur de ticks persistant), et seulement si
 * toute sa zone est chargée ; sinon il est conservé tel quel et retenté au tour suivant.
 * Les règles d'unicité (une pièce = un registre, fermes espacées) s'appliquent aux deux étapes.
 */
public final class CivilBuildings {
    private CivilBuildings() {
    }

    public static ActionResult register(ServerPlayer player, ServerLevel level, BlockPos pos) {
        MinecraftServer server = player.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(player.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (data.ownerAt(level.dimension(), pos) != nation) {
            return ActionResult.fail(Msg.err("building.error.not_territory"));
        }
        if (!nation.hasPermission(player.getUUID(), NationPermission.BUILD)) {
            return ActionResult.fail(Msg.err("nation.error.no_permission", NationPermission.BUILD.displayName()));
        }
        GlobalPos key = GlobalPos.of(level.dimension(), pos);
        BuildingValidator.Validation v = validate(data, nation, level, pos);
        if (v.unloaded()) {
            // Résultat indéterminé : un bâtiment déjà enregistré ici est conservé.
            return ActionResult.fail(v.message());
        }
        BuildingType existing = nation.buildings().get(key);
        Component problem = v.valid() ? conflict(data, nation, key, v) : v.message();

        if (problem != null) {
            if (existing != null) {
                nation.removeBuilding(key);
                nation.log("building_lost", "@building." + existing.id(), pos.toShortString());
                NationSync.refreshNation(server, nation);
            }
            return ActionResult.fail(problem);
        }
        if (existing == v.type()) {
            return ActionResult.ok(Msg.info("building.already_registered", v.type().displayName()).append(" ").append(v.message()));
        }
        nation.addBuilding(key, v.type());
        nation.log("building_add", player.getGameProfile().getName(), "@building." + v.type().id(), pos.toShortString());
        NationSync.refreshNation(server, nation);
        return ActionResult.ok(v.message().copy().append(" ").append(Msg.ok("building.registered", nation.effectivePopulation())));
    }

    /** Le registre a été détruit (joueur, explosion, piston…) : le bâtiment est retiré. */
    public static void onRegistryRemoved(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        NationData data = NationData.get(server);
        Nation owner = data.ownerAt(level.dimension(), pos);
        if (owner == null) {
            return;
        }
        BuildingType removed = owner.removeBuilding(GlobalPos.of(level.dimension(), pos));
        if (removed != null) {
            owner.log("building_lost", "@building." + removed.id(), pos.toShortString());
            NationManager.broadcast(server, owner, Msg.warn("building.lost", removed.displayName(), pos.toShortString()));
            NationSync.refreshNation(server, owner);
        }
    }

    /** Revalide un seul bâtiment par intervalle, à tour de rôle sur toutes les nations. */
    public static void tickRevalidation(MinecraftServer server) {
        NationData data = NationData.get(server);
        if (!data.advanceBuildingCounter(SFConfig.BUILDING_RECHECK_TICKS.get())) {
            return;
        }
        int total = 0;
        for (Nation n : data.nations()) {
            total += n.population();
        }
        if (total == 0) {
            return;
        }
        int index = Math.floorMod(data.nextBuildingCursor(), total);
        for (Nation nation : data.nationsSnapshot()) {
            if (index >= nation.population()) {
                index -= nation.population();
                continue;
            }
            Map.Entry<GlobalPos, BuildingType> entry = nation.buildings().entrySet().stream().skip(index).findFirst().orElse(null);
            if (entry != null) {
                revalidate(server, nation, entry.getKey());
            }
            return;
        }
    }

    /**
     * Revalide un bâtiment enregistré : retiré s'il n'est plus valide ou s'il enfreint une règle
     * d'unicité, conservé tel quel si sa zone n'est pas entièrement chargée.
     */
    public static void revalidate(MinecraftServer server, Nation nation, GlobalPos key) {
        BuildingType current = nation.buildings().get(key);
        ServerLevel level = server.getLevel(key.dimension());
        BlockPos pos = key.pos();
        if (current == null || level == null || !level.isLoaded(pos)) {
            return;
        }
        NationData data = NationData.get(server);
        BuildingValidator.Validation v = null;
        boolean lost = !level.getBlockState(pos).is(ModBlocks.CIVIL_REGISTRY.get());
        if (!lost) {
            v = validate(data, nation, level, pos);
            if (v.unloaded()) {
                return;
            }
            lost = !v.valid() || conflict(data, nation, key, v) != null;
        }
        if (lost) {
            nation.removeBuilding(key);
            nation.log("building_lost", "@building." + current.id(), pos.toShortString());
            NationManager.broadcast(server, nation, Msg.warn("building.lost", current.displayName(), pos.toShortString()));
            NationSync.refreshNation(server, nation);
        } else if (v.type() != current) {
            nation.addBuilding(key, v.type());
            NationSync.refreshNation(server, nation);
        }
    }

    /** Validation avec, pour une ferme, la seule terre labourée du territoire de la nation (cache par chunk). */
    private static BuildingValidator.Validation validate(NationData data, Nation nation, ServerLevel level, BlockPos pos) {
        ResourceKey<Level> dim = level.dimension();
        return BuildingValidator.validate(level, pos, SFConfig.MAX_BUILDING_VOLUME.get(), SFConfig.FARM_MIN_FARMLAND.get(),
                p -> data.ownerAt(dim, p) == nation);
    }

    /**
     * Règles d'unicité d'un bâtiment valide, communes à l'enregistrement et à la revalidation (un
     * registre caché puis découvert, ou deux pièces réunies, est ainsi rattrapé). Pièce : aucun autre
     * registre enregistré comme maison ou atelier, quelle que soit sa nation, ne borde son intérieur
     * (parcours de la bordure en O(bordure), propriétaire lu dans le cache par chunk). Ferme : aucune
     * autre ferme de la nation dont la zone de comptage chevauche la sienne.
     */
    @Nullable
    private static Component conflict(NationData data, Nation nation, GlobalPos key, BuildingValidator.Validation v) {
        ResourceKey<Level> dim = key.dimension();
        BlockPos pos = key.pos();
        if (v.type().isRoom()) {
            for (BlockPos p : v.boundary()) {
                if (p.equals(pos)) {
                    continue;
                }
                Nation owner = data.ownerAt(dim, p);
                BuildingType other = owner == null ? null : owner.buildings().get(GlobalPos.of(dim, p));
                if (other != null && other.isRoom()) {
                    return Msg.err("building.error.duplicate_room");
                }
            }
            return null;
        }
        for (Map.Entry<GlobalPos, BuildingType> e : nation.buildings().entrySet()) {
            GlobalPos other = e.getKey();
            if (e.getValue() == BuildingType.FARM && !other.equals(key) && other.dimension().equals(dim)
                    && BuildingValidator.farmAreasOverlap(other.pos(), pos)) {
                return Msg.err("building.error.farm_too_close");
            }
        }
        return null;
    }
}
