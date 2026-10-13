package io.github.theoblaise1.sovereignfrontier.client.dev;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.network.NationTab;
import io.github.theoblaise1.sovereignfrontier.network.RequestNationDataC2S;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition;
import io.github.theoblaise1.sovereignfrontier.research.ResearchManager;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.research.ResearchTier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Outil de développement : capture automatique de l'écran national, inactif sauf si la variable
 * d'environnement {@code SF_SCREENSHOTS} est définie. Crée un monde plat, prépare une nation de
 * démonstration sur le serveur intégré, ouvre chaque onglet, écrit les captures dans
 * {@code runs/client/screenshots/} puis quitte. Usage :
 * {@code SF_SCREENSHOTS=1 xvfb-run -s "-screen 0 1280x720x24" ./gradlew runClient}.
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(modid = SovereignFrontier.MODID, value = Dist.CLIENT)
public final class ScreenshotHarness {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = System.getenv("SF_SCREENSHOTS") != null;

    private record Step(String name, Runnable action, int waitTicks) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int stage;
    private static int wait;
    private static int step;
    /** Vrai : le curseur survole le centre de l'écran (pour capturer une infobulle). */
    private static boolean hover;

    private ScreenshotHarness() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null && stage >= 3) {
            // Curseur dans un coin (pas d'infobulle parasite), sauf pour la capture d'infobulle.
            double cx = hover ? mc.getWindow().getScreenWidth() * 0.72D : 0.0D;
            double cy = hover ? mc.getWindow().getScreenHeight() * 0.45D : 0.0D;
            placeCursor(mc, cx, cy);
        }
        switch (stage) {
            case 0 -> {
                if (mc.screen instanceof AccessibilityOnboardingScreen) {
                    mc.options.onboardAccessibility = false;
                    mc.setScreen(new TitleScreen());
                } else if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                    createWorld(mc);
                    stage = 1;
                }
            }
            case 1 -> {
                if (mc.player != null && mc.getSingleplayerServer() != null && mc.screen == null) {
                    mc.getTutorial().setStep(TutorialSteps.NONE);
                    wait = 100;
                    stage = 2;
                }
            }
            case 2 -> {
                if (--wait <= 0) {
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> setup(server));
                    buildSteps(mc);
                    wait = 60;
                    stage = 3;
                }
            }
            case 3 -> {
                if (--wait <= 0) {
                    if (step >= STEPS.size()) {
                        LOGGER.info("[captures] terminé, arrêt du client");
                        stage = 4;
                        mc.stop();
                        return;
                    }
                    Step s = STEPS.get(step);
                    if (s.action() != null) {
                        s.action().run();
                        wait = s.waitTicks();
                        STEPS.set(step, new Step(s.name(), null, 0));
                    } else {
                        if (s.name() != null) {
                            Screenshot.grab(mc.gameDirectory, s.name() + ".png", mc.getMainRenderTarget(),
                                    msg -> LOGGER.info("[captures] {}", msg.getString()));
                        }
                        step++;
                        wait = 5;
                    }
                }
            }
            default -> {
            }
        }
    }

    /** Sous Xvfb, déplacer le pointeur GLFW ne déclenche aucun rappel : on fixe aussi la position lue par le jeu. */
    private static void placeCursor(Minecraft mc, double x, double y) {
        GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), x, y);
        try {
            java.lang.reflect.Field fx = net.minecraft.client.MouseHandler.class.getDeclaredField("xpos");
            java.lang.reflect.Field fy = net.minecraft.client.MouseHandler.class.getDeclaredField("ypos");
            fx.setAccessible(true);
            fy.setAccessible(true);
            fx.setDouble(mc.mouseHandler, x);
            fy.setDouble(mc.mouseHandler, y);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[captures] position du curseur non modifiable : {}", e.toString());
        }
    }

    private static void createWorld(Minecraft mc) {
        LevelSettings settings = new LevelSettings("sf_captures_" + System.currentTimeMillis(), GameType.CREATIVE, false,
                Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(settings.levelName(), settings, new WorldOptions(42L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    /** Nation de démonstration : territoire, nation rivale, trésorerie, recherches, coffre. */
    private static void setup(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        LOGGER.info("[captures] création : {}", NationManager.create(player, "Aurore").message().getString());
        Nation nation = NationData.get(server).nationOf(player.getUUID());
        NationAdmin.adjustTreasury(server, nation, 50_000L);
        ChunkPos c = player.chunkPosition();
        for (int[] d : new int[][]{{1, 0}, {2, 0}, {0, 1}, {1, 1}, {-1, 0}}) {
            NationManager.claim(player, player.level().dimension(), new ChunkPos(c.x + d[0], c.z + d[1]));
        }
        ServerPlayer rival = FakePlayerFactory.get(player.serverLevel(), new GameProfile(UUID.randomUUID(), "Rival"));
        rival.moveTo(player.getX() + 16 * 4, player.getY(), player.getZ() - 16 * 3);
        NationManager.create(rival, "Rivale");

        ResearchRegistry registry = ResearchRegistry.server();
        for (String id : new String[]{"ressources_1", "ressources_2"}) {
            ResearchTier tier = registry.get(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, id));
            if (tier != null) {
                ResearchManager.adminComplete(server, nation, tier);
            }
        }
        ResearchTier next = registry.get(ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, "civil_1"));
        if (next != null) {
            for (ResearchDefinition.ItemCost cost : next.definition().cost().items()) {
                int left = cost.count();
                while (left > 0) {
                    int n = Math.min(left, cost.item().getDefaultMaxStackSize());
                    nation.chest().addItem(new ItemStack(cost.item(), n));
                    left -= n;
                }
            }
        }
        LOGGER.info("[captures] {} palier(s) de recherche chargés", registry.size());
    }

    private static void buildSteps(Minecraft mc) {
        for (NationTab tab : NationTab.values()) {
            STEPS.add(new Step("sf_" + tab.ordinal() + "_" + tab.id(),
                    () -> PacketDistributor.sendToServer(new RequestNationDataC2S(true, tab.ordinal())), 30));
        }
        STEPS.add(new Step(null, () -> mc.setScreen(null), 5));
        STEPS.add(new Step(null, () -> mc.player.connection.sendCommand("research start civil_1"), 20));
        STEPS.add(new Step("sf_7_research_active",
                () -> PacketDistributor.sendToServer(new RequestNationDataC2S(true, NationTab.RESEARCH.ordinal())), 40));
        STEPS.add(new Step(null, () -> mc.setScreen(null), 5));
        STEPS.add(new Step(null, () -> mc.player.connection.sendCommand("research info industrie_8"), 20));
        STEPS.add(new Step("sf_8_chat_research_info", () -> mc.setScreen(new ChatScreen("")), 20));
        STEPS.add(new Step(null, () -> mc.setScreen(null), 5));
        STEPS.add(new Step(null, () -> mc.player.connection.sendCommand("map"), 20));
        STEPS.add(new Step("sf_9_chat_map", () -> mc.setScreen(new ChatScreen("")), 20));
        STEPS.add(new Step(null, () -> mc.setScreen(null), 5));
        STEPS.add(new Step(null, () -> mc.player.connection.sendCommand("research list civil"), 20));
        STEPS.add(new Step("sf_10_chat_research_list", () -> mc.setScreen(new ChatScreen("")), 20));
        STEPS.add(new Step(null, () -> mc.setScreen(null), 5));
        STEPS.add(new Step("sf_11_research_tooltip", () -> {
            hover = true;
            PacketDistributor.sendToServer(new RequestNationDataC2S(true, NationTab.RESEARCH.ordinal()));
        }, 40));
    }
}
