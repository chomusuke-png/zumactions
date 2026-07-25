package zumito.zumactions.request;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

// Lista de bloqueos por jugador, persistida en el mundo (sobrevive reinicios del server,
// a diferencia de PendingRequest/ActiveSession que son efímeros a propósito).
public final class BlockListData extends SavedData {
	private static final String DATA_NAME = "zumactions_blocklist";
	private static final SavedData.Factory<BlockListData> FACTORY =
			new SavedData.Factory<>(BlockListData::new, BlockListData::load, DataFixTypes.LEVEL);

	private final Map<UUID, Set<UUID>> blockedByPlayer = new HashMap<>();

	public static BlockListData get(MinecraftServer server) {
		return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
	}

	public boolean isBlocked(UUID blocker, UUID blocked) {
		return blockedByPlayer.getOrDefault(blocker, Set.of()).contains(blocked);
	}

	public boolean block(UUID blocker, UUID toBlock) {
		boolean added = blockedByPlayer.computeIfAbsent(blocker, id -> new HashSet<>()).add(toBlock);
		if (added) {
			setDirty();
		}
		return added;
	}

	public boolean unblock(UUID blocker, UUID toUnblock) {
		Set<UUID> blocked = blockedByPlayer.get(blocker);
		boolean removed = blocked != null && blocked.remove(toUnblock);
		if (removed) {
			setDirty();
		}
		return removed;
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		for (Map.Entry<UUID, Set<UUID>> entry : blockedByPlayer.entrySet()) {
			ListTag list = new ListTag();
			for (UUID blocked : entry.getValue()) {
				list.add(StringTag.valueOf(blocked.toString()));
			}
			tag.put(entry.getKey().toString(), list);
		}
		return tag;
	}

	private static BlockListData load(CompoundTag tag, HolderLookup.Provider provider) {
		BlockListData data = new BlockListData();
		for (String key : tag.getAllKeys()) {
			ListTag list = tag.getList(key, Tag.TAG_STRING);
			Set<UUID> blocked = new HashSet<>();
			for (int i = 0; i < list.size(); i++) {
				blocked.add(UUID.fromString(list.getString(i)));
			}
			data.blockedByPlayer.put(UUID.fromString(key), blocked);
		}
		return data;
	}
}
