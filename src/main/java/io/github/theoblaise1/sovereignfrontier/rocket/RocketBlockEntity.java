// Propriété : WP2
package io.github.theoblaise1.sovereignfrontier.rocket;

import io.github.theoblaise1.sovereignfrontier.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bloc-entité de la fusée (docs/conception/J5.md §2.2) : {@code owner} (nation qui l'a posée), {@code home} (dernier
 * départ terrestre), {@code pads} (dernier décollage sur chaque planète) et la soute de 9 cases. Le compte à rebours
 * n'est <b>pas</b> persisté (il vit dans {@link RocketFlight}) : un arrêt du serveur l'annule sans rien consommer.
 * <p>
 * Le bloc-entité n'implémente ni {@code Container} ni capacité d'objets : aucun entonnoir ne touche la soute.
 */
public class RocketBlockEntity extends BlockEntity {
    /** Contenu transférable d'une fusée : copie profonde, posée telle quelle à l'arrivée. */
    public record Data(@Nullable UUID owner, @Nullable GlobalPos home, Map<ResourceKey<Level>, Long> pads,
                       List<ItemStack> items) {
        public Data {
            pads = Collections.unmodifiableMap(new LinkedHashMap<>(pads));
            items = List.copyOf(items);
        }
    }

    @Nullable
    private UUID owner;
    @Nullable
    private GlobalPos home;
    /** Dernier décollage par planète : position du bloc, {@link BlockPos#asLong()}. */
    private final Map<ResourceKey<Level>, Long> pads = new LinkedHashMap<>();
    private final RocketHold hold = new RocketHold(this);
    /** Vrai pendant le déplacement du bloc par un vol : le retrait ne lâche rien et n'annule rien. */
    private boolean moving;

    public RocketBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ROCKET.get(), pos, state);
    }

    /** Identifiant de la nation propriétaire, ou {@code null} (fusée inerte). */
    @Nullable
    public UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    @Nullable
    public GlobalPos home() {
        return home;
    }

    public void setHome(@Nullable GlobalPos home) {
        this.home = home;
        setChanged();
    }

    /** Position du dernier décollage sur cette planète, ou {@code null}. */
    @Nullable
    public BlockPos pad(ResourceKey<Level> dimension) {
        Long packed = pads.get(dimension);
        return packed == null ? null : BlockPos.of(packed);
    }

    public void setPad(ResourceKey<Level> dimension, BlockPos pos) {
        pads.put(dimension, pos.asLong());
        setChanged();
    }

    public Map<ResourceKey<Level>, Long> pads() {
        return Collections.unmodifiableMap(pads);
    }

    public RocketHold hold() {
        return hold;
    }

    public boolean isMoving() {
        return moving;
    }

    /** Annonce que le bloc va être retiré par un vol : ni butin, ni annulation de compte à rebours. */
    public void markMoving() {
        moving = true;
    }

    /** Copie du contenu à transférer ({@code owner}, {@code home}, {@code pads}, soute : 9 cases, vides comprises). */
    public Data snapshot() {
        List<ItemStack> items = new ArrayList<>(RocketHold.SIZE);
        for (int i = 0; i < RocketHold.SIZE; i++) {
            items.add(hold.getItem(i).copy());
        }
        return new Data(owner, home, pads, items);
    }

    /** Pose le contenu transféré (arrivée d'un vol). */
    public void restore(Data data) {
        owner = data.owner();
        home = data.home();
        pads.clear();
        pads.putAll(data.pads());
        for (int i = 0; i < hold.getContainerSize(); i++) {
            hold.setItem(i, i < data.items().size() ? data.items().get(i).copy() : ItemStack.EMPTY);
        }
        setChanged();
    }

    /** Ticker serveur : seulement pendant un compte à rebours ({@code LAUNCHING}). */
    static void serverTick(Level level, BlockPos pos, BlockState state, RocketBlockEntity be) {
        if (level instanceof ServerLevel serverLevel) {
            RocketFlight.tick(serverLevel, pos, be);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
        if (home != null) {
            CompoundTag homeTag = new CompoundTag();
            homeTag.putString("dim", home.dimension().location().toString());
            homeTag.putLong("pos", home.pos().asLong());
            tag.put("home", homeTag);
        }
        CompoundTag padsTag = new CompoundTag();
        for (Map.Entry<ResourceKey<Level>, Long> e : pads.entrySet()) {
            padsTag.putLong(e.getKey().location().toString(), e.getValue());
        }
        tag.put("pads", padsTag);
        ContainerHelper.saveAllItems(tag, hold.getItems(), registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        home = null;
        if (tag.contains("home", 10)) {
            CompoundTag homeTag = tag.getCompound("home");
            ResourceLocation dim = ResourceLocation.tryParse(homeTag.getString("dim"));
            if (dim != null) {
                home = GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(homeTag.getLong("pos")));
            }
        }
        pads.clear();
        CompoundTag padsTag = tag.getCompound("pads");
        for (String key : padsTag.getAllKeys()) {
            ResourceLocation dim = ResourceLocation.tryParse(key);
            if (dim != null) {
                pads.put(ResourceKey.create(Registries.DIMENSION, dim), padsTag.getLong(key));
            }
        }
        hold.getItems().clear();
        ContainerHelper.loadAllItems(tag, hold.getItems(), registries);
    }
}
