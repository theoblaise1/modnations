package io.github.theoblaise1.sovereignfrontier.engineering;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.theoblaise1.sovereignfrontier.research.ResearchDefinition.ItemCost;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Recette de la Station d'ingénierie, lue depuis {@code data/<ns>/engineering/<id>.json} :
 * {@code {"tab": "machines", "unlock": "generator.thermal", "ingredients": [{"item": …, "count": …}],
 * "result": {"item": …, "count": …}}}. Visible et fabricable seulement si la nation du joueur a
 * débloqué l'onglet et la clé {@code unlock} (facultative).
 */
public record EngineeringRecipe(ResourceLocation id, Definition definition) {

    public record Definition(EngineeringTab tab, String unlock, List<ItemCost> ingredients, ItemCost result) {
        private static final Codec<EngineeringTab> TAB_CODEC = Codec.STRING.comapFlatMap(
                s -> {
                    EngineeringTab t = EngineeringTab.byId(s);
                    return t != null ? DataResult.success(t) : DataResult.error(() -> "Onglet inconnu : " + s);
                }, EngineeringTab::id);

        public static final Codec<Definition> CODEC = RecordCodecBuilder.create(i -> i.group(
                TAB_CODEC.fieldOf("tab").forGetter(Definition::tab),
                Codec.STRING.optionalFieldOf("unlock", "").forGetter(Definition::unlock),
                ItemCost.CODEC.listOf().fieldOf("ingredients").forGetter(Definition::ingredients),
                ItemCost.CODEC.fieldOf("result").forGetter(Definition::result)
        ).apply(i, Definition::new));
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, EngineeringRecipe> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, EngineeringRecipe::id,
            ByteBufCodecs.fromCodecWithRegistries(Definition.CODEC), EngineeringRecipe::definition,
            EngineeringRecipe::new);
}
