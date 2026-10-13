package io.github.theoblaise1.sovereignfrontier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

import static io.github.theoblaise1.sovereignfrontier.command.CommandUtil.send;

/** {@code /claim}, {@code /unclaim} (chunk courant) et {@code /map} (carte ASCII des chunks). */
public final class TerritoryCommands {
    private static final int MAP_HALF_WIDTH = 10;
    private static final int MAP_HALF_HEIGHT = 4;

    private TerritoryCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("claim").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            return send(ctx.getSource(), NationManager.claim(player, player.level().dimension(), player.chunkPosition()));
        }));
        dispatcher.register(Commands.literal("unclaim").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            return send(ctx.getSource(), NationManager.unclaim(player, player.level().dimension(), player.chunkPosition()));
        }));
        dispatcher.register(Commands.literal("map").executes(TerritoryCommands::map));
    }

    private static int map(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        NationData data = NationData.get(player.server);
        Nation own = data.nationOf(player.getUUID());
        ChunkPos center = player.chunkPosition();

        Nation here = data.ownerAt(player.level().dimension(), center.toLong());
        MutableComponent out = Msg.tr("map.header", center.x, center.z,
                here == null ? Msg.tr("map.wilderness") : Component.literal(here.name())).withStyle(ChatFormatting.AQUA);
        for (int dz = -MAP_HALF_HEIGHT; dz <= MAP_HALF_HEIGHT; dz++) {
            MutableComponent line = Component.literal("\n");
            for (int dx = -MAP_HALF_WIDTH; dx <= MAP_HALF_WIDTH; dx++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                Nation owner = data.ownerAt(player.level().dimension(), ChunkPos.asLong(cx, cz));
                MutableComponent cell;
                if (dx == 0 && dz == 0) {
                    cell = Component.literal("+").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
                } else if (owner == null) {
                    cell = Component.literal("-").withStyle(ChatFormatting.DARK_GRAY);
                } else if (owner == own) {
                    cell = Component.literal("■").withStyle(ChatFormatting.GREEN);
                } else {
                    cell = Component.literal("■").withColor(owner.color());
                }
                Component hover = Component.literal(cx + ", " + cz + " — ")
                        .append(owner == null ? Msg.tr("map.wilderness") : Component.literal(owner.name()));
                line.append(cell.withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
            }
            out.append(line);
        }
        out.append("\n").append(Msg.info("map.legend"));
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }
}
