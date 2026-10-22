// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Fiche de la fusée dans le chat (docs/conception/J5.md §4.1) : propriétaire, carburant de la soute, destinations avec
 * leur coût ou la raison du refus, et boutons cliquables {@code /rocket launch <destination>}. Elle ouvre aussi la
 * session de 60 s qui désigne la fusée pour les commandes {@code /rocket}.
 */
public final class RocketSheet {
    private RocketSheet() {
    }

    /** Affiche la fiche à {@code viewer} et ouvre sa session sur cette fusée. */
    public static void show(ServerPlayer viewer, ServerLevel level, BlockPos pos, RocketBlockEntity rocket, long now) {
        Nation nation = ownerOf(level, rocket);
        viewer.sendSystemMessage(Msg.ok("rocket.sheet.title", nation == null ? Msg.tr("rocket.sheet.no_owner") : Component.literal(nation.name())));
        RocketHold hold = rocket.hold();
        boolean propulsor = FlightPlanner.propulsorActive(nation, hold);
        viewer.sendSystemMessage(Msg.info("rocket.sheet.hold", hold.countPlain(ModItems.ROCKET_FUEL.get()),
                hold.countPlain(ModItems.FULL_GAS_BOTTLE.get()), Msg.tr(propulsor ? "rocket.sheet.yes" : "rocket.sheet.no")));
        if (RocketFlight.countdownAt(level, pos) != null) {
            viewer.sendSystemMessage(Msg.warn("rocket.sheet.launching"));
        } else {
            List<FlightPlanner.Route> routes = FlightPlanner.routesFrom(level.dimension(), PlanetRegistry.get());
            if (routes.isEmpty()) {
                viewer.sendSystemMessage(Msg.info("rocket.sheet.no_route"));
            }
            MutableComponent buttons = Component.empty();
            for (FlightPlanner.Route route : routes) {
                FlightPlanner.Check check = FlightPlanner.check(level, pos, rocket, route, viewer, now);
                if (check.ok()) {
                    viewer.sendSystemMessage(Msg.ok("rocket.sheet.dest_ok", route.destinationDisplay(),
                            FlightPlanner.describeNeeds(List.of(check.plan().fuel()))));
                } else {
                    viewer.sendSystemMessage(Msg.err("rocket.sheet.dest_no", route.destinationDisplay(), check.error()));
                }
                buttons.append(button(route, check.ok())).append(Component.literal(" "));
            }
            if (!routes.isEmpty()) {
                viewer.sendSystemMessage(buttons);
            }
        }
        RocketSessions.open(viewer.getUUID(), GlobalPos.of(level.dimension(), pos.immutable()), now);
    }

    private static MutableComponent button(FlightPlanner.Route route, boolean possible) {
        return Component.literal("[").append(route.destinationDisplay()).append("]").withStyle(Style.EMPTY
                .withColor(possible ? ChatFormatting.GREEN : ChatFormatting.RED)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rocket launch " + route.destinationName()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Msg.info("rocket.sheet.launch_hover", route.destinationDisplay()))));
    }

    @Nullable
    static Nation ownerOf(ServerLevel level, RocketBlockEntity rocket) {
        return rocket.owner() == null ? null : NationData.get(level.getServer()).byId(rocket.owner());
    }
}
