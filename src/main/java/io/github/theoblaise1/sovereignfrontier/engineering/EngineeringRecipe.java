package io.github.theoblaise1.sovereignfrontier.engineering;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition.ItemCost;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Recette de la Station d'ingénierie, lue depuis {@code data/<ns>/engineering/<id>.json} :
 * {@code {"tab": "machines", "unlock": "generator.thermal", "ingredients": [{"item": …, "count": …}],
 * "result": {"item": …, "count": …}}}. Listée à l'écran (grisée tant qu'elle est verrouillée) ;
 * fabricable seulement si la nation du joueur a débloqué l'onglet et la clé {@code unlock} (facultative).
 * Fichier rejeté si un objet apparaît deux fois dans les ingrédients ou si le résultat dépasse une pile.
 */
public record EngineeringRecipe(ResourceLocation id, Definition definition) {

    public record Definition(EngineeringTab tab, String unlock, List<ItemCost> ingredients, ItemCost result) {
        private static final Codec<EngineeringTab> TAB_CODEC = Codec.STRING.comapFlatMap(
                s -> {
                    EngineeringTab t = EngineeringTab.byId(s);
                    return t != null ? DataResult.success(t) : DataResult.error(() -> "Onglet inconnu : " + s);
                }, EngineeringTab::id);

        private static final Codec<Definition> RAW_CODEC = RecordCodecBuilder.create(i -> i.group(
                TAB_CODEC.fieldOf("tab").forGetter(Definition::tab),
                Codec.STRING.optionalFieldOf("unlock", "").forGetter(Definition::unlock),
                ItemCost.CODEC.listOf().fieldOf("ingredients").forGetter(Definition::ingredients),
                ItemCost.CODEC.fieldOf("result").forGetter(Definition::result)
        ).apply(i, Definition::new));

        public static final Codec<Definition> CODEC = RAW_CODEC.validate(Definition::validate);

        /**
         * Un ingrédient en double serait vérifié deux fois sur le même stock (craft sous-payé) ; un résultat
         * plus grand qu'une pile serait lâché au sol en une entité invalide (sauvegarde du chunk en échec).
         */
        private static DataResult<Definition> validate(Definition d) {
            Set<Item> seen = new HashSet<>();
            for (ItemCost cost : d.ingredients) {
                if (!seen.add(cost.item())) {
                    return DataResult.error(() -> "Ingrédient en double : " + BuiltInRegistries.ITEM.getKey(cost.item()));
                }
            }
            int max = d.result.item().getDefaultMaxStackSize();
            if (d.result.count() > max) {
                return DataResult.error(() -> "Quantité de résultat " + d.result.count() + " supérieure à une pile ("
                        + max + ") pour " + BuiltInRegistries.ITEM.getKey(d.result.item()));
            }
            return DataResult.success(d);
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, EngineeringRecipe> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, EngineeringRecipe::id,
            ByteBufCodecs.fromCodecWithRegistries(Definition.CODEC), EngineeringRecipe::definition,
            EngineeringRecipe::new);
}
