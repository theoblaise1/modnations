package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.AdminBypass;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationAdmin;
import io.github.theoblaise1.sovereignfrontier.nation.NationData;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.registry.ModAttachments;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.space.PlanetDefinition;
import io.github.theoblaise1.sovereignfrontier.space.PlanetRegistry;
import io.github.theoblaise1.sovereignfrontier.space.ReturnPoints;
import io.github.theoblaise1.sovereignfrontier.space.SpaceDimensions;
import io.github.theoblaise1.sovereignfrontier.space.SpaceRules;
import io.github.theoblaise1.sovereignfrontier.space.SpaceState;
import io.github.theoblaise1.sovereignfrontier.space.TravelPermits;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.Reader;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * GameTests des dimensions et de l'accès aux planètes (WP1, docs/conception/J5.md §7) : types de dimension, dimensions
 * décodées, biomes, créatures et minerais ; garde de voyage, minage verrouillé, explosions des minerais, bordure
 * souple, revendication et fondation, lits et ancres, secours à la connexion.
 * <p>
 * Le serveur de GameTests ne crée pas les niveaux des planètes (seuls l'Overworld, le Nether et l'End existent) : les
 * tests qui ont besoin d'un niveau de planète utilisent une planète de test liée à {@code minecraft:the_end}, dans la
 * zone vide loin de l'île centrale, et nettoient leurs blocs dans un {@code finally}.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class SpaceDimensionGameTests {
    private static final String AREA = J5TestUtil.AREA;
    private static final List<String> PLANETS = List.of("lune", "mars", "planete_glacee");
    private static final List<String> ORES = List.of("minerai_titane", "minerai_soufre", "minerai_cryogenique");
    /** Plateforme de test dans l'End : zone vide entre l'île centrale et les îles extérieures. */
    private static final BlockPos END_STAGE = new BlockPos(8, 100, 600);

    private SpaceDimensionGameTests() {
    }

    private static ResourceLocation id(String path) {
        return J5TestUtil.id(path);
    }

    private static String translationKey(Component message) {
        return message.getContents() instanceof TranslatableContents tc ? tc.getKey() : "";
    }

    /**
     * Opérateur de niveau 2 : le serveur de GameTests donne le niveau 0 à {@code PlayerList#op}, donc l'entrée est
     * ajoutée à la main. À retirer par {@link #deop} dans un {@code finally}.
     */
    private static void op(MinecraftServer server, ServerPlayer player) {
        server.getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(), 2, false));
    }

    private static void deop(MinecraftServer server, ServerPlayer player) {
        server.getPlayerList().getOps().remove(player.getGameProfile());
    }

    // ------------------------------------------------------------------ données de la génération

    @GameTest(template = AREA)
    public static void dimensionTypesLoaded(GameTestHelper helper) {
        Registry<DimensionType> types = helper.getLevel().registryAccess().registryOrThrow(Registries.DIMENSION_TYPE);
        Set<ResourceLocation> effects = new HashSet<>();
        for (String name : PLANETS) {
            DimensionType type = types.get(id(name));
            helper.assertTrue(type != null, "type de dimension " + name + " chargé");
            helper.assertFalse(type.bedWorks(), name + " : les lits n'y fonctionnent pas");
            helper.assertFalse(type.respawnAnchorWorks(), name + " : les ancres de réapparition n'y fonctionnent pas");
            helper.assertTrue(type.hasSkyLight() && !type.hasCeiling(), name + " : ciel éclairé, sans plafond");
            helper.assertTrue(!type.natural() && !type.hasRaids(), name + " : boussoles qui tournent, pas de raids");
            helper.assertTrue(type.minY() == 0 && type.height() == 256 && type.logicalHeight() == 256,
                    name + " : min_y 0, hauteur 256");
            helper.assertTrue(id(name).equals(type.effectsLocation()), name + " : effets propres à la planète");
            effects.add(type.effectsLocation());
        }
        helper.assertTrue(effects.size() == PLANETS.size(), "un identifiant d'effets par planète");
        helper.assertTrue(types.get(id("planete_glacee")).monsterSpawnLightTest().getMaxValue() == 7
                && types.get(id("lune")).monsterSpawnLightTest().getMaxValue() == 0, "vagabonds : lumière 0 à 7 sur la planète glacée seulement");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void levelStemsDecode(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        for (String name : PLANETS) {
            Optional<Resource> resource = server.getResourceManager().getResource(id("dimension/" + name + ".json"));
            helper.assertTrue(resource.isPresent(), "dimension/" + name + ".json fourni par le mod");
            JsonElement json;
            try (Reader reader = resource.get().openAsReader()) {
                json = JsonParser.parseReader(reader);
            } catch (IOException e) {
                throw new IllegalStateException("lecture de dimension/" + name + ".json", e);
            }
            LevelStem stem = LevelStem.CODEC.parse(ops, json).getOrThrow(msg -> new IllegalStateException(name + " : " + msg));
            helper.assertTrue(stem.type().unwrapKey().map(k -> k.location().equals(id(name))).orElse(false),
                    name + " : type de dimension référencé résolu");
            ChunkGenerator generator = stem.generator();
            helper.assertTrue(generator instanceof NoiseBasedChunkGenerator noise
                    && noise.generatorSettings().unwrapKey().map(k -> k.location().equals(id(name))).orElse(false),
                    name + " : générateur de bruit et réglages résolus");
            helper.assertTrue(generator.getBiomeSource() instanceof FixedBiomeSource fixed && fixed.possibleBiomes().size() == 1
                    && fixed.possibleBiomes().stream().allMatch(b -> b.unwrapKey().map(k -> k.location().equals(id(name))).orElse(false)),
                    name + " : un seul biome fixe, résolu");

            // Relief 2D : plein sous y = 40, vide au-dessus de y = 120, surface vers y = 80 (± 24) sur une grille de colonnes.
            NoiseGeneratorSettings settings = ((NoiseBasedChunkGenerator) generator).generatorSettings().value();
            DensityFunction density = RandomState.create(settings, server.registryAccess().lookupOrThrow(Registries.NOISE), 42L)
                    .router().finalDensity();
            for (int x = -400; x <= 400; x += 200) {
                for (int z = -400; z <= 400; z += 200) {
                    int surface = -1;
                    for (int y = 255; y >= 0 && surface < 0; y--) {
                        if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0D) {
                            surface = y;
                        }
                    }
                    helper.assertTrue(surface >= 56 && surface <= 104, name + " : surface vers y = 80 en (" + x + ", " + z
                            + "), lu " + surface);
                    helper.assertTrue(density.compute(new DensityFunction.SinglePointContext(x, 30, z)) > 0.0D
                            && density.compute(new DensityFunction.SinglePointContext(x, 125, z)) < 0.0D,
                            name + " : roche sous y = 40, air au-dessus de y = 120");
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void biomesSpawnersAndOres(GameTestHelper helper) {
        var access = helper.getLevel().registryAccess();
        Registry<Biome> biomes = access.registryOrThrow(Registries.BIOME);
        Registry<PlacedFeature> placed = access.registryOrThrow(Registries.PLACED_FEATURE);
        Registry<ConfiguredFeature<?, ?>> configured = access.registryOrThrow(Registries.CONFIGURED_FEATURE);
        for (int i = 0; i < PLANETS.size(); i++) {
            String name = PLANETS.get(i);
            String ore = ORES.get(i);
            Biome biome = biomes.get(id(name));
            helper.assertTrue(biome != null, "biome " + name + " chargé");
            boolean glacial = name.equals("planete_glacee");
            for (MobCategory category : MobCategory.values()) {
                int spawns = biome.getMobSettings().getMobs(category).unwrap().size();
                if (glacial && category == MobCategory.MONSTER) {
                    helper.assertTrue(spawns == 1 && biome.getMobSettings().getMobs(category).unwrap().get(0).type == EntityType.STRAY,
                            "planète glacée : vagabonds seulement");
                } else {
                    helper.assertTrue(spawns == 0, name + " : aucune créature de la catégorie " + category.getName());
                }
            }
            helper.assertTrue(biome.hasPrecipitation() == glacial, name + " : précipitations sur la planète glacée seulement");
            helper.assertTrue(glacial ? biome.getBaseTemperature() < 0.0F : biome.getBaseTemperature() > 0.0F,
                    name + " : température cohérente");

            List<List<ResourceLocation>> steps = biome.getGenerationSettings().features().stream()
                    .map(set -> set.stream().map(h -> h.unwrapKey().orElseThrow().location()).toList()).toList();
            helper.assertTrue(steps.size() == 11, name + " : 11 étapes de décoration");
            for (int step = 0; step < steps.size(); step++) {
                helper.assertTrue(steps.get(step).equals(step == 6 ? List.of(id(ore)) : List.of()),
                        name + " : étape " + step + (step == 6 ? " = minerai seul" : " vide"));
            }

            PlacedFeature feature = placed.get(id(ore));
            helper.assertTrue(feature != null && feature.feature().unwrapKey().map(k -> k.location().equals(id(ore))).orElse(false),
                    "placed_feature " + ore + " référence le filon du même nom");
            ConfiguredFeature<?, ?> vein = configured.get(id(ore));
            helper.assertTrue(vein != null && vein.config() instanceof OreConfiguration, "configured_feature " + ore + " de type ore");
            OreConfiguration config = (OreConfiguration) vein.config();
            helper.assertTrue(config.targetStates.size() == 1
                    && config.targetStates.get(0).state.is(BuiltInRegistries.BLOCK.get(id(ore))), ore + " : le filon donne le minerai");

            // Génération réelle d'un filon dans un volume de roche hôte de la planète, posé dans l'End (zone vide).
            BlockState host = access.registryOrThrow(Registries.NOISE_SETTINGS).get(id(name)).defaultBlock();
            helper.assertTrue(config.targetStates.get(0).target.test(host, RandomSource.create(1L)),
                    ore + " : le filon cible la roche par défaut de " + name + " (" + host.getBlock().getDescriptionId() + ")");
            ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
            helper.assertTrue(end != null, "l'End existe sur le serveur de GameTests");
            BlockPos center = new BlockPos(8, 100, 700 + 32 * i);
            try {
                fill(end, center, 8, host);
                helper.assertTrue(vein.place(end, end.getChunkSource().getGenerator(), RandomSource.create(7L), center),
                        ore + " : un filon est posé dans la roche de " + name);
                int ores = count(end, center, 8, BuiltInRegistries.BLOCK.get(id(ore)));
                helper.assertTrue(ores >= 1 && ores <= config.size, ore + " : " + ores + " bloc(s) de minerai (taille " + config.size + ")");
            } finally {
                fill(end, center, 8, Blocks.AIR.defaultBlockState());
            }
        }
        helper.succeed();
    }

    /** Remplit un cube de {@code 2 * radius + 1} blocs de côté centré sur {@code center}. */
    private static void fill(ServerLevel level, BlockPos center, int radius, BlockState state) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            level.setBlock(pos, state, 2 | 16);
        }
    }

    private static int count(ServerLevel level, BlockPos center, int radius, Block block) {
        int n = 0;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (level.getBlockState(pos).is(block)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ garde de voyage

    @GameTest(template = AREA)
    public static void travelGuardBlocksUnauthorized(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = J5TestUtil.fakePlayer(helper, "sf_j5_voyageur");
        Entity cow = EntityType.COW.create(level);
        helper.assertTrue(cow != null, "vache de test");
        for (ResourceKey<Level> planet : SpaceDimensions.ALL) {
            helper.assertFalse(CommonHooks.onTravelToDimension(player, planet), "joueur sans permis vers " + planet.location());
            helper.assertFalse(CommonHooks.onTravelToDimension(cow, planet), "autre entité sans permis vers " + planet.location());
        }
        helper.assertTrue(CommonHooks.onTravelToDimension(player, Level.OVERWORLD), "retour vers l'Overworld toujours permis");
        helper.assertTrue(CommonHooks.onTravelToDimension(player, Level.NETHER) && CommonHooks.onTravelToDimension(player, Level.END),
                "dimensions qui ne sont pas des planètes : non gardées");
        helper.assertTrue(SpaceRules.isSpaceDimension(SpaceDimensions.LUNE) && !SpaceRules.isSpaceDimension(Level.OVERWORLD),
                "les trois dimensions du mod sont des planètes");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j5_travel")
    public static void travelGuardPermitsFlightsAndOps(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = J5TestUtil.fakePlayer(helper, "sf_j5_pilote");
        Entity cow = EntityType.COW.create(helper.getLevel());
        helper.assertTrue(cow != null, "vache de test");
        int permits = TravelPermits.size();
        for (ResourceKey<Level> planet : SpaceDimensions.ALL) {
            boolean allowed = TravelPermits.with(player, () -> CommonHooks.onTravelToDimension(player, planet));
            helper.assertTrue(allowed, "permis de vol : voyage vers " + planet.location() + " accepté");
            helper.assertFalse(CommonHooks.onTravelToDimension(player, planet), "permis retiré après le voyage");
        }
        helper.assertTrue(TravelPermits.with(cow, () -> CommonHooks.onTravelToDimension(cow, SpaceDimensions.MARS)),
                "le permis vaut pour toute entité");
        helper.assertTrue(TravelPermits.size() == permits, "aucun permis résiduel");

        helper.assertTrue(SpaceRules.travelAllowed(true, false) && SpaceRules.travelAllowed(false, true)
                && !SpaceRules.travelAllowed(false, false), "décision : permis ou opérateur");
        op(server, player);
        try {
            helper.assertTrue(player.hasPermissions(2), "joueur de test passé opérateur");
            for (ResourceKey<Level> planet : SpaceDimensions.ALL) {
                helper.assertTrue(CommonHooks.onTravelToDimension(player, planet), "opérateur : voyage vers " + planet.location());
            }
            helper.assertFalse(CommonHooks.onTravelToDimension(cow, SpaceDimensions.LUNE), "l'exception ne vaut que pour les joueurs");
        } finally {
            deop(server, player);
        }
        helper.assertFalse(player.hasPermissions(2) || CommonHooks.onTravelToDimension(player, SpaceDimensions.LUNE),
                "plus opérateur : refusé de nouveau");
        helper.succeed();
    }

    // ------------------------------------------------------------------ minage et explosions

    private static boolean breakCanceled(ServerLevel level, BlockPos pos, ServerPlayer player) {
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(level, pos, level.getBlockState(pos), player);
        NeoForge.EVENT_BUS.post(event);
        return event.isCanceled();
    }

    @GameTest(template = AREA, batch = "sf_j5_mining")
    public static void miningNeedsUnlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_mineur");
        ServerPlayer loner = J5TestUtil.fakePlayer(helper, "sf_j5_sans_nation");
        Nation nation = J5TestUtil.foundNation(helper, chef, "SfMine");
        BlockPos titane = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos soufre = helper.absolutePos(new BlockPos(3, 2, 1));
        BlockPos cryo = helper.absolutePos(new BlockPos(5, 2, 1));
        BlockPos stone = helper.absolutePos(new BlockPos(7, 2, 1));
        // Hors du territoire de la nation : le refus d'un joueur sans nation vient alors de la recherche, pas de la protection.
        BlockPos wild = titane.offset(48, 0, 0);
        try {
            helper.assertTrue(NationData.get(server).ownerAt(level.dimension(), new ChunkPos(wild).toLong()) == null,
                    "bloc hors territoire");
            level.setBlock(wild, ModBlocks.TITANIUM_ORE.get().defaultBlockState(), 3);
            level.setBlock(titane, ModBlocks.TITANIUM_ORE.get().defaultBlockState(), 3);
            level.setBlock(soufre, ModBlocks.SULFUR_ORE.get().defaultBlockState(), 3);
            level.setBlock(cryo, ModBlocks.CRYOGENIC_ORE.get().defaultBlockState(), 3);
            level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3);
            helper.assertTrue(!nation.research().hasUnlock("mining.lunar"), "nation neuve : aucune extraction");
            helper.assertTrue(breakCanceled(level, titane, chef) && breakCanceled(level, soufre, chef) && breakCanceled(level, cryo, chef),
                    "sans recherche : les trois minerais résistent");
            helper.assertFalse(breakCanceled(level, stone, chef), "un bloc ordinaire reste cassable");

            J5TestUtil.complete(helper, nation, "ressources_13");
            helper.assertTrue(nation.research().hasUnlock("mining.lunar"), "ressources_13 accorde mining.lunar");
            helper.assertFalse(breakCanceled(level, titane, chef), "mining.lunar : le titane se casse");
            helper.assertTrue(breakCanceled(level, soufre, chef) && breakCanceled(level, cryo, chef),
                    "mining.lunar ne libère ni le soufre ni le cristal");
            helper.assertTrue(breakCanceled(level, wild, loner), "joueur sans nation : refusé");
            helper.assertTrue(translationKey(SpaceRules.miningBlockReason(loner, level.getBlockState(wild)))
                    .equals("sovereignfrontier.space.mining.locked"), "motif du refus : space.mining.locked");

            J5TestUtil.complete(helper, nation, "ressources_14", "ressources_15");
            helper.assertFalse(breakCanceled(level, soufre, chef) || breakCanceled(level, cryo, chef),
                    "mining.martian et mining.cryogenic : soufre et cristal se cassent");

            // Créatif : exempté.
            helper.assertTrue(breakCanceled(level, wild, loner), "témoin : toujours refusé sans nation");
            helper.assertTrue(loner.setGameMode(GameType.CREATIVE) && loner.isCreative(), "joueur de test en Créatif");
            try {
                helper.assertFalse(breakCanceled(level, wild, loner), "Créatif : exempté");
            } finally {
                loner.setGameMode(GameType.SURVIVAL);
            }
            // Contournement administrateur.
            helper.assertTrue(breakCanceled(level, wild, loner), "retour en survie : refusé de nouveau");
            op(server, loner);
            try {
                helper.assertTrue(breakCanceled(level, wild, loner), "opérateur sans contournement : refusé");
                helper.assertTrue(AdminBypass.toggle(loner), "contournement activé");
                helper.assertFalse(breakCanceled(level, wild, loner), "contournement administrateur : exempté");
            } finally {
                if (AdminBypass.isActive(loner)) {
                    AdminBypass.toggle(loner);
                }
                AdminBypass.forget(loner.getUUID());
                deop(server, loner);
            }
            helper.assertTrue(breakCanceled(level, wild, loner), "contournement retiré : refusé de nouveau");
        } finally {
            for (BlockPos pos : List.of(titane, soufre, cryo, stone, wild)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void spaceOresResistExplosions(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos center = helper.absolutePos(new BlockPos(4, 3, 4));
        List<BlockPos> ores = List.of(center.north(), center.south(), center.east());
        BlockPos rock = center.west();
        BlockPos control = center.above();
        try {
            level.setBlock(ores.get(0), ModBlocks.TITANIUM_ORE.get().defaultBlockState(), 3);
            level.setBlock(ores.get(1), ModBlocks.SULFUR_ORE.get().defaultBlockState(), 3);
            level.setBlock(ores.get(2), ModBlocks.CRYOGENIC_ORE.get().defaultBlockState(), 3);
            level.setBlock(rock, Blocks.ANDESITE.defaultBlockState(), 3);
            // Témoin de même résistance (3,0) que les minerais spatiaux : prouve que l'explosion les aurait détruits.
            level.setBlock(control, Blocks.IRON_ORE.defaultBlockState(), 3);
            level.explode(null, center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D, 4.0F, Level.ExplosionInteraction.BLOCK);
            helper.assertTrue(level.getBlockState(rock).isAir(), "roche voisine détruite par l'explosion");
            helper.assertTrue(level.getBlockState(control).isAir(), "témoin de même résistance détruit");
            helper.assertTrue(level.getBlockState(ores.get(0)).is(ModBlocks.TITANIUM_ORE.get())
                    && level.getBlockState(ores.get(1)).is(ModBlocks.SULFUR_ORE.get())
                    && level.getBlockState(ores.get(2)).is(ModBlocks.CRYOGENIC_ORE.get()), "les trois minerais spatiaux ont résisté");
        } finally {
            for (BlockPos pos : List.of(ores.get(0), ores.get(1), ores.get(2), rock, control)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ bordure

    @GameTest(template = AREA, batch = "sf_j5_border")
    public static void softBorderClamp(GameTestHelper helper) {
        int radius = 2000;
        helper.assertTrue(SpaceRules.borderClamp(radius, 0, 0) == null && SpaceRules.borderClamp(radius, 1999, 0) == null
                && SpaceRules.borderClamp(radius, 1000, 1000) == null && SpaceRules.borderClamp(radius, -1414, -1414) == null,
                "dedans : inchangé");
        helper.assertTrue(SpaceRules.borderClamp(radius, 2000, 0) == null && SpaceRules.borderClamp(radius, 0, -2000) == null,
                "sur la bordure : inchangé");
        SpaceRules.Point axis = SpaceRules.borderClamp(radius, 3000, 0);
        helper.assertTrue(axis != null && Math.abs(axis.x() - 1984.0D) < 1e-9 && Math.abs(axis.z()) < 1e-9,
                "sur l'axe : ramené à rayon - 16");
        SpaceRules.Point diagonal = SpaceRules.borderClamp(radius, -2400, 1800);
        helper.assertTrue(diagonal != null, "dehors (distance 3 000)");
        double length = Math.hypot(diagonal.x(), diagonal.z());
        helper.assertTrue(Math.abs(length - 1984.0D) < 1e-9, "à rayon - 16 du centre (lu " + length + ")");
        helper.assertTrue(Math.abs(diagonal.x() * 1800.0D - diagonal.z() * -2400.0D) < 1e-6 && diagonal.x() < 0.0D && diagonal.z() > 0.0D,
                "même direction que le point d'origine");
        helper.assertTrue(SpaceRules.borderClamp(256, 300, 0) != null && SpaceRules.borderClamp(256, 300, 0).x() == 240.0D,
                "plus petit rayon autorisé (256) : ramené à 240");

        // Les rayons des planètes livrées : bordure de 2 000, 3 000 et 2 000 blocs.
        helper.assertTrue(PlanetRegistry.get().byName("lune").radius() == 2000 && PlanetRegistry.get().byName("mars").radius() == 3000
                && PlanetRegistry.get().byName("planete_glacee").radius() == 2000, "rayons publiés");

        // Contrôle périodique d'un joueur sur une planète de test (l'End) : repère de planète, Créatif exempté.
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        helper.assertTrue(end != null, "l'End existe sur le serveur de GameTests");
        ServerPlayer player = FakePlayerFactory.get(end, new GameProfile(UUID.randomUUID(), "sf_j5_explorateur"));
        PlanetDefinition test = J5TestUtil.testPlanet(helper, "essai_bordure", 1000L);
        J5TestUtil.withPlanets(List.of(test), () -> {
            player.moveTo(5000.5D, 100.0D, 0.5D);
            SpaceRules.tickPlayer(player);
            helper.assertTrue(end.dimension().location().equals(SpaceRules.markedPlanet(player)),
                    "repère de planète posé par le contrôle périodique");
            helper.assertTrue(SpaceRules.constrained(player), "joueur de survie : soumis à la bordure");
        });
        SpaceRules.markPlanet(player, null);
        helper.assertTrue(SpaceRules.markedPlanet(player) == null, "repère effacé");
        helper.succeed();
    }

    // ------------------------------------------------------------------ revendication et fondation

    @GameTest(template = AREA, batch = "sf_j5_claim")
    public static void claimAndFoundingOnPlanets(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        NationData data = NationData.get(server);
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_colon");
        Nation nation = J5TestUtil.foundNation(helper, chef, "SfColon");
        ServerLevel end = server.getLevel(Level.END);
        helper.assertTrue(end != null, "l'End existe sur le serveur de GameTests");
        ServerPlayer founder = FakePlayerFactory.get(end, new GameProfile(UUID.randomUUID(), "sf_j5_fondateur"));
        PlanetDefinition moon = J5TestUtil.testPlanet(helper, "lune", 1000L);
        ChunkPos chunk = new ChunkPos(0, 60);
        try {
            NationAdmin.adjustTreasury(server, nation, 10_000L);
            J5TestUtil.withPlanets(List.of(moon), () -> {
                helper.assertTrue(moon.dimension().equals(Level.END), "la planète « lune » de test est liée à l'End");
                ActionResult refused = NationManager.claim(chef, Level.END, chunk);
                J4TestUtil.expect(helper, refused, false, "revendication sur la planète sans dimension.lune");
                helper.assertTrue(translationKey(refused.message()).equals("sovereignfrontier.space.claim.locked"),
                        "motif : space.claim.locked (lu " + translationKey(refused.message()) + ")");
                helper.assertTrue(data.ownerAt(Level.END, chunk.toLong()) == null && nation.treasury() == 10_000L,
                        "rien n'est prélevé ni revendiqué");

                J5TestUtil.complete(helper, nation, "technologie_2");
                long cost = NationManager.claimCost(nation);
                J4TestUtil.expect(helper, NationManager.claim(chef, Level.END, chunk), true, "revendication avec dimension.lune");
                helper.assertTrue(data.ownerAt(Level.END, chunk.toLong()) == nation, "chunk de la planète revendiqué");
                helper.assertTrue(nation.treasury() == 10_000L - cost, "même coût exponentiel que sur Terre (" + cost + ")");

                ActionResult founding = NationManager.create(founder, J4TestUtil.unique(helper, "SfFond"));
                J4TestUtil.expect(helper, founding, false, "fondation sur une planète");
                helper.assertTrue(translationKey(founding.message()).equals("sovereignfrontier.space.found.planet"),
                        "motif : space.found.planet");
                helper.assertTrue(data.nationOf(founder.getUUID()) == null, "aucune nation fondée");
            });
            // Même sans planète publiée, les trois dimensions du mod restent des planètes.
            J5TestUtil.withPlanets(List.of(), () -> {
                helper.assertTrue(SpaceRules.foundBlockReason(SpaceDimensions.LUNE) != null
                        && SpaceRules.foundBlockReason(SpaceDimensions.MARS) != null
                        && SpaceRules.foundBlockReason(SpaceDimensions.PLANETE_GLACEE) != null, "fondation refusée sur les trois");
                helper.assertTrue(SpaceRules.foundBlockReason(Level.OVERWORLD) == null && SpaceRules.foundBlockReason(Level.NETHER) == null,
                        "fondation inchangée ailleurs");
                helper.assertTrue(SpaceRules.claimBlockReason(nation, SpaceDimensions.LUNE) == null,
                        "Lune : dimension.lune suffit (clé déduite de la dimension)");
                helper.assertTrue(translationKey(SpaceRules.claimBlockReason(nation, SpaceDimensions.MARS))
                        .equals("sovereignfrontier.space.claim.locked"), "Mars : dimension.mars manque");
                helper.assertTrue(SpaceRules.claimBlockReason(nation, Level.OVERWORLD) == null
                        && SpaceRules.claimBlockReason(nation, Level.NETHER) == null, "revendication inchangée ailleurs");
            });
        } finally {
            J5TestUtil.disband(chef);
        }
        helper.assertTrue(data.ownerAt(Level.END, chunk.toLong()) == null, "dissolution : chunk de la planète libéré");
        helper.succeed();
    }

    // ------------------------------------------------------------------ lits et ancres

    @GameTest(template = AREA, batch = "sf_j5_beds")
    public static void bedsAndAnchorsRefused(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel end = server.getLevel(Level.END);
        helper.assertTrue(end != null, "l'End existe sur le serveur de GameTests");
        ServerPlayer sleeper = FakePlayerFactory.get(end, new GameProfile(UUID.randomUUID(), "sf_j5_dormeur"));
        ServerPlayer earthling = J5TestUtil.fakePlayer(helper, "sf_j5_terrien");
        PlanetDefinition test = J5TestUtil.testPlanet(helper, "essai_lits", 1000L);

        BlockPos foot = END_STAGE;
        BlockPos head = foot.relative(Direction.SOUTH);
        BlockPos anchor = END_STAGE.east(3);
        BlockPos earthBed = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockState footState = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT).setValue(BedBlock.FACING, Direction.SOUTH);
        try {
            for (int dx = -1; dx <= 5; dx++) {
                for (int dz = -1; dz <= 2; dz++) {
                    end.setBlock(foot.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                }
            }
            end.setBlock(foot, footState, 3);
            end.setBlock(head, footState.setValue(BedBlock.PART, BedPart.HEAD), 3);
            end.setBlock(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 1), 3);
            sleeper.moveTo(foot.getX() + 0.5D, foot.getY() + 1.0D, foot.getZ() - 1.5D);
            // Témoin sur Terre : le lit n'est jamais touché par cette règle.
            helper.getLevel().setBlock(earthBed, footState, 3);

            J5TestUtil.withPlanets(List.of(test), () -> {
                helper.assertTrue(SpaceRules.isSpaceDimension(Level.END), "l'End est la planète de test");
                for (BlockPos pos : List.of(foot, head, anchor)) {
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
                    PlayerInteractEvent.RightClickBlock event = CommonHooks.onRightClickBlock(sleeper, InteractionHand.MAIN_HAND, pos, hit);
                    helper.assertTrue(event.isCanceled(), "utilisation annulée sur la planète : " + end.getBlockState(pos).getBlock().getDescriptionId());
                    InteractionResult result = sleeper.gameMode.useItemOn(sleeper, end, ItemStack.EMPTY, InteractionHand.MAIN_HAND, hit);
                    helper.assertTrue(result == InteractionResult.FAIL, "résultat : échec, sans explosion (lu " + result + ")");
                }
                helper.assertTrue(end.getBlockState(foot).getBlock() instanceof BedBlock && end.getBlockState(head).getBlock() instanceof BedBlock,
                        "le lit est intact (vanilla l'aurait retiré et fait exploser)");
                helper.assertTrue(end.getBlockState(anchor).getBlock() instanceof RespawnAnchorBlock, "l'ancre est intacte");
                helper.assertTrue(!SpaceRules.refusesRespawnBlock(helper.getLevel(), footState), "Terre : lits et ancres non concernés");
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(earthBed), Direction.UP, earthBed, false);
                helper.assertFalse(CommonHooks.onRightClickBlock(earthling, InteractionHand.MAIN_HAND, earthBed, hit).isCanceled(),
                        "témoin : lit de l'Overworld non annulé");
                helper.assertTrue(!SpaceRules.refusesRespawnBlock(end, Blocks.STONE.defaultBlockState()), "seuls lits et ancres sont refusés");
            });
        } finally {
            for (int dx = -1; dx <= 5; dx++) {
                for (int dz = -1; dz <= 2; dz++) {
                    end.setBlock(foot.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), 3);
                    end.setBlock(foot.offset(dx, -1, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
            helper.getLevel().setBlock(earthBed, Blocks.AIR.defaultBlockState(), 3);
            helper.getLevel().setBlock(earthBed.relative(Direction.SOUTH), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ secours

    @GameTest(template = AREA, batch = "sf_j5_rescue")
    public static void loginRescueDecision(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ResourceLocation lune = id("lune");
        ResourceKey<Level> luneLevel = SpaceDimensions.LUNE;
        // Fonction pure : planète disparue du serveur -> point de retour ; présente (ou inconnue) -> rien.
        helper.assertTrue(SpaceRules.loginNeedsRescue(lune, Level.OVERWORLD, dim -> false), "planète absente du serveur : secours");
        helper.assertFalse(SpaceRules.loginNeedsRescue(lune, Level.OVERWORLD, dim -> true), "planète présente : rien");
        helper.assertFalse(SpaceRules.loginNeedsRescue(null, Level.OVERWORLD, dim -> false), "pas de repère de planète : rien");
        helper.assertFalse(SpaceRules.loginNeedsRescue(lune, Level.END, dim -> false), "hors de l'Overworld : rien");
        helper.assertTrue(SpaceRules.loginNeedsRescue(lune, Level.OVERWORLD, dim -> dim.equals(Level.END)), "seul le niveau demandé compte");

        ServerPlayer player = J5TestUtil.fakePlayer(helper, "sf_j5_naufrage");
        ServerPlayer chef = J5TestUtil.fakePlayer(helper, "sf_j5_chef_secours");
        Nation nation = J5TestUtil.foundNation(helper, chef, "SfSecours");
        try {
            // Repère de planète : écrit, relu, effacé.
            helper.assertTrue(SpaceRules.markedPlanet(player) == null, "joueur neuf : aucun repère");
            SpaceRules.markPlanet(player, luneLevel);
            helper.assertTrue(lune.equals(SpaceRules.markedPlanet(player)), "repère écrit : lune");
            SpaceRules.markPlanet(player, Level.END);
            helper.assertTrue(Level.END.location().equals(SpaceRules.markedPlanet(player)), "repère remplacé");

            // Connexion : la Lune n'existe pas sur ce serveur de test, l'End oui.
            helper.assertTrue(server.getLevel(luneLevel) == null && server.getLevel(Level.END) != null, "Lune absente, End présent");
            SpaceRules.markPlanet(player, luneLevel);
            SpaceRules.onLogin(player);
            helper.assertTrue(SpaceRules.markedPlanet(player) == null, "connexion avec une planète disparue : secours, repère effacé");
            SpaceRules.markPlanet(player, Level.END);
            SpaceRules.onLogin(player);
            helper.assertTrue(Level.END.location().equals(SpaceRules.markedPlanet(player)), "connexion avec une planète présente : rien");
            SpaceRules.markPlanet(player, null);

            // Destination : départ de la dernière fusée, sinon noyau national, sinon point d'apparition.
            ServerLevel earth = server.overworld();
            BlockPos free = helper.absolutePos(new BlockPos(2, 2, 2)).offset(160, 0, 160);
            player.setData(ModAttachments.SPACE, SpaceState.EMPTY.withReturnPoint(Optional.of(GlobalPos.of(Level.OVERWORLD, free))));
            SpaceRules.RescueSite home = SpaceRules.rescueSite(player);
            helper.assertTrue(home.target().source() == ReturnPoints.Source.HOME
                    && home.position().x == free.getX() + 0.5D && home.position().z == free.getZ() + 0.5D
                    && home.position().y >= earth.getMinBuildHeight() + 1, "départ terrestre libre : retour au départ");

            BlockPos foreign = helper.absolutePos(BlockPos.ZERO);
            helper.assertTrue(NationData.get(server).ownerAt(Level.OVERWORLD, new ChunkPos(foreign).toLong()) == nation,
                    "le départ ci-dessous est sur le territoire d'une autre nation");
            player.setData(ModAttachments.SPACE, SpaceState.EMPTY.withReturnPoint(Optional.of(GlobalPos.of(Level.OVERWORLD, foreign))));
            SpaceRules.RescueSite spawn = SpaceRules.rescueSite(player);
            BlockPos worldSpawn = earth.getSharedSpawnPos();
            helper.assertTrue(spawn.target().source() == ReturnPoints.Source.SPAWN
                    && spawn.position().x == worldSpawn.getX() + 0.5D && spawn.position().z == worldSpawn.getZ() + 0.5D,
                    "départ chez une autre nation : point d'apparition du monde");

            // Le secours efface le repère (le transfert d'un faux joueur dans le même niveau est sans effet).
            SpaceRules.markPlanet(player, luneLevel);
            SpaceRules.rescue(player);
            helper.assertTrue(SpaceRules.markedPlanet(player) == null, "secours : repère effacé");

            // Commandes d'administration : branchées sur /sfadmin par AdminCommand, réservées aux opérateurs.
            CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
            helper.assertTrue(dispatcher.findNode(List.of("sfadmin", "space", "info")) != null
                    && dispatcher.findNode(List.of("sfadmin", "space", "rescue", "joueur")) != null,
                    "/sfadmin space info et /sfadmin space rescue <joueur> enregistrées");
            try {
                helper.assertTrue(dispatcher.execute("sfadmin space info", server.createCommandSourceStack()) == PlanetRegistry.get().size(),
                        "/sfadmin space info : une ligne par planète publiée");
            } catch (CommandSyntaxException e) {
                throw new IllegalStateException("/sfadmin space info", e);
            }
            helper.assertFalse(dispatcher.findNode(List.of("sfadmin", "space")).canUse(player.createCommandSourceStack()),
                    "commande réservée aux opérateurs");
        } finally {
            player.setData(ModAttachments.SPACE, SpaceState.EMPTY);
            J5TestUtil.disband(chef);
        }
        helper.succeed();
    }
}
