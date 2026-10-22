// Propriété : WP3
package io.github.theoblaise1.sovereignfrontier.space;

import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.UnlockKeys;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Oxygène et froid des joueurs sur une planète (docs/conception/J5.md §1.5, §1.6 et §5.1).
 * <p>
 * Toutes les {@value #PERIOD} ticks, {@link #tick} traite un joueur en survie ou en aventure, en O(1) : une lecture de
 * planète (fournie par l'appelant), une à deux requêtes de {@link ZoneIndex} et un parcours d'inventaire seulement pour
 * un joueur exposé. La décision est une fonction pure ({@link #step}) : l'état spatial du joueur
 * ({@link SpaceState}), les capteurs ({@link Sensors}, évalués à la demande) et le temps en entrée ; un {@link Outcome}
 * en sortie (nouvel état, bouteilles à prendre, dégâts, avertissements). {@link #tick} l'applique ensuite au joueur
 * (inventaire, attachement, dégâts, barre d'action). Les GameTests vérifient donc les décisions, pas les points de vie
 * (un faux joueur est invulnérable).
 * <ul>
 *   <li>Oxygène : hors zone oxygénée, 1 s de réserve consommée par seconde ; à 0, une bouteille d'oxygène intacte est
 *       prise (inventaire, puis main secondaire) et une bouteille de gaz vide rendue ; sans bouteille, dégâts
 *       d'asphyxie chaque seconde.</li>
 *   <li>Froid (planète glacée) : hors zone de chaleur et sans plastron actif, le compteur gagne 1 par seconde ; au-delà
 *       du seuil, dégâts de gel toutes les {@code coldDamageIntervalSeconds}. À l'abri, il perd 2 par seconde. Le
 *       compteur reste dans la fenêtre [seuil, seuil + intervalle[ une fois le seuil atteint (il ne croît pas sans
 *       fin).</li>
 *   <li>Plastron chauffant : porté par un joueur dont la nation a {@code armor.gas_heated}, il chauffe en brûlant une
 *       bouteille de gaz pleine par {@code heatedChestplateBottleSeconds} secondes (bouteille vide rendue).</li>
 *   <li>Acclimatation ({@code graceUntil}) : ni consommation ni froid, seulement un compte à rebours à l'écran.</li>
 *   <li>Exemptés : Créatif et spectateur.</li>
 * </ul>
 */
public final class Environment {
    /** Période du traitement par joueur (ticks). */
    public static final int PERIOD = 20;
    /** Dégâts d'asphyxie : ignorent l'armure, sans recul (docs/conception/J5.md §2.1). */
    public static final ResourceKey<DamageType> ASPHYXIA = damageKey("asphyxie");
    /** Dégâts de gel : ignorent l'armure, sans recul. */
    public static final ResourceKey<DamageType> FREEZE = damageKey("gel");

    private Environment() {
    }

    private static ResourceKey<DamageType> damageKey(String path) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path));
    }

    // ------------------------------------------------------------------ décision (pure)

    /**
     * Réglages numériques de la décision, lus dans la configuration à l'exécution ({@link #current}).
     *
     * @param bottleSeconds   autonomie d'une bouteille d'oxygène (s)
     * @param oxygenDamage    dégâts d'asphyxie par seconde
     * @param warnSeconds     avertissement quand la dernière bouteille descend à ce nombre de secondes
     * @param coldThreshold   secondes d'exposition avant les premiers dégâts de gel
     * @param coldDamage      dégâts de gel
     * @param coldInterval    intervalle entre deux dégâts de gel (s)
     * @param plastronSeconds secondes de chaleur d'une bouteille de gaz brûlée par le plastron
     */
    public record Settings(int bottleSeconds, float oxygenDamage, int warnSeconds, int coldThreshold, float coldDamage,
                           int coldInterval, int plastronSeconds) {
        public static Settings current() {
            return new Settings(SFConfig.OXYGEN_BOTTLE_SECONDS.get(), SFConfig.OXYGEN_DAMAGE.get().floatValue(),
                    SFConfig.OXYGEN_WARN_SECONDS.get(), SFConfig.COLD_THRESHOLD_SECONDS.get(),
                    SFConfig.COLD_DAMAGE.get().floatValue(), SFConfig.COLD_DAMAGE_INTERVAL_SECONDS.get(),
                    SFConfig.HEATED_CHESTPLATE_BOTTLE_SECONDS.get());
        }
    }

    /**
     * Ce que {@link #step} demande de savoir sur le joueur. Chaque capteur n'est interrogé que si la décision en a
     * besoin (aucun parcours d'inventaire pour un joueur à l'abri).
     */
    public interface Sensors {
        /** Le joueur est dans une zone d'oxygène de sa position. */
        boolean oxygenZone();

        /** Le joueur est dans une zone de chaleur de sa position. */
        boolean heatZone();

        /** Plastron chauffant porté, et {@code armor.gas_heated} accordé à la nation du joueur. */
        boolean plastronReady();

        /** Bouteilles d'oxygène intactes de l'inventaire (puis main secondaire). */
        int oxygenBottles();

        /** Bouteilles de gaz pleines intactes de l'inventaire (puis main secondaire). */
        int fullGasBottles();
    }

    /**
     * Décision d'un passage. {@code bottlesLeft} vaut −1 si les bouteilles n'ont pas été comptées (joueur à l'abri ou
     * planète sans oxygène) ; sinon c'est le nombre de bouteilles restantes <i>après</i> celle qui est prise.
     */
    public record Outcome(SpaceState state, boolean grace, int graceSeconds, boolean oxygenPlanet, boolean inOxygenZone,
                          int bottlesLeft, boolean takeOxygenBottle, float asphyxiaDamage, boolean warnLow,
                          boolean warnLastBottle, boolean coldPlanet, boolean warm, boolean takeGasBottle,
                          float coldDamage, boolean warnFreezing) {
    }

    /**
     * Un passage de l'environnement (toutes les 20 ticks) pour un joueur sur une planète d'environnement
     * {@code env} ; fonction pure, sans effet de bord.
     *
     * @param nowMs temps courant (epoch ms), pour l'acclimatation
     */
    public static Outcome step(SpaceState state, PlanetDefinition.Environment env, Sensors sensors, Settings cfg, long nowMs) {
        if (state.inGrace(nowMs)) {
            int left = (int) ((state.graceUntil() - nowMs + 999L) / 1000L);
            return new Outcome(state, true, left, env.oxygen(), false, -1, false, 0.0F, false, false, env.cold(), false,
                    false, 0.0F, false);
        }
        int oxygen = state.oxygen();
        int cold = state.cold();
        int heat = state.heat();

        boolean inZone = false;
        int bottlesLeft = -1;
        boolean takeOxygen = false;
        float asphyxia = 0.0F;
        boolean warnLow = false;
        boolean warnLast = false;
        if (env.oxygen()) {
            inZone = sensors.oxygenZone();
            if (!inZone) {
                int bottles = sensors.oxygenBottles();
                if (oxygen <= 0 && bottles > 0) {
                    takeOxygen = true;
                    oxygen = cfg.bottleSeconds();
                    bottles--;
                    warnLast = bottles == 0;
                }
                bottlesLeft = bottles;
                if (oxygen > 0) {
                    int before = oxygen;
                    oxygen--;
                    warnLow = bottles == 0 && cfg.warnSeconds() > 0 && before > cfg.warnSeconds() && oxygen <= cfg.warnSeconds();
                } else {
                    asphyxia = cfg.oxygenDamage();
                }
            }
        }

        boolean warm = false;
        boolean takeGas = false;
        float freeze = 0.0F;
        boolean warnFreezing = false;
        if (env.cold()) {
            warm = sensors.heatZone();
            if (!warm && sensors.plastronReady()) {
                if (heat > 0) {
                    warm = true;
                } else if (sensors.fullGasBottles() > 0) {
                    takeGas = true;
                    heat = cfg.plastronSeconds();
                    warm = true;
                }
                if (warm) {
                    heat--;
                }
            }
            if (warm) {
                cold = Math.max(0, cold - 2);
            } else {
                int before = cold;
                cold++;
                if (cold >= cfg.coldThreshold() + cfg.coldInterval()) {
                    cold = cfg.coldThreshold() + (cold - cfg.coldThreshold()) % cfg.coldInterval();
                }
                if (cold >= cfg.coldThreshold() && (cold - cfg.coldThreshold()) % cfg.coldInterval() == 0) {
                    freeze = cfg.coldDamage();
                }
                warnFreezing = before < cfg.coldThreshold() && cold >= cfg.coldThreshold();
            }
        } else {
            cold = Math.max(0, cold - 2);
        }
        SpaceState next = state.withOxygen(oxygen).withCold(cold).withHeat(Math.max(0, heat));
        return new Outcome(next, false, 0, env.oxygen(), inZone, bottlesLeft, takeOxygen, asphyxia, warnLow, warnLast,
                env.cold(), warm, takeGas, freeze, warnFreezing);
    }

    /**
     * Passage d'un joueur hors de toute planète : le froid se dissipe de 2 par seconde (fonction pure).
     */
    public static SpaceState offPlanet(SpaceState state) {
        return state.cold() > 0 ? state.withCold(Math.max(0, state.cold() - 2)) : state;
    }

    // ------------------------------------------------------------------ application au joueur

    /** Créatif et spectateur sont exemptés ; un joueur mort n'est pas traité. */
    public static boolean exempt(ServerPlayer player) {
        return player.isCreative() || player.isSpectator() || !player.isAlive();
    }

    /**
     * Traite le joueur pour un passage (à appeler toutes les {@value #PERIOD} ticks) : décide, puis applique la
     * décision (bouteilles, attachement, dégâts, avertissements, barre d'action).
     *
     * @param planet planète de la dimension du joueur, ou {@code null} s'il n'est sur aucune planète
     * @return la décision appliquée, ou {@code null} (joueur exempté ou hors planète : rien à décider)
     */
    @Nullable
    public static Outcome tick(ServerPlayer player, @Nullable PlanetDefinition planet, long nowMs) {
        if (exempt(player)) {
            return null;
        }
        SpaceState before = player.getData(ModAttachments.SPACE);
        if (planet == null) {
            SpaceState after = offPlanet(before);
            if (after != before) {
                player.setData(ModAttachments.SPACE, after);
            }
            return null;
        }
        Settings cfg = Settings.current();
        Outcome out = step(before, planet.environment(), new PlayerSensors(player), cfg, nowMs);
        if (out.takeOxygenBottle()) {
            swapForEmpty(player, new ItemStack(ModItems.OXYGEN_BOTTLE.get()));
        }
        if (out.takeGasBottle()) {
            swapForEmpty(player, new ItemStack(ModItems.FULL_GAS_BOTTLE.get()));
        }
        if (!out.state().equals(before)) {
            player.setData(ModAttachments.SPACE, out.state());
        }
        Component bar = bar(out, cfg);
        if (bar != null) {
            player.displayClientMessage(bar, true);
        }
        if (out.warnLow()) {
            player.sendSystemMessage(Msg.warn("oxygen.warn.low", cfg.warnSeconds()));
        }
        if (out.warnLastBottle()) {
            player.sendSystemMessage(Msg.warn("oxygen.warn.last_bottle"));
        }
        if (out.warnFreezing()) {
            player.sendSystemMessage(Msg.warn("cold.warn.freezing"));
        }
        // Les dégâts en dernier : l'état est déjà écrit si le joueur meurt (attachement copié à la mort).
        hurt(player, ASPHYXIA, out.asphyxiaDamage());
        hurt(player, FREEZE, out.coldDamage());
        return out;
    }

    /** Retire une bouteille intacte du joueur et lui rend une bouteille de gaz vide. */
    private static void swapForEmpty(ServerPlayer player, ItemStack taken) {
        Inventories.consumePlain(player, taken.getItem(), 1);
        Inventories.giveOrDrop(player, new ItemStack(ModItems.GAS_BOTTLE.get()));
    }

    private static void hurt(ServerPlayer player, ResourceKey<DamageType> type, float amount) {
        if (amount > 0.0F) {
            player.hurt(new DamageSource(player.level().registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(type)), amount);
        }
    }

    /** Capteurs réels d'un joueur : zones d'oxygène et de chaleur, plastron, inventaire. */
    private record PlayerSensors(ServerPlayer player) implements Sensors {
        @Override
        public boolean oxygenZone() {
            return ZoneIndex.covers(player.serverLevel(), player.blockPosition(), ZoneIndex.Kind.OXYGEN);
        }

        @Override
        public boolean heatZone() {
            return ZoneIndex.covers(player.serverLevel(), player.blockPosition(), ZoneIndex.Kind.HEAT);
        }

        @Override
        public boolean plastronReady() {
            if (!player.getItemBySlot(EquipmentSlot.CHEST).is(ModItems.HEATED_CHESTPLATE.get())) {
                return false;
            }
            Nation nation = NationData.get(player.server).nationOf(player.getUUID());
            return nation != null && nation.research().hasUnlock(UnlockKeys.ARMOR_GAS_HEATED);
        }

        @Override
        public int oxygenBottles() {
            return Inventories.countPlain(player, ModItems.OXYGEN_BOTTLE.get());
        }

        @Override
        public int fullGasBottles() {
            return Inventories.countPlain(player, ModItems.FULL_GAS_BOTTLE.get());
        }
    }

    // ------------------------------------------------------------------ connexion et réapparition

    /**
     * Acclimatation à la connexion (docs/conception/J5.md §1.5) : accordée seulement si la précédente date d'au moins
     * {@code cooldownMinutes} (un recul d'horloge allonge l'attente) ; elle ne se cumule pas avec une acclimatation
     * plus longue déjà en cours. Fonction pure.
     */
    public static SpaceState loginGrace(SpaceState state, long nowMs, int graceSeconds, int cooldownMinutes) {
        if (graceSeconds <= 0) {
            return state;
        }
        boolean due = state.lastLoginGrace() == 0L || nowMs >= state.lastLoginGrace() + cooldownMinutes * 60_000L;
        if (!due) {
            return state;
        }
        return state.withGraceUntil(Math.max(state.graceUntil(), nowMs + graceSeconds * 1000L)).withLastLoginGrace(nowMs);
    }

    /** Connexion : acclimatation si le joueur arrive sur une planète. */
    public static void onLogin(ServerPlayer player, long nowMs) {
        if (!SpaceDimensions.isPlanet(player.level())) {
            return;
        }
        SpaceState before = player.getData(ModAttachments.SPACE);
        SpaceState after = loginGrace(before, nowMs, SFConfig.SPACE_GRACE_SECONDS.get(),
                SFConfig.SPACE_LOGIN_GRACE_COOLDOWN_MINUTES.get());
        if (!after.equals(before)) {
            player.setData(ModAttachments.SPACE, after);
        }
    }

    /** Réapparition : oxygène, froid et charge du plastron repartent de 0 (l'attachement est copié à la mort). */
    public static void onRespawn(ServerPlayer player) {
        player.setData(ModAttachments.SPACE, player.getData(ModAttachments.SPACE).afterRespawn());
    }

    // ------------------------------------------------------------------ affichage

    /**
     * Barre d'action du passage, ou {@code null} s'il n'y a rien à dire : « Acclimatation : 8 s » ; « O₂ : 3 bouteille(s) +
     * 74 s · Froid : 12/30 s » ; « Zone oxygénée · Froid : … ». Un joueur à l'abri sur une planète sans froid ne reçoit
     * rien (la barre d'action est partagée avec les messages de territoire).
     */
    @Nullable
    public static Component bar(Outcome out, Settings cfg) {
        if (out.grace()) {
            return Msg.info("oxygen.bar.grace", out.graceSeconds());
        }
        List<Component> parts = new ArrayList<>(2);
        SpaceState state = out.state();
        if (out.oxygenPlanet()) {
            if (!out.inOxygenZone()) {
                ChatFormatting color = out.asphyxiaDamage() > 0.0F ? ChatFormatting.RED
                        : out.bottlesLeft() == 0 && state.oxygen() <= cfg.warnSeconds() ? ChatFormatting.GOLD : ChatFormatting.GREEN;
                parts.add(Msg.tr("oxygen.bar.reserve", Math.max(0, out.bottlesLeft()), state.oxygen()).withStyle(color));
            } else if (out.coldPlanet()) {
                parts.add(Msg.ok("oxygen.bar.zone"));
            }
        }
        if (out.coldPlanet()) {
            if (out.warm()) {
                parts.add(Msg.ok("cold.bar.warm"));
            } else {
                ChatFormatting color = state.cold() >= cfg.coldThreshold() ? ChatFormatting.RED : ChatFormatting.GOLD;
                parts.add(Msg.tr("cold.bar.exposure", state.cold(), cfg.coldThreshold()).withStyle(color));
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        MutableComponent line = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(parts.get(i));
        }
        return line;
    }

    /** État complet du joueur pour {@code /oxygen} : planète, oxygène, froid, zones, acclimatation, point de retour. */
    public static List<Component> status(ServerPlayer player, long nowMs) {
        List<Component> lines = new ArrayList<>();
        SpaceState state = player.getData(ModAttachments.SPACE);
        Settings cfg = Settings.current();
        PlanetDefinition planet = SpaceDimensions.planet(player.level());
        lines.add(Msg.info("oxygen.status.header", player.getName()));
        if (planet == null) {
            lines.add(Msg.info("oxygen.status.no_planet"));
        } else {
            lines.add(Msg.info("oxygen.status.planet", planet.displayName()));
            if (state.inGrace(nowMs)) {
                lines.add(Msg.ok("oxygen.status.grace", (state.graceUntil() - nowMs + 999L) / 1000L));
            }
            PlayerSensors sensors = new PlayerSensors(player);
            if (planet.environment().oxygen()) {
                if (sensors.oxygenZone()) {
                    lines.add(Msg.ok("oxygen.status.zone_in"));
                } else {
                    lines.add(Msg.warn("oxygen.status.zone_out", state.oxygen(), sensors.oxygenBottles()));
                }
            } else {
                lines.add(Msg.info("oxygen.status.no_oxygen"));
            }
            if (planet.environment().cold()) {
                if (sensors.heatZone()) {
                    lines.add(Msg.ok("cold.status.zone"));
                } else if (sensors.plastronReady()) {
                    lines.add(Msg.ok("cold.status.plastron", state.heat(), sensors.fullGasBottles()));
                } else {
                    lines.add(Msg.warn("cold.status.exposed"));
                }
                lines.add(Msg.info("cold.status.exposure", state.cold(), cfg.coldThreshold()));
            } else {
                lines.add(Msg.info("cold.status.no_cold"));
            }
        }
        Nation nation = NationData.get(player.server).nationOf(player.getUUID());
        ReturnPoints.Target target = ReturnPoints.resolve(player.server, nation, state.returnPoint().orElse(null));
        BlockPos at = target.column().pos();
        lines.add(Msg.info("oxygen.status.return", at.getX(), at.getY(), at.getZ(), sourceLabel(target.source())));
        return lines;
    }

    private static Component sourceLabel(ReturnPoints.Source source) {
        return switch (source) {
            case HOME -> Msg.tr("oxygen.status.source_home");
            case CORE -> Msg.tr("oxygen.status.source_core");
            case SPAWN -> Msg.tr("oxygen.status.source_spawn");
        };
    }
}
