package io.github.theoblaise1.sovereignfrontier.gametest;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.contract.ContractRegistry;
import io.github.theoblaise1.sovereignfrontier.contract.ContractTemplate;
import io.github.theoblaise1.sovereignfrontier.economy.RestrictedItems;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.research.ResearchRegistry;
import io.github.theoblaise1.sovereignfrontier.reward.CrateEntry;
import io.github.theoblaise1.sovereignfrontier.reward.CrateKind;
import io.github.theoblaise1.sovereignfrontier.reward.CrateRegistry;
import io.github.theoblaise1.sovereignfrontier.reward.CrateTable;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GameTests du butin J6 (WP3, docs/conception/J6.md §2.3, §6) : caisse militaire sans palier 3 ni 4, tags
 * {@code restreint/palier_3}, {@code palier_4} et {@code arme_max}, interdictions dans les contrats, table de butin du
 * gardien (données chargées par le serveur) et contrat de mission palier 3. Aucune nation, aucun bloc posé : les tests
 * n'ont rien à nettoyer.
 */
@GameTestHolder(SovereignFrontier.MODID)
@PrefixGameTestTemplate(false)
public final class ButinJ6GameTests {
    private static final String AREA = J6TestUtil.AREA;
    private static final String BATCH = "sf_j6_butin";
    /** Nombre de graines : tirages déterministes (une même table donne toujours le même résultat). */
    private static final int ROLLS = 2000;

    private ButinJ6GameTests() {
    }

    /** Identifiants des objets d'un tag d'objets (vide si le tag n'est pas chargé). */
    private static Set<ResourceLocation> itemsOf(TagKey<Item> tag) {
        Set<ResourceLocation> out = new HashSet<>();
        BuiltInRegistries.ITEM.getTag(tag).ifPresent(set -> {
            for (Holder<Item> holder : set) {
                out.add(BuiltInRegistries.ITEM.getKey(holder.value()));
            }
        });
        return out;
    }

