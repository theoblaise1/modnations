package io.github.theoblaise1.sovereignfrontier.client.screen;

import io.github.theoblaise1.sovereignfrontier.machine.MachineBlockEntity;
import io.github.theoblaise1.sovereignfrontier.machine.MachineKind;
import io.github.theoblaise1.sovereignfrontier.machine.MachineMenu;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Interface commune des machines : jauges d'énergie (et de chaleur, de gaz…), emplacements, inventaire. */
@OnlyIn(Dist.CLIENT)
public class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    private static final int COL_PANEL = 0xFF2B2E36;
    private static final int COL_BORDER = 0xFF5B6070;
    private static final int COL_SLOT = 0xFF14161B;
    private static final int COL_ENERGY = 0xFF3FB8E8;
    private static final int COL_HEAT = 0xFFE8702A;
    private static final int COL_GAS = 0xFF9AD15A;
    private static final int COL_TEXT = 0xFFE0E0E0;
    private Button toggle;

    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 166;
        inventoryLabelY = 74;
    }

    @Override
    protected void init() {
        super.init();
        if (menu.kind() == MachineKind.REACTOR) {
            toggle = addRenderableWidget(Button.builder(Component.empty(),
                    b -> minecraft.gameMode.handleInventoryButtonClick(menu.containerId, MachineMenu.BUTTON_TOGGLE))
                    .bounds(leftPos + 112, topPos + 54, 56, 20).build());
        }
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos - 1, topPos - 1, leftPos + imageWidth + 1, topPos + imageHeight + 1, COL_BORDER);
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, COL_PANEL);
        for (Slot slot : menu.slots) {
            g.fill(leftPos + slot.x - 1, topPos + slot.y - 1, leftPos + slot.x + 17, topPos + slot.y + 17, COL_SLOT);
        }
        long energy = menu.wide(0);
        long capacity = Math.max(1, menu.wide(2));
        bar(g, 8, 16, 160, 4, (float) energy / capacity, COL_ENERGY);
        if (menu.kind() == MachineKind.REACTOR) {
            bar(g, 8, 22, 160, 4, (float) menu.wide(4) / Math.max(1, menu.wide(8)), COL_HEAT);
        } else if (menu.kind() == MachineKind.GAS_DRILL) {
            bar(g, 8, 22, 160, 4, (float) menu.value(7) / MachineBlockEntity.GAS_TANK, COL_GAS);
        } else if (menu.kind() == MachineKind.THERMAL) {
            bar(g, 8, 22, 160, 3, menu.value(6) / 100f, COL_HEAT);
        }
    }

    private void bar(GuiGraphics g, int x, int y, int w, int h, float ratio, int color) {
        g.fill(leftPos + x, topPos + y, leftPos + x + w, topPos + y + h, COL_SLOT);
        g.fill(leftPos + x, topPos + y, leftPos + x + (int) (w * Math.max(0, Math.min(1, ratio))), topPos + y + h, color);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(font, title, 8, 6, COL_TEXT, false);
        g.drawString(font, playerInventoryTitle, 8, inventoryLabelY, COL_TEXT, false);
        g.drawString(font, Msg.tr("gui.machine.energy", menu.wide(0), menu.wide(2)).getString(), 8, 28, COL_TEXT, false);
        Component[] lines = statusLines();
        for (int i = 0; i < lines.length; i++) {
            g.drawString(font, lines[i], 8, 37 + 9 * i, COL_TEXT, false);
        }
    }

    /**
     * Lignes d'état, au plus deux, dessinées sans troncature : chaque texte est découpé en clés assez courtes
     * pour tenir dans les 160 px disponibles (police vanilla), y compris pour les valeurs usuelles.
     */
    private Component[] statusLines() {
        return switch (menu.kind()) {
            case REACTOR -> {
                boolean valid = (menu.value(10) & MachineBlockEntity.FLAG_VALID) != 0;
                yield new Component[]{
                        Msg.tr(valid ? "gui.machine.reactor_valid" : "gui.machine.reactor_invalid", menu.value(7) / 100)
                                .withStyle(valid ? ChatFormatting.GREEN : ChatFormatting.RED),
                        Msg.tr("gui.machine.reactor_heat", menu.wide(4), menu.wide(8), menu.value(7) % 100)};
            }
            case GAS_DRILL -> new Component[]{
                    Msg.tr("gui.machine.gas_tank", menu.value(7), MachineBlockEntity.GAS_TANK),
                    Msg.tr("gui.machine.gas_reserve", menu.wide64(11))};
            case SOLAR -> new Component[]{Msg.tr(menu.value(6) > 0 ? "gui.machine.solar_on" : "gui.machine.solar_off")};
            case THERMAL -> new Component[]{Msg.tr(menu.value(6) > 0 ? "gui.machine.burning" : "gui.machine.idle")};
            case BATTERY -> new Component[]{Msg.tr("gui.machine.battery")};
        };
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (toggle != null) {
            boolean running = (menu.value(10) & MachineBlockEntity.FLAG_RUNNING) != 0;
            toggle.setMessage(Msg.tr(running ? "gui.machine.stop" : "gui.machine.start"));
        }
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }
}
