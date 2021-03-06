package com.minexd.zoot.profile;

import com.minexd.zoot.Locale;
import com.minexd.zoot.Zoot;
import com.minexd.zoot.bootstrap.BootstrappedListener;
import com.minexd.zoot.cache.RedisPlayerData;
import com.minexd.zoot.network.packet.PacketStaffChat;
import com.minexd.zoot.profile.punishment.Punishment;
import com.minexd.zoot.profile.punishment.PunishmentType;
import com.minexd.zoot.util.CC;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class ProfileListener extends BootstrappedListener {

	public ProfileListener(Zoot zoot) {
		super(zoot);
	}

	@EventHandler
	public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
		Player player = Bukkit.getPlayer(event.getUniqueId());

		// Need to check if player is still logged in when receiving another login attempt
		// This happens when a player using a custom client can access the server list while in-game (and reconnecting)
		if (player != null && player.isOnline()) {
			event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
			event.setKickMessage(CC.RED + "You tried to login too quickly after disconnecting.\nTry again in a few seconds.");
			zoot.getServer().getScheduler().runTask(zoot, () -> player.kickPlayer(CC.RED + "Duplicate login kick"));
			return;
		}

		Profile profile = null;

		try {
			profile = new Profile(event.getName(), event.getUniqueId());

			if (!profile.isLoaded()) {
				event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
				event.setKickMessage(Locale.FAILED_TO_LOAD_PROFILE.format());
				return;
			}

			if (profile.getActivePunishmentByType(PunishmentType.BAN) != null) {
				handleBan(event, profile.getActivePunishmentByType(PunishmentType.BAN));
				return;
			}

			profile.setUsername(event.getName());

			if (profile.getFirstSeen() == null) {
				profile.setFirstSeen(System.currentTimeMillis());
			}

			profile.setLastSeen(System.currentTimeMillis());

			if (profile.getCurrentAddress() == null) {
				profile.setCurrentAddress(event.getAddress().getHostAddress());
			}

			if (!profile.getIpAddresses().contains(event.getAddress().getHostAddress())) {
				profile.getIpAddresses().add(event.getAddress().getHostAddress());
			}

			if (!profile.getCurrentAddress().equals(event.getAddress().getHostAddress())) {
				List<Profile> alts = Profile.getByIpAddress(event.getAddress().getHostAddress());

				for (Profile alt : alts) {
					if (alt.getActivePunishmentByType(PunishmentType.BAN) != null) {
						handleBan(event, alt.getActivePunishmentByType(PunishmentType.BAN));
						return;
					}
				}
			}

			profile.save();
		} catch (Exception e) {
			e.printStackTrace();
			zoot.debug(Level.SEVERE, "Failed to load profile for " + event.getName(), e);
		}

		if (profile == null || !profile.isLoaded()) {
			event.setKickMessage(Locale.FAILED_TO_LOAD_PROFILE.format());
			event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
			return;
		}

		Profile.getProfiles().put(profile.getUuid(), profile);

		RedisPlayerData playerData = new RedisPlayerData(event.getUniqueId(), event.getName());
		playerData.setLastAction(RedisPlayerData.LastAction.JOINING_SERVER);
		playerData.setLastSeenServer(zoot.getMainConfig().getString("SERVER_NAME"));
		playerData.setLastSeenAt(System.currentTimeMillis());

		zoot.getRedisCache().updatePlayerData(playerData);
		zoot.getRedisCache().updateNameAndUUID(event.getName(), event.getUniqueId());
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		Player player = event.getPlayer();
		Profile profile = Profile.getProfiles().get(player.getUniqueId());
		profile.setupBukkitPlayer(player);

		for (String perm : profile.getActiveGrant().getRank().getAllPermissions()) {
			zoot.debug(player, perm);
		}

		if (player.hasPermission("zoot.staff")) {
			player.sendMessage(CC.GOLD + "Your staff mode is currently: " +
					(profile.getStaffOptions().staffModeEnabled() ? CC.GREEN + "Enabled" : CC.RED + "Disabled"));
		}
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		Profile profile = Profile.getProfiles().remove(event.getPlayer().getUniqueId());
		profile.setLastSeen(System.currentTimeMillis());

		if (profile.isLoaded()) {
			new BukkitRunnable() {
				@Override
				public void run() {
					try {
						profile.save();
					} catch (Exception e) {
						zoot.debug(Level.SEVERE, "Failed to save profile " + event.getPlayer().getName(), e);
					}
				}
			}.runTaskAsynchronously(Zoot.get());
		}

		RedisPlayerData playerData = new RedisPlayerData(event.getPlayer().getUniqueId(), event.getPlayer().getName());
		playerData.setLastAction(RedisPlayerData.LastAction.LEAVING_SERVER);
		playerData.setLastSeenServer(zoot.getMainConfig().getString("SERVER_NAME"));
		playerData.setLastSeenAt(System.currentTimeMillis());

		zoot.getRedisCache().updatePlayerData(playerData);
	}

	@EventHandler(priority = EventPriority.LOW)
	public void onAsyncPlayerChatEvent(AsyncPlayerChatEvent event) {
		Profile profile = Profile.getByUuid(event.getPlayer().getUniqueId());

		if (profile.getStaffOptions().staffChatModeEnabled()) {
			if (profile.getStaffOptions().staffModeEnabled()) {
				Zoot.get().getPidgin().sendPacket(new PacketStaffChat(event.getPlayer().getDisplayName(),
						Zoot.get().getMainConfig().getString("SERVER_NAME"), event.getMessage()));
			} else {
				event.getPlayer().sendMessage(CC.RED + "You must enable staff mode or disable staff chat mode.");
			}

			event.setCancelled(true);
		}
	}

	private void handleBan(AsyncPlayerPreLoginEvent event, Punishment punishment) {
		event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_OTHER);
		event.setKickMessage(punishment.getKickMessage());
	}

}
