package io.github.theoblaise1.sovereignfrontier.reward;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.economy.EconomyData;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.network.CratePreviewS2C;
import io.github.theoblaise1.sovereignfrontier.network.CratePreviewView;
import io.github.theoblaise1.sovereignfrontier.network.NationSync;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Ouverture des caisses (serveur, thread principal, tout dans le même tick) et aperçu de leur contenu.
 * Ordre d'une ouverture : faux joueur refusé, caisse déjà ouverte, mauvaise clé, table absente, tirage, clé consommée
 * ({@code shrink(1)}), remise (garde d'exécution {@code #interdit_caisses}), pitié, animation, résumé et annonce.
 */
public final class CrateManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private CrateManager() {
    }

    /** Seuil de pitié configuré ({@code cratePityThreshold}). */
    public static int threshold() {
        return SFConfig.CRATE_PITY_THRESHOLD.get();
    }

    /**
     * Clic droit avec une clé sur une caisse : ouverture avec la table publiée ; le résultat est affiché au joueur
     * (résumé dans le chat, refus dans la barre d'action). Les faux joueurs (automatisation) sont refusés.
     */
    public static ActionResult use(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack key) {
        ActionResult result = player instanceof FakePlayer
                ? ActionResult.fail(Msg.err("reward.crate.error.automation"))
                : open(player, level, pos, key, CrateRegistry::get, player.getRandom());
        if (result.success()) {
            player.sendSystemMessage(result.message());
        } else {
            player.displayClientMessage(result.message(), true);
        }
        return result;
    }

    /**
     * Ouverture (étapes 2 à 9 ; public pour les GameTests, qui injectent les tables et le hasard).
     *
     * @param key    pile tenue en main (consommée d'une unité en cas de succès)
     * @param tables table de chaque type (production : {@link CrateRegistry#get})
     */
    public static ActionResult open(ServerPlayer player, ServerLevel level, BlockPos pos, ItemStack key,
                                    Function<CrateKind, CrateTable> tables, RandomSource random) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof CrateBlock crate)) {
            return ActionResult.fail(Msg.err("reward.crate.error.busy"));
        }
        CrateKind kind = crate.kind();
        if (state.getValue(CrateBlock.OPEN)) {
            return ActionResult.fail(Msg.err("reward.crate.error.busy"));
        }
        if (key.isEmpty() || CrateKind.byKey(key.getItem()) != kind) {
            return ActionResult.fail(Msg.err("reward.crate.error.wrong_key", kind.keyItem().getDescription()));
        }
        CrateTable table = tables.apply(kind);
        if (table == null) {
            return ActionResult.fail(Msg.err("reward.crate.error.unavailable", kind.block().getName()));
        }

        MinecraftServer server = level.getServer();
        RewardData data = RewardData.get(server);
        RewardData.PlayerRewards record = data.of(player.getUUID());
        CrateRoller.Roll roll = CrateRoller.roll(table, record.pity(kind), threshold(), random);
        List<CrateRoller.Gain> gains = CrateRoller.realize(roll.drawn(), random);
        key.shrink(1);

        long money = 0L;
        int dropped = 0;
        boolean withheld = false;
        List<CrateRoller.Gain> given = new ArrayList<>(gains.size());
        for (CrateRoller.Gain gain : gains) {
            if (gain.isMoney()) {
                money += gain.money();
                given.add(gain);
            } else if (RestrictedItems.allowInLoot(gain.stack(), "caisse " + kind.id())) {
                given.add(gain);
                dropped += Inventories.giveOrDrop(player, gain.stack().copy());
            } else {
                withheld = true;
            }
        }
        if (money > 0L) {
            EconomyData.get(server).add(player.getUUID(), money);
            NationSync.scheduleRefresh(player);
        }
        record.setPity(kind, roll.miss());
        data.setDirty();

        CrateRoller.Gain rare = null;
        for (CrateRoller.Gain g : given) {
            if (g.entry().isRare()) {
                rare = g;
                break;
            }
        }
        CrateRoller.Gain headline = rare != null ? rare : given.isEmpty() ? null : given.get(0);
        animate(level, pos, state, kind, headline == null ? Items.AIR : headline.isMoney() ? Items.GOLD_INGOT
                : headline.stack().getItem(), rare != null);

        LOGGER.info("[Économie] Caisse {} ouverte par {} : {}{}", kind.id(), player.getGameProfile().getName(),
                logText(given), roll.forced() != null ? " (pitié : " + roll.forced().key() + ")" : "");
        if (rare != null && SFConfig.CRATE_ANNOUNCE_RARE.get()) {
            server.getPlayerList().broadcastSystemMessage(Msg.tr("reward.crate.announce", player.getDisplayName(),
                    gainText(rare), kind.block().getName()).withStyle(ChatFormatting.LIGHT_PURPLE), false);
        }

        MutableComponent summary = Msg.tr("reward.crate.opened", kind.block().getName(), gainsText(given))
                .withStyle(ChatFormatting.GREEN);
        if (dropped > 0) {
            summary.append("\n").append(Msg.warn("reward.crate.dropped", dropped));
        }
        if (withheld) {
            summary.append("\n").append(Msg.err("reward.crate.withheld"));
        }
        return ActionResult.ok(summary);
    }

    /**
     * Animation d'ouverture : {@code OPEN=true} pendant {@code crateOpenTicks} (tick programmé), événement de bloc
     * (objet phare diffusé aux clients proches), particules et sons.
     */
    private static void animate(ServerLevel level, BlockPos pos, BlockState state, CrateKind kind, Item headline, boolean rare) {
        Block block = state.getBlock();
        level.setBlock(pos, state.setValue(CrateBlock.OPEN, true), Block.UPDATE_ALL);
        level.scheduleTick(pos, block, SFConfig.CRATE_OPEN_TICKS.get());
        level.blockEvent(pos, block, CrateBlockEntity.EVENT_OPEN, BuiltInRegistries.ITEM.getId(headline));
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 1.0D;
        double z = pos.getZ() + 0.5D;
        SimpleParticleType particle = switch (kind) {
            case COMMUNE -> ParticleTypes.HAPPY_VILLAGER;
            case MILITAIRE -> ParticleTypes.CRIT;
            case INDUSTRIELLE -> ParticleTypes.ELECTRIC_SPARK;
            case SPATIALE -> ParticleTypes.END_ROD;
        };
        level.sendParticles(particle, x, y, z, 16, 0.35D, 0.3D, 0.35D, 0.05D);
        level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.8F, 1.0F);
        if (rare) {
            level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, x, y, z, 40, 0.4D, 0.5D, 0.4D, 0.3D);
            level.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    private static MutableComponent gainText(CrateRoller.Gain gain) {
        MutableComponent c = gain.isMoney() ? Msg.money(gain.money())
                : Component.literal(gain.stack().getCount() + " × ").append(gain.stack().getHoverName());
        return gain.entry().isRare() ? c.withStyle(gain.entry().rarity().color(), ChatFormatting.BOLD) : c;
    }

    private static MutableComponent gainsText(List<CrateRoller.Gain> gains) {
        if (gains.isEmpty()) {
            return Msg.tr("reward.crate.nothing");
        }
        MutableComponent c = Component.empty();
        for (int i = 0; i < gains.size(); i++) {
            if (i > 0) {
                c.append(", ");
            }
            c.append(gainText(gains.get(i)).withStyle(ChatFormatting.WHITE));
        }
        return c;
    }

    private static String logText(List<CrateRoller.Gain> gains) {
        List<String> parts = new ArrayList<>();
        for (CrateRoller.Gain g : gains) {
            parts.add(g.isMoney() ? g.money() + " cr"
                    : g.stack().getCount() + " × " + BuiltInRegistries.ITEM.getKey(g.stack().getItem()));
        }
        return parts.isEmpty() ? "rien" : String.join(", ", parts);
    }

    // ------------------------------------------------------------------ aperçu et pitié

    /** Aperçu du contenu pour ce joueur (chances, plages, compteurs de pitié) ; indisponible sans table publiée. */
    public static CratePreviewView preview(ServerPlayer player, CrateKind kind) {
        return preview(kind, CrateRegistry.get(kind), pityOf(player.server, player.getUUID(), kind), threshold());
    }

    public static CratePreviewView preview(CrateKind kind, @Nullable CrateTable table, Map<String, Integer> miss, int threshold) {
        if (table == null) {
            return new CratePreviewView((byte) kind.ordinal(), 0, threshold, false, List.of());
        }
        int total = table.totalWeight();
        List<CratePreviewView.EntryLine> lines = new ArrayList<>();
        for (CrateEntry e : table.entries()) {
            if (lines.size() >= CratePreviewView.MAX_ENTRIES) {
                break;
            }
            String milestone = "";
            String label = "";
            if (e.reward() instanceof CrateEntry.Placeholder p) {
                milestone = p.milestone();
                label = p.label();
            }
            lines.add(new CratePreviewView.EntryLine(e.icon(), e.range().min(), e.range().max(),
                    e.reward() instanceof CrateEntry.MoneyReward, e.weight(), total, e.rarity().code(),
                    e.isRare() ? miss.getOrDefault(e.key(), 0) : -1, milestone, label));
        }
        return new CratePreviewView((byte) kind.ordinal(), table.rolls(), threshold, true, lines);
    }

    /** Envoie l'aperçu (ouvre l'écran d'aperçu du client). */
    public static void sendPreview(ServerPlayer player, CrateKind kind) {
        if (NationSync.canReceive(player)) {
            PacketDistributor.sendToPlayer(player, new CratePreviewS2C(preview(player, kind)));
        }
    }

    public static Map<String, Integer> pityOf(MinecraftServer server, UUID player, CrateKind kind) {
        RewardData.PlayerRewards r = RewardData.get(server).find(player);
        return r == null ? Map.of() : r.pity(kind);
    }

    /** Compteurs de pitié par type et par entrée rare publiée ({@code /crate pity}). */
    public static Component pityReport(MinecraftServer server, UUID player) {
        int threshold = threshold();
        MutableComponent c = Msg.tr("reward.crate.pity.header", threshold + 1).withStyle(ChatFormatting.AQUA);
        for (CrateKind kind : CrateKind.values()) {
            CrateTable table = CrateRegistry.get(kind);
            c.append("\n").append(Component.literal(" ")).append(kind.block().getName().copy().withStyle(ChatFormatting.GOLD))
                    .append(Component.literal(" : "));
            if (table == null) {
                c.append(Msg.err("reward.crate.pity.unavailable"));
                continue;
            }
            Map<String, Integer> miss = pityOf(server, player, kind);
            List<CrateEntry> rares = table.rares();
            for (int i = 0; i < rares.size(); i++) {
                CrateEntry e = rares.get(i);
                int m = miss.getOrDefault(e.key(), 0);
                if (i > 0) {
                    c.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
                }
                c.append(Msg.info("reward.crate.pity.entry", e.describe().withStyle(ChatFormatting.AQUA),
                        Msg.hl(Math.min(m, threshold) + "/" + threshold)));
            }
        }
        return c;
    }

    /**
     * Fixe tous les compteurs rares publiés du type ({@code /sfadmin crate pity}).
     *
     * @return nombre d'entrées rares concernées, ou -1 si la table manque
     */
    public static int setPity(MinecraftServer server, UUID player, CrateKind kind, int value) {
        CrateTable table = CrateRegistry.get(kind);
        if (table == null) {
            return -1;
        }
        RewardData data = RewardData.get(server);
        Map<String, Integer> counters = new LinkedHashMap<>();
        for (CrateEntry e : table.rares()) {
            counters.put(e.key(), Math.max(0, value));
        }
        data.of(player).setPity(kind, counters);
        data.setDirty();
        return counters.size();
    }
}
