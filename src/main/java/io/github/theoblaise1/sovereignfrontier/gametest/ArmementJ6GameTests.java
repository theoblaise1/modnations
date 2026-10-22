package io.github.theoblaise1.sovereignfrontier.gametest;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringManager;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRecipe;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRegistry;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringTab;
import io.github.theoblaise1.sovereignfrontier.nation.ActionResult;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition.ItemCost;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import javax.annotation.Nullable;

/**
 * GameTests de l'armement du J6 (docs/conception/J6.md §6, WP2) : déblocages, munitions, cadence, portée, dégâts, épargne
 * des joueurs, recettes. Chaque test a son lot ({@code sf_j6_armement_<test>}) : les tests s'exécutent l'un après l'autre,
 * aucun monstre d'un autre test ne se trouve sur la trajectoire.
 * <p>
 * La piste de tir ({@link #floor}) dépasse le gabarit de 9 blocs (portée de 24, cible hors portée à 26) : tout ce qui sert au
 * test (tireur, cibles, voisin) est dans la piste dégagée, et les monstres déjà présents y sont retirés d'abord. Le tireur
 * regarde vers l'est ; son œil (sol + 1,62) reste dans l'air. Les zombis des tests de dégâts n'ont pas d'armure : les
 * dégâts sont alors exacts (l'armure elle-même reste vanilla).
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class ArmementJ6GameTests {
    private static final String AREA = "gametest/empty9";
    /** Longueur de la piste (blocs) : la portée du pistolet (24) et une cible à 26 blocs. */
    private static final int LANE = 30;
    /** Tireur au centre du bloc x = 1 et z = 4 : la piste va de z = 2 à z = 6. */
    private static final double MUZZLE_X = 1.5D;
    private static final double MUZZLE_Z = 4.5D;
    /** Pieds du tireur : le sol de pierre est en y = 1 (relatif à l'origine), donc les pieds sont en y = 2. */
    private static final double FEET_Y = 2.0D;
    private static final float ZOMBIE_HEALTH = 20.0F;

    private ArmementJ6GameTests() {
    }

    /** Sans nation, puis nation sans le déblocage du pistolet : refus sans effet. Une fois débloqué, le même tir touche. */
    @GameTest(template = AREA, batch = "sf_j6_armement_locked")
    public static void pistolRefusedWithoutNationOrUnlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepare(helper);
        ServerPlayer outsider = J6TestUtil.fakePlayer(helper, "sf_sans_nation");
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, "sf_tireur");
        try {
            equip(outsider, origin, ModItems.PISTOL.get(), ModItems.AMMO_BASIC.get(), 5);
            Zombie zombie = unarmoredZombie(helper, level, origin, 6.0D);
            fire(helper, outsider);
            helper.assertTrue(same(zombie.getHealth(), ZOMBIE_HEALTH), "sans nation : aucun dégât");
            helper.assertTrue(countOf(outsider.getInventory(), ModItems.AMMO_BASIC.get()) == 5,
                    "sans nation : aucune munition consommée");

            Nation nation = J6TestUtil.foundNation(helper, chef, "ArmeVerrou", "militaire_1", "militaire_2");
            equip(chef, origin, ModItems.PISTOL.get(), ModItems.AMMO_BASIC.get(), 5);
            fire(helper, chef);
            helper.assertTrue(same(zombie.getHealth(), ZOMBIE_HEALTH), "pistolet non débloqué : aucun dégât");
            helper.assertTrue(countOf(chef.getInventory(), ModItems.AMMO_BASIC.get()) == 5,
                    "pistolet non débloqué : munition conservée");
            helper.assertFalse(chef.getCooldowns().isOnCooldown(ModItems.PISTOL.get()),
                    "pistolet non débloqué : aucune cadence consommée");

            J5TestUtil.complete(helper, nation, "militaire_3");
            fire(helper, chef);
            helper.assertTrue(same(zombie.getHealth(), 14.0F), "témoin : pistolet débloqué, le tir touche (6 de dégâts)");
        } finally {
            clear(level, origin);
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /** Sans munition compatible : aucun dégât, aucune cadence consommée. */
    @GameTest(template = AREA, batch = "sf_j6_armement_noammo")
    public static void shotWithoutAmmoIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepare(helper);
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, "sf_tireur");
        try {
            J6TestUtil.foundNation(helper, chef, "ArmeSansMun", "militaire_1", "militaire_2", "militaire_3");
            equip(chef, origin, ModItems.PISTOL.get(), null, 0);
            Zombie zombie = unarmoredZombie(helper, level, origin, 6.0D);
            fire(helper, chef);
            helper.assertTrue(same(zombie.getHealth(), ZOMBIE_HEALTH), "sans munition : aucun dégât");
            helper.assertFalse(chef.getCooldowns().isOnCooldown(ModItems.PISTOL.get()),
                    "sans munition : aucune cadence consommée");
        } finally {
            clear(level, origin);
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /** Un tir retire une munition et blesse le zombi de la portée ; un second tir immédiat est bloqué par la cadence. */
    @GameTest(template = AREA, batch = "sf_j6_armement_hit")
    public static void shotConsumesOneAmmoAndHitsZombieInRange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepare(helper);
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, "sf_tireur");
        try {
            J6TestUtil.foundNation(helper, chef, "ArmeTouche", "militaire_1", "militaire_2", "militaire_3");
            equip(chef, origin, ModItems.PISTOL.get(), ModItems.AMMO_BASIC.get(), 5);
            Zombie zombie = unarmoredZombie(helper, level, origin, 6.0D);
            fire(helper, chef);
            helper.assertTrue(same(zombie.getHealth(), 14.0F), "tir : 6 de dégâts au zombi (PV " + zombie.getHealth() + ")");
            helper.assertTrue(countOf(chef.getInventory(), ModItems.AMMO_BASIC.get()) == 4,
                    "tir : exactement une munition consommée");
            helper.assertTrue(chef.getCooldowns().isOnCooldown(ModItems.PISTOL.get()), "tir : cadence engagée");

            fire(helper, chef);
            helper.assertTrue(same(zombie.getHealth(), 14.0F), "cadence : second tir immédiat sans effet");
            helper.assertTrue(countOf(chef.getInventory(), ModItems.AMMO_BASIC.get()) == 4,
                    "cadence : aucune munition consommée pendant la recharge");
        } finally {
            clear(level, origin);
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /** Zombi à 26 blocs, au-delà de la portée du pistolet (24) : intact. Le tir manqué consomme tout de même une munition. */
    @GameTest(template = AREA, batch = "sf_j6_armement_range")
    public static void zombieBeyondRangeIsNotHit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepare(helper);
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, "sf_tireur");
        try {
            J6TestUtil.foundNation(helper, chef, "ArmeHorsPortee", "militaire_1", "militaire_2", "militaire_3");
            equip(chef, origin, ModItems.PISTOL.get(), ModItems.AMMO_BASIC.get(), 5);
            Zombie zombie = unarmoredZombie(helper, level, origin, 26.0D);
            fire(helper, chef);
            helper.assertTrue(same(zombie.getHealth(), ZOMBIE_HEALTH), "hors portée : zombi intact");
            helper.assertTrue(countOf(chef.getInventory(), ModItems.AMMO_BASIC.get()) == 4,
                    "hors portée : le tir manqué consomme une munition");
        } finally {
            clear(level, origin);
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /** Munitions perforantes : 6 × 1,5 = 9 de dégâts, zombi à 11 PV. */
    @GameTest(template = AREA, batch = "sf_j6_armement_piercing")
    public static void piercingAmmoDealsOneAndAHalfDamage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepare(helper);
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, "sf_tireur");
        try {
            J6TestUtil.foundNation(helper, chef, "ArmePerfore", "militaire_1", "militaire_3", "militaire_7");
            equip(chef, origin, ModItems.PISTOL.get(), ModItems.AMMO_PIERCING.get(), 5);
            Zombie zombie = unarmoredZombie(helper, level, origin, 6.0D);
            fire(helper, chef);
            helper.assertTrue(same(zombie.getHealth(), 11.0F),
                    "perforantes : 9 de dégâts (×1,5), zombi à 11 PV (PV " + zombie.getHealth() + ")");
            helper.assertTrue(countOf(chef.getInventory(), ModItems.AMMO_PIERCING.get()) == 4,
                    "perforantes : une munition consommée");
        } finally {
            clear(level, origin);
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /**
     * Un joueur sur la trajectoire n'est jamais touché : il est dans le monde (le rayon doit pouvoir le rencontrer) et le
     * rayon ne vise que les monstres, si bien que le zombi derrière est touché.
     */
    @GameTest(template = AREA, batch = "sf_j6_armement_voisin")
    public static void neighbourPlayerOnTheLineIsNotHit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = prepare(helper);
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, "sf_tireur");
        ServerPlayer neighbour = J6TestUtil.fakePlayer(helper, "sf_voisin");
        try {
            J6TestUtil.foundNation(helper, chef, "ArmeVoisin", "militaire_1", "militaire_2", "militaire_3");
            equip(chef, origin, ModItems.PISTOL.get(), ModItems.AMMO_BASIC.get(), 5);
            place(neighbour, origin, 3.0D);
            level.addFreshEntity(neighbour);
            Zombie zombie = unarmoredZombie(helper, level, origin, 6.0D);
            float before = neighbour.getHealth();
            fire(helper, chef);
            helper.assertTrue(same(zombie.getHealth(), 14.0F), "le zombi derrière le voisin est touché");
            helper.assertTrue(same(neighbour.getHealth(), before), "le joueur sur la trajectoire n'est pas touché");
        } finally {
            neighbour.discard();
            clear(level, origin);
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /** Le fusil de précision n'a aucune recette (ni résultat, ni ingrédient) ; la trousse de soins non plus. */
    @GameTest(template = AREA, batch = "sf_j6_armement_recettes")
    public static void sniperRifleAndKitHaveNoRecipe(GameTestHelper helper) {
        boolean pistolRecipe = false;
        for (EngineeringRecipe recipe : EngineeringRegistry.server().all()) {
            Item result = recipe.definition().result().item();
            helper.assertFalse(result == ModItems.SNIPER_RIFLE.get(), "fusil de précision sans recette : " + recipe.id());
            helper.assertFalse(result == ModItems.HEALING_KIT.get(), "trousse de soins sans recette : " + recipe.id());
            for (ItemCost cost : recipe.definition().ingredients()) {
                helper.assertFalse(cost.item() == ModItems.SNIPER_RIFLE.get(),
                        "fusil de précision absent des ingrédients : " + recipe.id());
            }
            pistolRecipe |= result == ModItems.PISTOL.get();
        }
        helper.assertTrue(pistolRecipe, "témoin : la recette du pistolet est chargée, le parcours n'est pas vide");
        helper.succeed();
    }

    /** La clé de donjon se fabrique à la Station d'ingénierie, onglet Armement (militaire_1), sans autre déblocage. */
    @GameTest(template = AREA, batch = "sf_j6_armement_cle")
    public static void dungeonKeyIsCraftableInArmamentTab(GameTestHelper helper) {
        ServerPlayer chef = J6TestUtil.fakePlayer(helper, "sf_forge");
        try {
            J6TestUtil.foundNation(helper, chef, "CleDonjon", "militaire_1");
            EngineeringRecipe recipe = null;
            for (EngineeringRecipe candidate : EngineeringRegistry.server().all()) {
                if (candidate.definition().result().item() == ModItems.DUNGEON_KEY.get()) {
                    recipe = candidate;
                }
            }
            helper.assertTrue(recipe != null, "recette de la clé de donjon présente");
            helper.assertTrue(recipe.definition().tab() == EngineeringTab.ARMEMENT, "clé de donjon : onglet Armement");
            helper.assertTrue(recipe.definition().unlock().isBlank(), "clé de donjon : aucun déblocage supplémentaire");

            chef.getInventory().add(new ItemStack(Items.IRON_INGOT, 4));
            chef.getInventory().add(new ItemStack(Items.GUNPOWDER, 2));
            ActionResult result = EngineeringManager.craftUnchecked(chef, recipe.id());
            J4TestUtil.expect(helper, result, true, "fabrication de la clé de donjon");
            helper.assertTrue(countOf(chef.getInventory(), ModItems.DUNGEON_KEY.get()) == 1, "une clé de donjon fabriquée");
            helper.assertTrue(countOf(chef.getInventory(), Items.IRON_INGOT) == 0
                    && countOf(chef.getInventory(), Items.GUNPOWDER) == 0, "ingrédients consommés");
        } finally {
            J6TestUtil.disband(chef);
        }
        helper.succeed();
    }

    /**
     * Piste de tir : monstres présents retirés, puis sol de pierre en y = 1 et air de y = 2 à 6, sur x de 0 à {@link #LANE}
     * et z de 2 à 6 (relatifs à l'origine absolue de la zone). Renvoie cette origine.
     */
    private static BlockPos prepare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        // La piste traverse plusieurs chunks (jusqu'à 31 blocs) : sans chargement, setBlock et addFreshEntity échouent sans bruit.
        for (int chunkX = origin.getX() >> 4; chunkX <= (origin.getX() + LANE + 1) >> 4; chunkX++) {
            for (int chunkZ = (origin.getZ() + 2) >> 4; chunkZ <= (origin.getZ() + 6) >> 4; chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        clear(level, origin);
        floor(level, origin);
        return origin;
    }

    private static void floor(ServerLevel level, BlockPos origin) {
        for (int x = 0; x <= LANE; x++) {
            for (int z = 2; z <= 6; z++) {
                level.setBlock(origin.offset(x, 1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 2; y <= 6; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
    }

    /** Retire les monstres de la piste (tests précédents, zombis oubliés). */
    private static void clear(ServerLevel level, BlockPos origin) {
        AABB lane = new AABB(origin.getX(), origin.getY() + 1, origin.getZ() + 2,
                origin.getX() + LANE + 1, origin.getY() + 7, origin.getZ() + 7);
        for (Monster monster : level.getEntitiesOfClass(Monster.class, lane)) {
            monster.discard();
        }
    }

    /** Joueur placé à {@code dx} blocs du tireur, face à l'est (yaw -90°). */
    private static void place(ServerPlayer player, BlockPos origin, double dx) {
        player.moveTo(origin.getX() + MUZZLE_X + dx, origin.getY() + FEET_Y, origin.getZ() + MUZZLE_Z, -90.0F, 0.0F);
    }

    /** Tireur placé sur la piste, arme en main principale, munitions dans l'inventaire (sauf si {@code ammo} est nul). */
    private static void equip(ServerPlayer player, BlockPos origin, Item weapon, @Nullable Item ammo, int count) {
        place(player, origin, 0.0D);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(weapon));
        if (ammo != null && count > 0) {
            player.getInventory().add(new ItemStack(ammo, count));
        }
    }

    /** Clic droit serveur : {@code Item#use} de l'arme tenue en main principale. */
    private static void fire(GameTestHelper helper, ServerPlayer shooter) {
        // Le regard est fixé ici : la rotation posée par moveTo n'est pas celle qui sert au tir (trace du run 2).
        shooter.setYRot(-90.0F);
        shooter.setXRot(0.0F);
        shooter.setYHeadRot(-90.0F);
        ItemStack weapon = shooter.getMainHandItem();
        weapon.getItem().use(helper.getLevel(), shooter, InteractionHand.MAIN_HAND);
    }

    /** Zombi posé dans la piste, à {@code distance} blocs du tireur (aucun tick ne passe avant les vérifications). */
    private static Zombie zombie(GameTestHelper helper, ServerLevel level, BlockPos origin, double distance) {
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombi créé");
        zombie.moveTo(origin.getX() + MUZZLE_X + distance, origin.getY() + FEET_Y, origin.getZ() + MUZZLE_Z, 0.0F, 0.0F);
        level.addFreshEntity(zombie);
        return zombie;
    }

    private static Zombie unarmoredZombie(GameTestHelper helper, ServerLevel level, BlockPos origin, double distance) {
        Zombie zombie = zombie(helper, level, origin, distance);
        AttributeInstance armor = zombie.getAttribute(Attributes.ARMOR);
        helper.assertTrue(armor != null, "attribut d'armure du zombi");
        armor.setBaseValue(0.0D);
        return zombie;
    }

    private static int countOf(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static boolean same(float actual, float expected) {
        return Math.abs(actual - expected) < 0.01F;
    }
}
