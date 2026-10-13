package io.github.theoblaise1.sovereignfrontier.util;

import io.github.theoblaise1.sovereignfrontier.SovereignFrontier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Fabrique de messages traduisibles. Toutes les clés sont préfixées par l'identifiant du mod
 * et définies dans {@code assets/sovereignfrontier/lang/en_us.json} (rédigé en français,
 * langue de repli de Minecraft pour toutes les locales).
 */
public final class Msg {
    private Msg() {
    }

    public static String key(String key) {
        return SovereignFrontier.MODID + "." + key;
    }

    public static MutableComponent tr(String key, Object... args) {
        return Component.translatable(key(key), args);
    }

    public static MutableComponent ok(String key, Object... args) {
        return tr(key, args).withStyle(ChatFormatting.GREEN);
    }

    public static MutableComponent err(String key, Object... args) {
        return tr(key, args).withStyle(ChatFormatting.RED);
    }

    public static MutableComponent info(String key, Object... args) {
        return tr(key, args).withStyle(ChatFormatting.GRAY);
    }

    public static MutableComponent warn(String key, Object... args) {
        return tr(key, args).withStyle(ChatFormatting.GOLD);
    }

    public static MutableComponent hl(Object value) {
        return Component.literal(String.valueOf(value)).withStyle(ChatFormatting.WHITE);
    }

    public static MutableComponent money(long amount) {
        return Component.literal(Money.format(amount)).withStyle(ChatFormatting.YELLOW);
    }
}
