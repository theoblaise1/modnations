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
 *       butin de donjon) peuvent en contenir. Dans un contrat, sont permis {@link #FINAL_ROCKET} (J5) et
 *       {@link #TIER_3}, {@link #TIER_4} (J6) ({@link #isBannedFromContracts}) ;</li>
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
     * Objet interdit dans une récompense de contrat : {@code #interdit_caisses} <b>sauf</b> {@code #restreint/fusee_finale}
     * (J5, décision validée : le Module cryogénique ne s'obtient que par le contrat de mission) et
     * {@code #restreint/palier_3}, {@code #restreint/palier_4} (J6 : contrats de mission). {@code arme_max} reste interdit
     * partout hors butin de donjon. Les caisses, marchands, entreprises et {@code /daily} gardent l'interdiction
     * ({@link #isBannedFromLoot}).
     */
    public static boolean isBannedFromContracts(ItemLike item) {
        var holder = BuiltInRegistries.ITEM.wrapAsHolder(item.asItem());
        return holder.is(BANNED_FROM_LOOT) && !holder.is(FINAL_ROCKET) && !holder.is(TIER_3) && !holder.is(TIER_4);
    }

    public static boolean isBannedFromContracts(ItemStack stack) {
        return !stack.isEmpty() && stack.is(BANNED_FROM_LOOT) && !stack.is(FINAL_ROCKET) && !stack.is(TIER_3)
                && !stack.is(TIER_4);
    }

    /**
     * Garde d'exécution avant de remettre la récompense d'un contrat : faux (et une ERREUR au journal) si l'objet est
     * interdit dans les contrats ({@link #isBannedFromContracts}) ; l'appelant ne le remet pas.
     *
     * @param source description du contrat pour le journal (« contrat sovereignfrontier:mission_module_cryogenique »)
     */
    public static boolean allowInContractReward(ItemStack stack, String source) {
        if (!isBannedFromContracts(stack)) {
            return true;
        }
        LOGGER.error("[Économie] Objet interdit {} non remis ({}) : il appartient à #{} (permis dans un contrat : #{}, #{}, #{})",
                BuiltInRegistries.ITEM.getKey(stack.getItem()), source, BANNED_FROM_LOOT.location(), FINAL_ROCKET.location(),
                TIER_3.location(), TIER_4.location());
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
