package io.github.theoblaise1.sovereignfrontier.economy;

import com.mojang.logging.LogUtils;
import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRecipe;
import io.github.theoblaise1.sovereignfrontier.engineering.EngineeringRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ItemLike;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Interdictions absolues de l'économie (tags d'objets du datapack) :
 * <ul>
 *   <li>{@link #RESTRICTED} ({@code #sovereignfrontier:restreint}) : objets de palier 3/4, composants finaux de
 *       fusée, armes militaires maximales. Jamais dans un craft (vanilla ou ingénierie), une caisse, une vente de
 *       marchand, un produit d'entreprise ni {@code /daily} ; seules les récompenses de contrat (et, au J6, le
 *       butin de donjon) peuvent en contenir ;</li>
 *   <li>{@link #BANNED_FROM_LOOT} ({@code #sovereignfrontier:interdit_caisses} = restreint + hors_economie) :
 *       interdit dans les caisses, les ventes des marchands, les produits d'entreprise et {@code /daily}.</li>
 * </ul>
 * Les prédicats lisent les tags liés au registre : valables après le premier {@code TagsUpdatedEvent}.
 */
public final class RestrictedItems {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final TagKey<Item> RESTRICTED = tag("restreint");
    public static final TagKey<Item> TIER_3 = tag("restreint/palier_3");
    public static final TagKey<Item> TIER_4 = tag("restreint/palier_4");
    public static final TagKey<Item> FINAL_ROCKET = tag("restreint/fusee_finale");
    public static final TagKey<Item> MAX_WEAPON = tag("restreint/arme_max");
    public static final TagKey<Item> OFF_ECONOMY = tag("hors_economie");
    public static final TagKey<Item> BANNED_FROM_LOOT = tag("interdit_caisses");

    private RestrictedItems() {
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(SovereignFrontier.MODID, path));
    }

    /** Objet de {@code #restreint} : interdit dans tout craft et toute source automatique. */
    public static boolean isRestricted(ItemLike item) {
        return BuiltInRegistries.ITEM.wrapAsHolder(item.asItem()).is(RESTRICTED);
    }

    public static boolean isRestricted(ItemStack stack) {
        return !stack.isEmpty() && stack.is(RESTRICTED);
    }

    /** Objet de {@code #interdit_caisses} : jamais dans une caisse, un étal, une entreprise ou {@code /daily}. */
    public static boolean isBannedFromLoot(ItemLike item) {
        return BuiltInRegistries.ITEM.wrapAsHolder(item.asItem()).is(BANNED_FROM_LOOT);
    }

    public static boolean isBannedFromLoot(ItemStack stack) {
        return !stack.isEmpty() && stack.is(BANNED_FROM_LOOT);
    }

    /**
     * Garde d'exécution avant de remettre un objet issu d'une source automatique : faux (et une ERREUR au
     * journal) si l'objet est interdit ; l'appelant ne le remet pas.
     *
     * @param source description de la source pour le journal (« caisse sovereignfrontier:militaire »)
     */
    public static boolean allowInLoot(ItemStack stack, String source) {
        if (!isBannedFromLoot(stack)) {
            return true;
        }
        LOGGER.error("[Économie] Objet interdit {} non remis ({}) : il appartient à #{}",
                BuiltInRegistries.ITEM.getKey(stack.getItem()), source, BANNED_FROM_LOOT.location());
        return false;
    }

    /**
     * Audit des recettes chargées (vanilla, datapacks et Station d'ingénierie) : chaque recette qui produit un objet
     * de {@code #restreint} est signalée par une ERREUR au journal, sans être supprimée. Appelé au démarrage du
     * serveur et après chaque {@code /reload}.
     *
     * @return identifiants des recettes fautives
     */
    public static List<ResourceLocation> auditRecipes(MinecraftServer server) {
        List<ResourceLocation> offending = new ArrayList<>();
        RegistryAccess access = server.registryAccess();
        int checked = 0;
        for (RecipeHolder<?> holder : server.getRecipeManager().getRecipes()) {
            ItemStack result;
            try {
                result = holder.value().getResultItem(access);
            } catch (RuntimeException e) {
                // Recette spéciale sans résultat fixe : rien à auditer.
                continue;
            }
            checked++;
            if (isRestricted(result)) {
                LOGGER.error("[Économie] La recette {} produit {}, objet de #{} : retirez-la du datapack",
                        holder.id(), BuiltInRegistries.ITEM.getKey(result.getItem()), RESTRICTED.location());
                offending.add(holder.id());
            }
        }
        for (EngineeringRecipe recipe : EngineeringRegistry.server().all()) {
            checked++;
            Item result = recipe.definition().result().item();
            if (isRestricted(result)) {
                LOGGER.error("[Économie] La recette d'ingénierie {} produit {}, objet de #{} : retirez-la du datapack",
                        recipe.id(), BuiltInRegistries.ITEM.getKey(result), RESTRICTED.location());
                offending.add(recipe.id());
            }
        }
        if (offending.isEmpty()) {
            LOGGER.info("[Économie] Audit des recettes : {} recette(s) vérifiée(s), aucune ne produit d'objet restreint", checked);
        }
        return offending;
    }
}
