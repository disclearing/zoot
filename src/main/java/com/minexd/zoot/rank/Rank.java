package com.minexd.zoot.rank;

import com.minexd.zoot.Zoot;
import com.minexd.zoot.network.packet.PacketDeleteRank;
import com.minexd.zoot.network.packet.PacketRefreshRank;
import com.minexd.zoot.util.CC;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.Setter;
import org.bson.Document;
import org.bukkit.ChatColor;

public class Rank {

	@Getter private static Map<UUID, Rank> ranks = new HashMap<>();
	private static MongoCollection<Document> collection;

	@Getter private final UUID uuid;
	@Getter private final String displayName;
	@Getter @Setter private String prefix = "";
	@Getter @Setter private String suffix = "";
	@Getter @Setter private String color = "";
	@Getter @Setter private int weight;
	@Setter private boolean defaultRank;
	@Getter private final List<String> permissions = new ArrayList<>();
	@Getter private final List<Rank> inherited = new ArrayList<>();

	public Rank(String displayName) {
		this.uuid = UUID.randomUUID();
		this.displayName = displayName;

		ranks.put(uuid, this);
	}

	public Rank(UUID uuid, String displayName) {
		this.uuid = uuid;
		this.displayName = displayName;

		ranks.put(uuid, this);
	}

	public Rank(UUID uuid, String displayName, String prefix, String suffix, String color, int weight,
			boolean defaultRank) {
		this.uuid = uuid;
		this.displayName = displayName;
		this.prefix = prefix;
		this.suffix = suffix;
		this.color = color;
		this.weight = weight;
		this.defaultRank = defaultRank;

		ranks.put(uuid, this);
	}

	public boolean isDefaultRank() {
		return defaultRank;
	}

	public boolean addPermission(String permission) {
		if (!permissions.contains(permission)) {
			permissions.add(permission);
			return true;
		}

		return false;
	}

	public boolean removePermission(String permission) {
		return permissions.remove(permission);
	}

	public boolean hasPermission(String permission) {
		if (permissions.contains(permission)) {
			return true;
		}

		for (Rank rank : inherited) {
			if (rank.hasPermission(permission)) {
				return true;
			}
		}

		return false;
	}

	public boolean canInherit(Rank rankToCheck) {
		if (inherited.contains(rankToCheck) || rankToCheck.inherited.contains(this)) {
			return false;
		}

		for (Rank rank : inherited) {
			if (!rank.canInherit(rankToCheck)) {
				return false;
			}
		}

		return true;
	}

	public List<String> getAllPermissions() {
		List<String> permissions = new ArrayList<>();
		permissions.addAll(this.permissions);

		for (Rank rank : inherited) {
			permissions.addAll(rank.getAllPermissions());
		}

		return permissions;
	}

	public void load() {
		load(collection.find(Filters.eq("uuid", uuid.toString())).first());
	}

	private void load(Document document) {
		if (document == null) {
			return;
		}

		prefix = ChatColor.translateAlternateColorCodes('&', document.getString("prefix"));
		suffix = ChatColor.translateAlternateColorCodes('&', document.getString("suffix"));
		color = ChatColor.translateAlternateColorCodes('&', document.getString("color"));
		weight = document.getInteger("weight");
		defaultRank = document.getBoolean("defaultRank");

		inherited.clear();

		Zoot.GSON.<List<String>>fromJson(document.getString("inherits"), Zoot.LIST_STRING_TYPE)
				.stream()
				.map(s -> Rank.getRankByUuid(UUID.fromString(s)))
				.filter(Objects::nonNull)
				.forEach(inherited::add);
	}

	public void save() {
		Document document = new Document();
		document.put("uuid", uuid.toString());
		document.put("displayName", displayName);
		document.put("prefix", prefix.replace(String.valueOf(ChatColor.COLOR_CHAR), "&"));
		document.put("suffix", suffix.replace(String.valueOf(ChatColor.COLOR_CHAR), "&"));
		document.put("color", color.replace(String.valueOf(ChatColor.COLOR_CHAR), "&"));
		document.put("weight", weight);
		document.put("defaultRank", defaultRank);
		document.put("permissions", Zoot.GSON.toJson(permissions));
		document.put("inherits", Zoot.GSON.toJson(inherited
				.stream()
				.map(Rank::getUuid)
				.map(UUID::toString)
				.collect(Collectors.toList())));

		collection.replaceOne(Filters.eq("uuid", uuid.toString()), document,
				new ReplaceOptions().upsert(true));

		Zoot.get().getPidgin().sendPacket(new PacketRefreshRank(uuid, displayName));
	}

	public void delete() {
		ranks.remove(uuid);
		collection.deleteOne(Filters.eq("uuid", uuid.toString()));

		Zoot.get().getPidgin().sendPacket(new PacketDeleteRank(uuid));
	}

	public static void init() {
		collection = Zoot.get().getMongoDatabase().getCollection("ranks");

		Map<Rank, List<UUID>> inheritanceReferences = new HashMap<>();

		try (MongoCursor<Document> cursor = collection.find().iterator()) {
			while (cursor.hasNext()) {
				Document document = cursor.next();

				Rank rank = new Rank(UUID.fromString(document.getString("uuid")),
						document.getString("displayName"));
				rank.load(document);

				Zoot.GSON.<List<String>>fromJson(document.getString("permissions"), Zoot.LIST_STRING_TYPE)
						.forEach(perm -> rank.getPermissions().add(perm));

				List<UUID> ranksToInherit = new ArrayList<>();

				Zoot.GSON.<List<String>>fromJson(document.getString("inherits"), Zoot.LIST_STRING_TYPE)
						.stream()
						.map(UUID::fromString)
						.forEach(ranksToInherit::add);

				inheritanceReferences.put(rank, ranksToInherit);

				ranks.put(rank.getUuid(), rank);
			}
		}

		inheritanceReferences.forEach((rank, list) -> {
			list.forEach(uuid -> {
				Rank inherited = ranks.get(uuid);

				if (inherited != null) {
					rank.getInherited().add(inherited);
				}
			});
		});

		getDefaultRank();
	}

	/**
	 * Retrieves a rank by UUID if one exists.
	 *
	 * @param uuid The UUID.
	 *
	 * @return A rank that matches the given UUID if found.
	 */
	public static Rank getRankByUuid(UUID uuid) {
		return ranks.get(uuid);
	}

	/**
	 * Retrieves a rank by name if one exists.
	 *
	 * @param name The name.
	 *
	 * @return A rank that matches the given name if found.
	 */
	public static Rank getRankByDisplayName(String name) {
		for (Rank rank : ranks.values()) {
			if (rank.getDisplayName().equalsIgnoreCase(name)) {
				return rank;
			}
		}

		return null;
	}

	/**
	 * Retrieves the default rank or creates a new default rank if one does not already exist.
	 *
	 * @return A default rank, or a new default rank if unavailable.
	 */
	public static Rank getDefaultRank() {
		for (Rank rank : ranks.values()) {
			if (rank.isDefaultRank()) {
				return rank;
			}
		}

		Rank defaultRank = new Rank("Default");
		defaultRank.setDefaultRank(true);
		defaultRank.save();

		ranks.put(defaultRank.getUuid(), defaultRank);

		return defaultRank;
	}

}
