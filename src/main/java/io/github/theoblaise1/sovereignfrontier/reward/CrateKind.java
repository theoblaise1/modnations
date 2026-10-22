package io.github.theoblaise1.sovereignfrontier.reward;

import com.mojang.serialization.Codec;
import io.github.theoblaise1.sovereignfrontier.registry.ModBlocks;
import io.github.theoblaise1.sovereignfrontier.registry.ModItems;
import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;

/**
 * Les quatre types de caisse. Chacun a un bloc ({@code caisse_<id>}) et une clé ({@code cle_<id>}) ;
 * le nom du fichier de table ({@code data/<ns>/crates/<id>.json}) est l'identifiant. L'identifiant sert
 * aussi dans la sauvegarde (pitié) : ne pas le renommer sans migration.
 */
public enum CrateKind implements StringRepresentable {
    COMMUNE("commune"),
    MILITAIRE("militaire"),
    INDUSTRIELLE("industrielle"),
    SPATIALE("spatiale");

    public static final Codec<CrateKind> CODEC = StringRepresentable.fromEnum(CrateKind::values);
    private static final CrateKind[] VALUES = values();

    private final String id;

    CrateKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /** Nom affiché du type (« Militaire ») : {@code sovereignfrontier.reward.crate.<id>}. */
    public MutableComponent displayName() {
        return Msg.tr("reward.crate." + id);
    }

    /** Clé qui ouvre ce type de caisse (pile de 64, sans recette). */
    public Item keyItem() {
        return switch (this) {
            case COMMUNE -> ModItems.KEY_COMMUNE.get();
            case MILITAIRE -> ModItems.KEY_MILITAIRE.get();
            case INDUSTRIELLE -> ModItems.KEY_INDUSTRIELLE.get();
            case SPATIALE -> ModItems.KEY_SPATIALE.get();
        };
    }

    public Block block() {
        return switch (this) {
            case COMMUNE -> ModBlocks.CRATE_COMMUNE.get();
            case MILITAIRE -> ModBlocks.CRATE_MILITAIRE.get();
            case INDUSTRIELLE -> ModBlocks.CRATE_INDUSTRIELLE.get();
            case SPATIALE -> ModBlocks.CRATE_SPATIALE.get();
        };
    }

    @Nullable
    public static CrateKind byId(String id) {
        for (CrateKind k : VALUES) {
            if (k.id.equalsIgnoreCase(id)) {
                return k;
            }
        }
        return null;
    }

    @Nullable
    public static CrateKind byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    /** Type dont {@code item} est la clé, ou {@code null}. */
    @Nullable
    public static CrateKind byKey(Item item) {
        for (CrateKind k : VALUES) {
            if (k.keyItem() == item) {
                return k;
            }
        }
        return null;
    }
}
