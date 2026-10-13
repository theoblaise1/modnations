package io.github.theoblaise1.sovereignfrontier.nation;

import io.github.theoblaise1.sovereignfrontier.util.Msg;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Ligne du journal national : clé de traduction (suffixe après {@code sovereignfrontier.log.})
 * et arguments textuels, pour que le rendu reste traduisible côté client. Un argument préfixé
 * par {@value #TRANSLATED_PREFIX} est lui-même une clé (ex. {@code @role.ministre}).
 */
public record LogEntry(long epochMs, String key, List<String> args) {
    public static final String TRANSLATED_PREFIX = "@";

    public MutableComponent toComponent() {
        Object[] rendered = new Object[args.size()];
        for (int i = 0; i < rendered.length; i++) {
            String a = args.get(i);
            rendered[i] = a.startsWith(TRANSLATED_PREFIX) ? Msg.tr(a.substring(1)) : a;
        }
        return Msg.tr("log." + key, rendered);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("t", epochMs);
        tag.putString("k", key);
        ListTag list = new ListTag();
        for (String a : args) {
            list.add(StringTag.valueOf(a));
        }
        tag.put("a", list);
        return tag;
    }

    public static LogEntry load(CompoundTag tag) {
        ListTag list = tag.getList("a", Tag.TAG_STRING);
        List<String> args = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            args.add(list.getString(i));
        }
        return new LogEntry(tag.getLong("t"), tag.getString("k"), List.copyOf(args));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeLong(epochMs);
        buf.writeUtf(key);
        buf.writeVarInt(args.size());
        for (String a : args) {
            buf.writeUtf(a);
        }
    }

    public static LogEntry read(FriendlyByteBuf buf) {
        long t = buf.readLong();
        String k = buf.readUtf();
        int n = buf.readVarInt();
        List<String> args = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            args.add(buf.readUtf());
        }
        return new LogEntry(t, k, List.copyOf(args));
    }
}