    private static boolean within(int value, int lo, int hi) {
        return value >= lo && value <= hi;
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void militaireSansPalier34(GameTestHelper helper) {
        CrateTable militaire = CrateRegistry.get(CrateKind.MILITAIRE);
        helper.assertTrue(militaire != null, "caisse militaire publiée");
        helper.assertTrue(militaire.bannedItems().isEmpty(),
                "aucun objet de #interdit_caisses dans la caisse militaire : " + militaire.bannedItems());
        for (CrateEntry entry : militaire.entries()) {
            if (entry.reward() instanceof CrateEntry.ItemReward reward) {
                helper.assertFalse(RestrictedItems.isRestricted(reward.item()),
                        reward.item() + " : objet de #restreint dans la caisse militaire");
            }
        }
        helper.succeed();
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void tagsPaliers(GameTestHelper helper) {
        Set<ResourceLocation> tier3 = itemsOf(RestrictedItems.TIER_3);
        helper.assertTrue(tier3.equals(Set.of(ResourceLocation.withDefaultNamespace("trident"))),
                "restreint/palier_3 = minecraft:trident : " + tier3);
        Set<ResourceLocation> tier4 = itemsOf(RestrictedItems.TIER_4);
        helper.assertTrue(tier4.equals(Set.of(ResourceLocation.withDefaultNamespace("totem_of_undying"))),
                "restreint/palier_4 = minecraft:totem_of_undying : " + tier4);
        Set<ResourceLocation> maxWeapon = itemsOf(RestrictedItems.MAX_WEAPON);
        helper.assertTrue(maxWeapon.equals(Set.of(J6TestUtil.id("fusil_precision"))),
                "restreint/arme_max = fusil_precision : " + maxWeapon);
        helper.assertTrue(RestrictedItems.isRestricted(Items.TRIDENT) && RestrictedItems.isRestricted(Items.TOTEM_OF_UNDYING)
                && RestrictedItems.isRestricted(ModItems.SNIPER_RIFLE.get()), "les trois objets sont dans #restreint");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void interdictionsContrat(GameTestHelper helper) {
        helper.assertFalse(RestrictedItems.isBannedFromContracts(Items.TRIDENT), "trident : permis dans un contrat");
        helper.assertFalse(RestrictedItems.isBannedFromContracts(Items.TOTEM_OF_UNDYING), "totem : permis dans un contrat");
        helper.assertTrue(RestrictedItems.isBannedFromContracts(ModItems.SNIPER_RIFLE.get()),
                "fusil de précision : interdit dans un contrat");
        helper.assertTrue(RestrictedItems.allowInContractReward(new ItemStack(Items.TRIDENT), "test J6"),
                "trident : remis par un contrat");
        helper.assertTrue(RestrictedItems.isBannedFromLoot(Items.TRIDENT) && RestrictedItems.isBannedFromLoot(Items.TOTEM_OF_UNDYING),
                "palier 3 et 4 : toujours interdits en caisse, marchand, entreprise et /daily");
        helper.assertTrue(RestrictedItems.isBannedFromLoot(ModItems.SNIPER_RIFLE.get()),
                "fusil de précision : interdit en caisse, marchand, entreprise et /daily");
        helper.succeed();
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void gardienTable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LootTable table = level.getServer().reloadableRegistries()
                .getLootTable(ResourceKey.create(Registries.LOOT_TABLE, J6TestUtil.id("entities/gardien_donjon")));
        helper.assertTrue(table != LootTable.EMPTY, "table de butin publiée : sovereignfrontier:entities/gardien_donjon");
        Zombie zombie = EntityType.ZOMBIE.create(level);
        helper.assertTrue(zombie != null, "zombi de référence (non placé dans le monde)");
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, zombie)
                .withParameter(LootContextParams.ORIGIN, new Vec3(0.5D, 1.0D, 0.5D))
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().generic())
                .create(LootContextParamSets.ENTITY);

        ResourceLocation perforantes = J6TestUtil.id("munitions_perforantes");
        ResourceLocation explosif = J6TestUtil.id("explosif");
        ResourceLocation soins = J6TestUtil.id("trousse_soins");
        ResourceLocation trident = ResourceLocation.withDefaultNamespace("trident");
        ResourceLocation totem = ResourceLocation.withDefaultNamespace("totem_of_undying");
        ResourceLocation fusil = J6TestUtil.id("fusil_precision");
        Set<ResourceLocation> attendus = Set.of(perforantes, explosif, soins, trident, totem, fusil);

        // Nombre de tirages où chaque objet apparaît (sur ROLLS graines).
        Map<ResourceLocation, Integer> presence = new HashMap<>();
        for (long seed = 0; seed < ROLLS; seed++) {
            Map<ResourceLocation, Integer> totals = new HashMap<>();
            for (ItemStack stack : table.getRandomItems(params, seed)) {
                totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(), Integer::sum);
            }
            for (ResourceLocation id : totals.keySet()) {
                helper.assertTrue(attendus.contains(id), "graine " + seed + " : objet inattendu " + id);
                presence.merge(id, 1, Integer::sum);
            }
            int perf = totals.getOrDefault(perforantes, 0);
            helper.assertTrue(perf >= 8 && perf <= 16, "graine " + seed + " : 8 à 16 perforantes (" + perf + ")");
            helper.assertTrue(totals.getOrDefault(explosif, 0) <= 2, "graine " + seed + " : au plus 2 explosifs");
            helper.assertTrue(totals.getOrDefault(soins, 0) <= 1, "graine " + seed + " : au plus 1 trousse");
            helper.assertTrue(totals.getOrDefault(trident, 0) <= 1, "graine " + seed + " : au plus 1 trident");
            helper.assertTrue(totals.getOrDefault(totem, 0) <= 1, "graine " + seed + " : au plus 1 totem");
            helper.assertTrue(totals.getOrDefault(fusil, 0) <= 1, "graine " + seed + " : au plus 1 fusil de précision");
        }
        // Fréquences de §2.3 : fenêtres à ±5 écarts-types autour de la valeur attendue sur ROLLS tirages.
        helper.assertTrue(presence.getOrDefault(perforantes, 0) == ROLLS, "munitions perforantes à chaque ouverture");
        helper.assertTrue(within(presence.getOrDefault(explosif, 0), 900, 1100), "explosif à 50 % : " + presence.get(explosif));
        helper.assertTrue(within(presence.getOrDefault(soins, 0), 900, 1100), "trousse de soins à 50 % : " + presence.get(soins));
        helper.assertTrue(within(presence.getOrDefault(trident, 0), 400, 600), "trident à 25 % : " + presence.get(trident));
        helper.assertTrue(within(presence.getOrDefault(totem, 0), 50, 150), "totem à 5 % : " + presence.get(totem));
        helper.assertTrue(within(presence.getOrDefault(fusil, 0), 130, 270), "fusil de précision à 10 % : " + presence.get(fusil));
        helper.succeed();
    }

    @GameTest(template = AREA, batch = BATCH)
    public static void missionPalier3(GameTestHelper helper) {
        ContractTemplate mission = ContractRegistry.get().get(J6TestUtil.id("mission_trident"));
        helper.assertTrue(mission != null, "modèle publié : sovereignfrontier:mission_trident");
        String problem = ContractRegistry.problem(mission, helper.getLevel().registryAccess(), ResearchRegistry.server());
        helper.assertTrue(problem == null, "modèle valide au chargement : " + problem);
        helper.assertTrue(mission.requires().equals(List.of(J6TestUtil.id("militaire_12"))),
                "exige militaire_12 : " + mission.requires());
        helper.assertTrue(mission.reward().items().size() == 1 && mission.reward().items().get(0).item() == Items.TRIDENT
                && mission.reward().items().get(0).count() == 1, "récompense : un trident (palier 3)");
        helper.assertTrue(mission.reward().keys().isEmpty(), "pas de clé en récompense");
        helper.succeed();
    }
}
