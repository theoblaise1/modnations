package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;

/**
 * Enregistrement des bâtiments civils (Registre civil) et revalidation tournante :
 * un seul bâtiment est revérifié par intervalle, et seulement si son chunk est chargé.
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
        BuildingValidator.Validation v = BuildingValidator.validate(level, pos,
                SFConfig.MAX_BUILDING_VOLUME.get(), SFConfig.FARM_MIN_FARMLAND.get());
        BuildingType existing = nation.buildings().get(key);

        if (!v.valid()) {
            if (existing != null) {
                nation.removeBuilding(key);
                nation.log("building_lost", "@building." + existing.id(), pos.toShortString());
                NationSync.refreshNation(server, nation);
            }
            return ActionResult.fail(v.message());
        }
        for (Map.Entry<GlobalPos, BuildingType> e : nation.buildings().entrySet()) {
            GlobalPos other = e.getKey();
            if (other.equals(key) || !other.dimension().equals(level.dimension())) {
                continue;
            }
            if (v.boundary().contains(other.pos())) {
                return ActionResult.fail(Msg.err("building.error.duplicate_room"));
            }
            if (v.type() == BuildingType.FARM && e.getValue() == BuildingType.FARM
                    && Math.abs(other.pos().getX() - pos.getX()) <= 9 && Math.abs(other.pos().getZ() - pos.getZ()) <= 9) {
                return ActionResult.fail(Msg.err("building.error.farm_too_close"));
            }
        }
        if (existing == v.type()) {
            return ActionResult.ok(Msg.info("building.already_registered", v.type().displayName()).append(" ").append(v.message()));
        }
        nation.addBuilding(key, v.type());
        nation.log("building_add", player.getGameProfile().getName(), "@building." + v.type().id(), pos.toShortString());
        NationSync.refreshNation(server, nation);
        return ActionResult.ok(v.message().copy().append(" ").append(Msg.ok("building.registered", nation.population())));
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
        if (server.getTickCount() % SFConfig.BUILDING_RECHECK_TICKS.get() != 0) {
            return;
        }
        NationData data = NationData.get(server);
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
                revalidate(server, nation, entry.getKey(), entry.getValue());
            }
            return;
        }
    }

    private static void revalidate(MinecraftServer server, Nation nation, GlobalPos key, BuildingType current) {
        ServerLevel level = server.getLevel(key.dimension());
        BlockPos pos = key.pos();
        if (level == null || !level.isLoaded(pos)) {
            return;
        }
        boolean stillRegistry = level.getBlockState(pos).is(ModBlocks.CIVIL_REGISTRY.get());
        BuildingValidator.Validation v = stillRegistry
                ? BuildingValidator.validate(level, pos, SFConfig.MAX_BUILDING_VOLUME.get(), SFConfig.FARM_MIN_FARMLAND.get())
                : null;
        if (v == null || !v.valid()) {
            nation.removeBuilding(key);
            nation.log("building_lost", "@building." + current.id(), pos.toShortString());
            NationManager.broadcast(server, nation, Msg.warn("building.lost", current.displayName(), pos.toShortString()));
            NationSync.refreshNation(server, nation);
        } else if (v.type() != current) {
            nation.addBuilding(key, v.type());
            NationSync.refreshNation(server, nation);
        }
    }
}
