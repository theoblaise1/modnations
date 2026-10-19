package io.github.theoblaise1.sovereignfrontier.gametest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.theoblaise1.sovereignfrontier.SFConfig;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.enterprise.NationEnterprises;
import io.github.theoblaise1.sovereignfrontier.enterprise.Sector;
import io.github.theoblaise1.sovereignfrontier.nation.Nation;
import io.github.theoblaise1.sovereignfrontier.nation.NationManager;
import io.github.theoblaise1.sovereignfrontier.nation.NationPermission;
import io.github.theoblaise1.sovereignfrontier.nation.NationRole;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModEntityTypes;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.reward.CrateBlock;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKeys;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.util.IntRange;
import io.github.theoblaise1.sovereignfrontier.util.Inventories;
import io.github.theoblaise1.sovereignfrontier.util.WeightedPicker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GameTests du socle J4 (WP0) : registres, tags d'interdiction, inventaires « intacts », migration de la permission
 * {@code economie}, revenu passif des habitants libres, utilitaires de tirage et remise de clés.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class FoundationJ4GameTests {
    private static final String AREA = J4TestUtil.AREA;

    private FoundationJ4GameTests() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path);
    }

    @GameTest(template = AREA)
    public static void j4RegistriesPresent(GameTestHelper helper) {
        for (String block : List.of("terminal_bancaire", "comptoir_avant_poste", "caisse_commune", "caisse_militaire",
                "caisse_industrielle", "caisse_spatiale")) {
            helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(id(block)), "bloc " + block + " enregistré");
            helper.assertTrue(BuiltInRegistries.ITEM.containsKey(id(block)), "objet-bloc " + block + " enregistré");
        }
        Set<Item> keys = new HashSet<>();
        Set<Block> blocks = new HashSet<>();
        for (CrateKind kind : CrateKind.values()) {
            Item key = kind.keyItem();
            helper.assertTrue(BuiltInRegistries.ITEM.getKey(key).equals(id("cle_" + kind.id())), "clé du type " + kind.id());
            helper.assertTrue(key.getDefaultMaxStackSize() == 64, "clé " + kind.id() + " en piles de 64");
            helper.assertTrue(kind.block() instanceof CrateBlock crate && crate.kind() == kind, "caisse du type " + kind.id());
            helper.assertTrue(ModBlockEntities.CRATE.get().isValid(kind.block().defaultBlockState()),
                    "bloc-entité caisse valable pour caisse_" + kind.id());
            helper.assertTrue(CrateKind.byId(kind.id()) == kind && CrateKind.byKey(key) == kind
                    && CrateKind.byOrdinal(kind.ordinal()) == kind, "recherche du type " + kind.id());
            keys.add(key);
            blocks.add(kind.block());
        }
        helper.assertTrue(CrateKind.values().length == 4 && keys.size() == 4 && blocks.size() == 4,
                "4 types de caisse distincts (clés et blocs)");
        helper.assertTrue(BuiltInRegistries.ENTITY_TYPE.containsKey(id("marchand"))
                && ModEntityTypes.MERCHANT.get().getCategory() == MobCategory.MISC, "entité marchand (MISC)");
        helper.assertTrue(DefaultAttributes.hasSupplier(ModEntityTypes.MERCHANT.get()), "attributs du marchand enregistrés");
        helper.assertTrue(BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id("comptoir"))
                && BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(id("caisse")), "blocs-entités comptoir et caisse");
        helper.assertTrue(ModBlockEntities.COMPTOIR.get().isValid(ModBlocks.OUTPOST_COUNTER.get().defaultBlockState()),
                "bloc-entité du comptoir");
        BlockPos probe = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.assertTrue(ModBlocks.OUTPOST_COUNTER.get().defaultBlockState().getDestroySpeed(helper.getLevel(), probe) < 0.0F,
                "comptoir incassable");
        helper.assertTrue(Sector.values().length == 4 && Sector.byId("industriel") == Sector.INDUSTRIEL
                && Sector.byOrdinal(4) == null, "4 secteurs d'entreprise");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void bannedTagsBound(GameTestHelper helper) {
        helper.assertTrue(RestrictedItems.isBannedFromLoot(Items.NETHERITE_SWORD), "épée en netherite interdite dans les caisses");
        helper.assertTrue(RestrictedItems.isBannedFromLoot(ModItems.THERMAL_GENERATOR), "générateur thermique interdit dans les caisses");
        helper.assertTrue(RestrictedItems.isBannedFromLoot(Items.COMMAND_BLOCK), "bloc de commande interdit dans les caisses");
        helper.assertFalse(RestrictedItems.isBannedFromLoot(Items.DIAMOND), "diamant autorisé");
        helper.assertFalse(RestrictedItems.isBannedFromLoot(ModItems.KEY_COMMUNE), "clé commune autorisée");
        helper.assertFalse(RestrictedItems.isRestricted(Items.NETHERITE_SWORD), "netherite : hors économie, pas restreinte");
        for (TagKey<Item> tag : List.of(RestrictedItems.OFF_ECONOMY, RestrictedItems.BANNED_FROM_LOOT)) {
            helper.assertTrue(BuiltInRegistries.ITEM.getTag(tag).map(HolderSet::size).orElse(0) > 0, "tag #" + tag.location() + " chargé");
        }
        // J5 : le Module cryogénique remplit #restreint/fusee_finale (donc #restreint) ; les autres sous-tags restent vides.
        for (TagKey<Item> tag : List.of(RestrictedItems.RESTRICTED, RestrictedItems.FINAL_ROCKET)) {
            helper.assertTrue(BuiltInRegistries.ITEM.getTag(tag).map(HolderSet::size).orElse(0) > 0,
                    "#" + tag.location() + " non vide depuis le J5");
        }
        for (TagKey<Item> tag : List.of(RestrictedItems.TIER_3, RestrictedItems.TIER_4, RestrictedItems.MAX_WEAPON)) {
            helper.assertTrue(BuiltInRegistries.ITEM.getTag(tag).map(HolderSet::size).orElse(0) == 0,
                    "#" + tag.location() + " vide au J5");
        }
        helper.assertTrue(RestrictedItems.auditRecipes(helper.getLevel().getServer()).isEmpty(),
                "aucune recette chargée ne produit d'objet restreint");
        helper.assertTrue(RestrictedItems.allowInLoot(new ItemStack(Items.BREAD), "GameTest"), "pain remis");
        helper.assertFalse(RestrictedItems.allowInLoot(new ItemStack(Items.ELYTRA), "GameTest (refus attendu)"), "élytres refusées");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void inventoriesPlainOnly(GameTestHelper helper) {
        ServerPlayer player = J4TestUtil.fakePlayer(helper, "sf_j4_inventaire");
        Vec3 at = helper.absoluteVec(new Vec3(4.5D, 2.0D, 4.5D));
        player.moveTo(at.x, at.y, at.z);
        Inventory inv = player.getInventory();
        inv.clearContent();
        try {
            ItemStack renamed = new ItemStack(Items.COBBLESTONE, 5);
            renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Pavé précieux"));
            ItemStack damaged = new ItemStack(Items.IRON_SWORD);
            damaged.setDamageValue(5);
            ItemStack enchanted = new ItemStack(Items.IRON_SWORD);
            enchanted.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.SHARPNESS), 1);
            inv.items.set(0, new ItemStack(Items.COBBLESTONE, 10));
            inv.items.set(1, renamed);
            inv.items.set(2, damaged);
            inv.items.set(3, new ItemStack(Items.IRON_SWORD));
            inv.items.set(4, enchanted);
            inv.offhand.set(0, new ItemStack(Items.COBBLESTONE, 3));
            inv.armor.set(0, new ItemStack(Items.COBBLESTONE, 7));

            helper.assertFalse(Inventories.isPlain(renamed) || Inventories.isPlain(damaged) || Inventories.isPlain(enchanted),
                    "objets renommés, abîmés ou enchantés non intacts");
            helper.assertTrue(Inventories.countPlain(player, Items.COBBLESTONE) == 13,
                    "pavés intacts : inventaire + main secondaire, sans armure ni pile renommée ("
                            + Inventories.countPlain(player, Items.COBBLESTONE) + ")");
            helper.assertTrue(Inventories.countPlain(player, Items.IRON_SWORD) == 1, "une seule épée intacte");

            helper.assertTrue(Inventories.consumePlain(player, Items.COBBLESTONE, 12) == 12, "retrait exact de 12");
            helper.assertTrue(Inventories.countPlain(player, Items.COBBLESTONE) == 1 && inv.offhand.get(0).getCount() == 1,
                    "inventaire principal vidé d'abord, puis main secondaire");
            helper.assertTrue(renamed.getCount() == 5 && inv.armor.get(0).getCount() == 7, "pile renommée et armure intactes");
            helper.assertTrue(Inventories.consumePlain(player, Items.COBBLESTONE, 5) == 1, "retrait borné à ce qui existe");
            helper.assertTrue(Inventories.consumePlain(player, Items.IRON_SWORD, 1) == 1 && damaged.getCount() == 1
                    && enchanted.getCount() == 1, "seule l'épée intacte est retirée");

            // Inventaire plein : la remise tombe au sol, réservée au joueur.
            inv.clearContent();
            for (int i = 0; i < inv.items.size(); i++) {
                inv.items.set(i, new ItemStack(Items.STONE, 64));
            }
            helper.assertFalse(Inventories.canFit(player, new ItemStack(Items.DIAMOND, 3)), "inventaire plein");
            helper.assertTrue(Inventories.giveOrDrop(player, new ItemStack(Items.DIAMOND, 3)) == 3, "3 diamants lâchés");
            List<ItemEntity> drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    player.getBoundingBox().inflate(4.0D), e -> e.getItem().is(Items.DIAMOND));
            helper.assertTrue(drops.size() == 1 && drops.get(0).getItem().getCount() == 3
                    && player.getUUID().equals(drops.get(0).getTarget()), "diamants au sol, réservés au joueur");
            drops.forEach(Entity::discard);

            inv.items.set(5, new ItemStack(Items.DIAMOND, 60));
            helper.assertTrue(Inventories.canFit(player, new ItemStack(Items.DIAMOND, 4)), "complète une pile existante");
            helper.assertFalse(Inventories.canFit(player, List.of(new ItemStack(Items.DIAMOND, 4), new ItemStack(Items.DIAMOND, 1))),
                    "deux piles qui ne tiennent pas ensemble");
            helper.assertTrue(Inventories.giveOrDrop(player, new ItemStack(Items.DIAMOND, 4)) == 0
                    && inv.items.get(5).getCount() == 64, "remise dans l'inventaire sans chute");
        } finally {
            inv.clearContent();
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void permissionMigrationAddsEconomy(GameTestHelper helper) {
        NationPermission eco = NationPermission.ECONOMY;
        helper.assertTrue(eco.ordinal() == 11 && eco.bit() == 1 << 11 && NationPermission.values().length == 12,
                "« economie » ajoutée en dernier (bit 11)");
        helper.assertTrue(NationPermission.byId("economie").orElse(null) == eco, "identifiant « economie »");
        helper.assertTrue(NationPermission.defaults(NationRole.MINISTRE).contains(eco)
                && NationPermission.defaults(NationRole.OFFICIER).contains(eco)
                && !NationPermission.defaults(NationRole.CITOYEN).contains(eco)
                && !NationPermission.defaults(NationRole.RECRUE).contains(eco), "défauts : ministre et officier");

        EnumSet<NationPermission> saved = EnumSet.of(NationPermission.CLAIM, NationPermission.BUILD);
        EnumSet<NationPermission> ministre = NationPermission.migrate(NationRole.MINISTRE, saved, 0);
        helper.assertTrue(ministre.contains(eco) && ministre.containsAll(saved) && ministre.size() == 3,
                "version 0 : « economie » ajoutée au ministre, choix existants conservés");
        helper.assertTrue(NationPermission.migrate(NationRole.OFFICIER, EnumSet.noneOf(NationPermission.class), 0)
                .equals(EnumSet.of(eco)), "version 0 : ajoutée à l'officier");
        helper.assertFalse(NationPermission.migrate(NationRole.CITOYEN, saved, 0).contains(eco), "citoyen : rien d'ajouté");
        helper.assertFalse(NationPermission.migrate(NationRole.MINISTRE, saved, NationPermission.CURRENT_VERSION).contains(eco),
                "version courante : un retrait décidé par le chef est conservé");
        helper.assertTrue(saved.equals(EnumSet.of(NationPermission.CLAIM, NationPermission.BUILD)), "fonction pure");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_free_income")
    public static void freeInhabitantsIncome(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_revenu");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfRevenu");
        try {
            long perInhabitant = SFConfig.INCOME_PER_INHABITANT.get();
            J4TestUtil.addInhabitants(helper, nation, 2);
            helper.assertTrue(nation.effectivePopulation() == 2 && NationManager.freeInhabitants(nation) == 2,
                    "2 bâtiments : 2 habitants libres");
            helper.assertTrue(NationManager.incomePerCycle(nation) == 2 * perInhabitant,
                    "2 habitants libres : " + 2 * perInhabitant + " cr (obtenu " + NationManager.incomePerCycle(nation) + ")");

            nation.enterprises().setWorkers(Sector.AGRICOLE, 1);
            helper.assertTrue(NationManager.freeInhabitants(nation) == 1 && NationManager.incomePerCycle(nation) == perInhabitant,
                    "1 habitant affecté quitte le revenu passif : " + perInhabitant + " cr");

            nation.enterprises().setWorkers(Sector.MINIER, 5);
            helper.assertTrue(NationManager.freeInhabitants(nation) == 0 && NationManager.incomePerCycle(nation) == 0L,
                    "plus d'affectés que d'habitants : aucun habitant libre, jamais négatif");
        } finally {
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void enterprisesNbtRoundTrip(GameTestHelper helper) {
        NationEnterprises e = new NationEnterprises();
        e.setWorkers(Sector.MINIER, 3);
        e.setWorkers(Sector.INDUSTRIEL, 2);
        e.setWorkers(Sector.AGRICOLE, -4);
        e.setFraction("minecraft:raw_iron", 0.4D);
        e.setFraction("minecraft:diamond", 0.0D);
        e.setLast(12L, 7, 1);
        CompoundTag tag = e.save();
        NationEnterprises back = NationEnterprises.load(tag);
        helper.assertTrue(back.workers(Sector.MINIER) == 3 && back.workers(Sector.INDUSTRIEL) == 2
                && back.workers(Sector.AGRICOLE) == 0 && back.assignedTotal() == 5, "habitants affectés relus");
        helper.assertTrue(back.fractions().equals(Map.of("minecraft:raw_iron", 0.4D)), "fractions relues (nulles omises)");
        helper.assertTrue(back.lastMoney() == 12L && back.lastItems() == 7 && back.lastLost() == 1, "bilan du dernier cycle");
        helper.assertTrue(NationEnterprises.load(new CompoundTag()).assignedTotal() == 0, "NBT absent : aucune affectation");
        helper.succeed();
    }

    @GameTest(template = AREA)
    public static void utilRangesAndPicker(GameTestHelper helper) {
        helper.assertTrue(parseRange("5").equals(IntRange.of(5)), "« 5 » → [5, 5]");
        helper.assertTrue(parseRange("[2, 7]").equals(IntRange.of(2, 7)), "« [2, 7] »");
        helper.assertTrue(IntRange.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[7, 2]")).error().isPresent(),
                "min > max refusé");
        helper.assertTrue(IntRange.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("[1, 2, 3]")).error().isPresent(),
                "trois valeurs refusées");
        helper.assertTrue(IntRange.codec(0, 10).parse(JsonOps.INSTANCE, JsonParser.parseString("[5, 20]")).error().isPresent(),
                "hors bornes refusé");
        helper.assertTrue(IntRange.CODEC.encodeStart(JsonOps.INSTANCE, IntRange.of(3)).getOrThrow().getAsInt() == 3,
                "plage constante réécrite « 3 »");
        RandomSource random = RandomSource.create(7L);
        IntRange range = IntRange.of(2, 4);
        for (int i = 0; i < 200; i++) {
            helper.assertTrue(range.contains(range.sample(random)), "tirage dans la plage");
        }

        record Entry(String name, int weight) {
        }
        List<Entry> entries = List.of(new Entry("a", 1), new Entry("b", 0), new Entry("c", 5), new Entry("d", 2));
        List<Entry> first = WeightedPicker.pickWithoutReplacement(entries, Entry::weight, 10, RandomSource.create(42L));
        List<Entry> again = WeightedPicker.pickWithoutReplacement(entries, Entry::weight, 10, RandomSource.create(42L));
        helper.assertTrue(first.size() == 3 && new HashSet<>(first).size() == 3 && !first.contains(entries.get(1)),
                "sans remise, poids nul jamais tiré : " + first);
        helper.assertTrue(first.equals(again), "même graine, même tirage");
        helper.assertTrue(WeightedPicker.pick(List.of(new Entry("z", 0)), Entry::weight, random) == null, "aucun poids positif");
        int rare = 0;
        List<Entry> skewed = List.of(new Entry("rare", 1), new Entry("courant", 1_000_000));
        for (int i = 0; i < 1000; i++) {
            if (WeightedPicker.pick(skewed, Entry::weight, random).name().equals("rare")) {
                rare++;
            }
        }
        helper.assertTrue(rare <= 2, "poids 1 contre 10⁶ : quasi jamais tiré (" + rare + ")");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = "sf_j4_keys")
    public static void crateKeysToNationChest(GameTestHelper helper) {
        ServerPlayer chef = J4TestUtil.fakePlayer(helper, "sf_j4_cles");
        Nation nation = J4TestUtil.foundNation(helper, chef, "SfCles");
        try {
            chef.getInventory().clearContent();
            // Une seule case libre dans le coffre national.
            for (int i = 0; i < Nation.CHEST_SIZE - 1; i++) {
                nation.chest().setItem(i, new ItemStack(Items.STONE, 64));
            }
            int lost = CrateKeys.giveToNation(helper.getLevel().getServer(), nation, CrateKind.MILITAIRE, 70, chef);
            helper.assertTrue(lost == 0, "rien de perdu avec un joueur de repli");
            helper.assertTrue(nation.chest().getItem(Nation.CHEST_SIZE - 1).is(ModItems.KEY_MILITAIRE.get())
                    && nation.chest().getItem(Nation.CHEST_SIZE - 1).getCount() == 64, "64 clés dans la case libre du coffre");
            helper.assertTrue(Inventories.countPlain(chef, ModItems.KEY_MILITAIRE.get()) == 6, "6 clés au joueur de repli");
            helper.assertTrue(CrateKeys.giveToNation(helper.getLevel().getServer(), nation, CrateKind.COMMUNE, 3, null) == 3,
                    "coffre plein sans repli : 3 clés perdues");
            helper.assertTrue(CrateKeys.give(chef, CrateKind.SPATIALE, 2) == 0
                    && Inventories.countPlain(chef, ModItems.KEY_SPATIALE.get()) == 2, "remise directe au joueur");
        } finally {
            chef.getInventory().clearContent();
            J4TestUtil.disband(chef);
        }
        helper.succeed();
    }

    private static IntRange parseRange(String json) {
        return IntRange.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}
