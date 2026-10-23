// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.war.missile;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import io.github.theoblaise1.sovereignfrontier.util.TimeUtil;
import io.github.theoblaise1.sovereignfrontier.war.SiloBlockEntity;
import io.github.theoblaise1.sovereignfrontier.war.WarData;
import io.github.theoblaise1.sovereignfrontier.war.WarProtections;
import io.github.theoblaise1.sovereignfrontier.war.WarQueries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /nation missile fire} (J7, docs/conception/J7.md §1.6, §3) : tir depuis le silo de la nation vers le chunk
 * regardé (D11, 200 blocs au plus). Refus, dans cet ordre : permission, silo, cible, guerre, portée, capitale, cooldown,
 * munitions. Succès : une munition de chaque consommée, un {@link WarData.PendingMissile} à {@code missileDelaySeconds},
 * cooldown {@code missileCooldownHours}.
 */
public final class MissileCommands {
    /** Portée (blocs) : depuis le silo jusqu'au bloc visé, et longueur du rayon de visée. */
    static final int RANGE = 200;
    /** Distance maximale (blocs) du tireur au silo. */
    static final double REACH = 6.0D;
    private static final long HOUR_MS = 3_600_000L;

    private MissileCommands() {
    }

    /** Sous-arbre {@code missile}, ajouté à {@code /nation} par {@code NationCommand} (WP1). */
    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("missile")
                .then(Commands.literal("fire").executes(MissileCommands::fire));
    }

    private static int fire(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer shooter = ctx.getSource().getPlayerOrException();
        ActionResult result = launch(shooter, aim(shooter));
        if (result.success()) {
            ctx.getSource().sendSuccess(result::message, false);
            return 1;
        }
        ctx.getSource().sendFailure(result.message());
        return 0;
    }

    /** Bloc regardé à {@link #RANGE} blocs au plus ; sans obstacle, le point situé à cette distance. */
    static BlockPos aim(ServerPlayer shooter) {
        Vec3 eye = shooter.getEyePosition();
        Vec3 end = eye.add(shooter.getLookAngle().scale(RANGE));
        BlockHitResult hit = shooter.level().clip(
                new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, shooter));
        return hit.getType() == HitResult.Type.MISS ? BlockPos.containing(end) : hit.getBlockPos();
    }

    /**
     * Tir vers le bloc {@code aim} : le chunk qui le contient est visé (D11). Renvoie le succès ou la première règle violée.
     * Appelé par la commande ; les GameTests l'appellent directement, sans rayon de visée.
     */
    public static ActionResult launch(ServerPlayer shooter, BlockPos aim) {
        MinecraftServer server = shooter.server;
        NationData data = NationData.get(server);
        Nation nation = data.nationOf(shooter.getUUID());
        if (nation == null) {
            return ActionResult.fail(Msg.err("nation.error.no_nation"));
        }
        if (!nation.hasPermission(shooter.getUUID(), NationPermission.FIRE_MISSILE)) {
            return ActionResult.fail(Msg.err("missile.error.no_permission"));
        }
        ServerLevel overworld = server.overworld();
        BlockPos silo = SiloRegistry.get(server).liveSiloOf(overworld, nation.id());
        if (silo == null || !shooter.level().dimension().equals(Level.OVERWORLD)
                || shooter.distanceToSqr(Vec3.atCenterOf(silo)) > REACH * REACH
                || !(overworld.getBlockEntity(silo) instanceof SiloBlockEntity tube)
                || !nation.id().equals(tube.owner())) {
            return ActionResult.fail(Msg.err("missile.error.no_silo"));
        }
        ChunkPos target = new ChunkPos(aim);
        Nation owner = data.ownerAt(Level.OVERWORLD, target.toLong());
        if (owner == null) {
            return ActionResult.fail(Msg.err("missile.error.no_target"));
        }
        WarData war = WarData.get(server);
        if (!WarQueries.atWar(war, nation.id(), owner.id())) {
            return ActionResult.fail(Msg.err("missile.error.not_at_war"));
        }
        if (Vec3.atCenterOf(silo).distanceTo(Vec3.atCenterOf(aim)) > RANGE) {
            return ActionResult.fail(Msg.err("missile.error.range", RANGE));
        }
        if (WarProtections.isCapitalChunk(server, target.x, target.z)) {
            return ActionResult.fail(Msg.err("missile.error.capital"));
        }
        long now = TimeUtil.now();
        long readyAt = war.siloCooldownUntil(silo);
        if (now < readyAt) {
            return ActionResult.fail(Msg.err("missile.error.cooldown", TimeUtil.duration(readyAt - now)));
        }
        if (!tube.canFire()) {
            return ActionResult.fail(Msg.err("missile.error.no_ammo"));
        }
        tube.consumeAmmo();
        long delayMs = SFConfig.MISSILE_DELAY_SECONDS.get() * 1000L;
        war.addMissile(new WarData.PendingMissile(now + delayMs, target.x, target.z, nation.id(), silo));
        war.setSiloCooldown(silo, now + SFConfig.MISSILE_COOLDOWN_HOURS.get() * HOUR_MS);
        String where = target.x + ", " + target.z;
        nation.log("missile_launched", where);
        return ActionResult.ok(Msg.ok("missile.launched", where, TimeUtil.duration(delayMs)));
    }
}
