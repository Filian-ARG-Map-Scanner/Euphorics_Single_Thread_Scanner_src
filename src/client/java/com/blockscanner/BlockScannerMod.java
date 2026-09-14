package com.blockscanner;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.KeyBinding.Category;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.DayOfWeek;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

//Client entry point for the mod
public class BlockScannerMod implements ClientModInitializer {
	public static final String MOD_ID = "blockscanner";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Category KEYBINDING_CATEGORY = Category.create(Identifier.of(MOD_ID, "general"));

	private static KeyBinding screenKey;
	private static KeyBinding controllerKey;
	private static BlockScanner blockScanner;
	private static boolean scanning;
	private static boolean autoMode = true;
	private static boolean pendingScanRestore;
	private static String savedScanDimension;
	private static boolean manualTargetActive;
	private static int manualTargetX;
	private static int manualTargetZ;
	private static int manualChunkTargetX;
	private static int manualChunkTargetZ;
	private static int waypointX;
	private static int waypointZ;
	private static int lastReachedWaypointX;
	private static int lastReachedWaypointZ;
	private static final Deque<SpiralTraversal.Waypoint> futureWaypoints = new ArrayDeque<>();
	private static final int FUTURE_WAYPOINT_BUFFER_SIZE = 2;
	private static final Path WAYPOINT_FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("blockscanner")
		.resolve("last_waypoint.csv");
	private static final Path SCAN_STATE_FILE = FabricLoader.getInstance().getConfigDir()
		.resolve("blockscanner")
		.resolve("scan_state.properties");
	private static final long STATE_CHECKPOINT_INTERVAL_MS = 1_000L;
	private static final long AUTO_RECONNECT_INTERVAL_MS = 30_000L;
	private static long lastAutomaticStopMinute = Long.MIN_VALUE;
	private static long lastAutomaticReconnectMinute = Long.MIN_VALUE;
	private static ServerInfo scheduledReconnectServer;
	private static ServerInfo lastConnectedServer;
	private static long nextAutoReconnectAt = Long.MAX_VALUE;
	private static boolean intentionalDisconnect;
	private static boolean resumeScanAfterReconnect;
	private static long lastStateCheckpointAt;

    //Initializes the client-side components of the mod
	@Override
	public void onInitializeClient() {
		blockScanner = new BlockScanner();
		loadPersistentState();
		screenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.blockscanner.screen",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_KP_8,
			KEYBINDING_CATEGORY
		));
		controllerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
			"key.blockscanner.controller",
			InputUtil.Type.KEYSYM,
			GLFW.GLFW_KEY_KP_9,
			KEYBINDING_CATEGORY
		));
		ScannerOverlay.register();
		ClientTickEvents.START_CLIENT_TICK.register(BlockScannerMod::lockAutoMovement);
		ClientTickEvents.END_CLIENT_TICK.register(BlockScannerMod::onClientTick);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
			(handler, sender, client) -> {
				lastConnectedServer = client.getCurrentServerEntry();
				nextAutoReconnectAt = Long.MAX_VALUE;
			}
		);
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
			(handler, client) -> {
				if (!intentionalDisconnect && scanning) {
					resumeScanAfterReconnect = true;
				}
				if (!intentionalDisconnect) {
					ServerInfo disconnectedServer = client.getCurrentServerEntry();
					if (disconnectedServer != null) {
						lastConnectedServer = disconnectedServer;
					}
					if (lastConnectedServer != null) {
						nextAutoReconnectAt = System.currentTimeMillis() + AUTO_RECONNECT_INTERVAL_MS;
						LOGGER.info("Connection lost; will retry {} in 30 seconds", lastConnectedServer.address);
					}
				}
				intentionalDisconnect = false;
				finishScanSession();
			}
		);
		Runtime.getRuntime().addShutdownHook(new Thread(BlockScannerMod::saveInterruptedSession, "blockscanner-shutdown-save"));
		LOGGER.info("Block Scanner client initialized");
	}

	private static void lockAutoMovement(MinecraftClient client) {
		if (!scanning || !autoMode) {
			return;
		}
		client.options.forwardKey.setPressed(false);
		client.options.backKey.setPressed(false);
		client.options.leftKey.setPressed(false);
		client.options.rightKey.setPressed(false);
	}

    //Handles the client-side tick events
	private static void onClientTick(MinecraftClient client) {
		ScannerOverlay.recordClientTick();
		restoreActiveScanIfNeeded(client);
		resumeScanAfterReconnectIfNeeded(client);
		while (screenKey.wasPressed()) {
			if (client.currentScreen instanceof ScannerConfigScreen) {
				client.setScreen(null);
			} else {
				client.setScreen(new ScannerConfigScreen());
			}
		}

		while (controllerKey.wasPressed()) {
			if (client.currentScreen instanceof ControllerScreen) {
				client.setScreen(null);
			} else {
				client.setScreen(new ControllerScreen());
			}
		}

		stopScanAtScheduledTime(client);
		reconnectAtScheduledTime(client);
		autoReconnectIfNeeded(client);
		if (scanning && client.world != null &&
			!blockScanner.isInScanDimension(client.world.getRegistryKey().getValue().toString())) {
			finishScanSession();
			if (client.player != null) {
				client.player.sendMessage(Text.literal("[Block Scanner] Scan ended because you changed dimension."), false);
			}
		}
		if (scanning) {
			blockScanner.scanAroundPlayer(client);
			ScannerOverlay.updateScannedChunkMilestone(client, blockScanner);
			if (autoMode) {
				moveToWaypoint(client);
			} else if (manualTargetActive) {
				moveToManualTarget(client);
			}
			checkpointScanStateIfDue();
		} else if (manualTargetActive) {
			moveToManualTarget(client);
		}
	}

	private static void resumeScanAfterReconnectIfNeeded(MinecraftClient client) {
		if (!resumeScanAfterReconnect || scanning || client.world == null) {
			return;
		}
		resumeScanAfterReconnect = false;
		toggleScanning(client);
	}

	private static void stopScanAtScheduledTime(MinecraftClient client) {
		if (!scanning) {
			return;
		}

		Instant now = Instant.now();
		long currentMinute = now.getEpochSecond() / 60L;
		var utcTime = now.atZone(ZoneOffset.UTC).toLocalTime();
		if (utcTime.getHour() == 9 && utcTime.getMinute() == 50 && currentMinute != lastAutomaticStopMinute) {
			lastAutomaticStopMinute = currentMinute;
			scheduledReconnectServer = client.getCurrentServerEntry();
			intentionalDisconnect = true;
			toggleScanning(client);
			if (client.getNetworkHandler() != null) {
				client.getNetworkHandler().getConnection().disconnect(
					Text.literal("Scheduled scanner stop")
				);
			}
		}
	}

	private static void autoReconnectIfNeeded(MinecraftClient client) {
		if (lastConnectedServer == null) {
			return;
		}
		if (client.getNetworkHandler() != null && nextAutoReconnectAt == Long.MAX_VALUE) {
			lastConnectedServer = null;
			return;
		}
		if (System.currentTimeMillis() < nextAutoReconnectAt) {
			return;
		}

		ServerInfo server = lastConnectedServer;
		nextAutoReconnectAt = System.currentTimeMillis() + AUTO_RECONNECT_INTERVAL_MS;
		LOGGER.info("Attempting automatic reconnect to {}", server.address);
		ConnectScreen.connect(
			client.currentScreen,
			client,
			ServerAddress.parse(server.address),
			server,
			false,
			null
		);
	}

	private static void reconnectAtScheduledTime(MinecraftClient client) {
		if (scheduledReconnectServer == null) {
			return;
		}
		if (client.getNetworkHandler() != null) {
			scheduledReconnectServer = null;
			return;
		}

		Instant now = Instant.now();
		long currentMinute = now.getEpochSecond() / 60L;
		var utcDateTime = now.atZone(ZoneOffset.UTC);
		if (utcDateTime.getHour() != 12 || utcDateTime.getMinute() != 45
			|| utcDateTime.getDayOfWeek() == DayOfWeek.SATURDAY
			|| currentMinute == lastAutomaticReconnectMinute) {
			return;
		}

		lastAutomaticReconnectMinute = currentMinute;
		ConnectScreen.connect(
			client.currentScreen,
			client,
			ServerAddress.parse(scheduledReconnectServer.address),
			scheduledReconnectServer,
			false,
			null
		);
	}

	public static void toggleScanning(MinecraftClient client) {
		if (!scanning && !hasSufficientRenderDistance(client)) {
			sendRenderDistanceError(client);
			return;
		}

		if (!scanning) {
			scanning = true;
			manualTargetActive = false;
			loadLastWaypoint();
			refillWaypointBuffer();
			String dimension = client.world == null
				? "unknown"
				: client.world.getRegistryKey().getValue().toString();
			blockScanner.startScanSession(dimension);
		} else {
			finishScanSession();
		}
		sendToggleMessage(client);
	}

	public static boolean isAutoMode() {
		return autoMode;
	}

	public static int getManualTargetX() {
		return manualTargetX;
	}

	public static int getManualTargetZ() {
		return manualTargetZ;
	}

	public static void toggleAutoMode() {
		if (!scanning) {
			autoMode = !autoMode;
			persistSettings();
		}
	}

	public static boolean startManualTarget(int x, int z) {
		if (scanning && autoMode) {
			return false;
		}
		manualTargetX = x;
		manualTargetZ = z;
		manualTargetActive = true;
		persistSettings();
		return true;
	}

	public static boolean startManualChunkTarget(int chunkX, int chunkZ) {
		manualChunkTargetX = chunkX;
		manualChunkTargetZ = chunkZ;
		return startManualTarget((chunkX * 16) + 8, (chunkZ * 16) + 8);
	}

	public static boolean startSpiralAtChunk(MinecraftClient client, int chunkX, int chunkZ) {
		if (scanning || !hasSufficientRenderDistance(client)) {
			return false;
		}
		SpiralTraversal.Waypoint nearest = SpiralTraversal.nearest(
			(chunkX * 16) + 8,
			(chunkZ * 16) + 8,
			waypointSpacing()
		);
		autoMode = true;
		manualTargetActive = false;
		waypointX = nearest.x();
		waypointZ = nearest.z();
		lastReachedWaypointX = waypointX;
		lastReachedWaypointZ = waypointZ;
		futureWaypoints.clear();
		refillWaypointBuffer();
		blockScanner.startScanSession(client.world == null
			? "unknown"
			: client.world.getRegistryKey().getValue().toString());
		scanning = true;
		persistSettings();
		if (client.player != null) {
			client.player.sendMessage(Text.literal(
				"[Block Scanner] Starting spiral at waypoint (" + waypointX + ", " + waypointZ + ")."
			), false);
		}
		sendToggleMessage(client);
		return true;
	}

	public static int getManualChunkTargetX() {
		return manualChunkTargetX;
	}

	public static int getManualChunkTargetZ() {
		return manualChunkTargetZ;
	}

	private static void moveToManualTarget(MinecraftClient client) {
		if (client.player == null) {
			return;
		}

		double targetX = manualTargetX + 0.5D;
		double targetZ = manualTargetZ + 0.5D;
		double deltaX = targetX - client.player.getX();
		double deltaZ = targetZ - client.player.getZ();
		double distance = Math.sqrt((deltaX * deltaX) + (deltaZ * deltaZ));
		double speed = ScannerOverlay.getMovementSpeed();
		if (distance <= speed) {
			client.player.setPosition(targetX, client.player.getY(), targetZ);
			manualTargetActive = false;
			client.player.sendMessage(Text.literal("[Block Scanner] Arrived at target block."), false);
			return;
		}

		double scale = speed / distance;
		client.player.setPosition(
			client.player.getX() + deltaX * scale,
			client.player.getY(),
			client.player.getZ() + deltaZ * scale
		);
	}

	private static void checkpointScanStateIfDue() {
		long now = System.currentTimeMillis();
		if (now - lastStateCheckpointAt < STATE_CHECKPOINT_INTERVAL_MS) {
			return;
		}
		lastStateCheckpointAt = now;
		blockScanner.flushScanSession();
		saveLastWaypoint();
		saveScanState(true);
	}

	private static void moveToWaypoint(MinecraftClient client) {
		if (client.player == null) {
			return;
		}

		double targetX = waypointX + 0.5D;
		double targetZ = waypointZ + 0.5D;
		double deltaX = targetX - client.player.getX();
		double deltaZ = targetZ - client.player.getZ();
		double distance = Math.sqrt((deltaX * deltaX) + (deltaZ * deltaZ));
		double waypointSpeed = ScannerOverlay.getMovementSpeed();
		if (distance <= waypointSpeed) {
			client.player.setPosition(targetX, client.player.getY(), targetZ);
			logWaypointScanProgress(client);
			if (!isWaypointScanComplete()) {
				return;
			}
			logCompletedChunkArea(client);
			lastReachedWaypointX = waypointX;
			lastReachedWaypointZ = waypointZ;
			advanceWaypoint();
			return;
		}
		double scale = waypointSpeed / distance;
		client.player.setPosition(
			client.player.getX() + deltaX * scale,
			client.player.getY(),
			client.player.getZ() + deltaZ * scale
		);
	}

	private static void logWaypointScanProgress(MinecraftClient client) {
		int centerChunkX = Math.floorDiv(waypointX, 16);
		int centerChunkZ = Math.floorDiv(waypointZ, 16);
		LOGGER.info(
			"Reached scan area: dimension={}, center=({}, {}), chunks completed={}/{}",
			client.world == null ? blockScanner.getScanDimension() : client.world.getRegistryKey().getValue(),
			centerChunkX,
			centerChunkZ,
			blockScanner.getScannedChunkCount(centerChunkX, centerChunkZ),
			blockScanner.getScannableChunkCount()
		);
	}

	private static boolean isWaypointScanComplete() {
		int centerChunkX = Math.floorDiv(waypointX, 16);
		int centerChunkZ = Math.floorDiv(waypointZ, 16);
		return blockScanner.getScannedChunkCount(centerChunkX, centerChunkZ)
			>= blockScanner.getScannableChunkCount();
	}

	private static void logCompletedChunkArea(MinecraftClient client) {
		int centerChunkX = Math.floorDiv(waypointX, 16);
		int centerChunkZ = Math.floorDiv(waypointZ, 16);
		int radius = blockScanner.getScanRadius();
		int minimumChunkX = centerChunkX - radius;
		int maximumChunkX = centerChunkX + radius;
		int minimumChunkZ = centerChunkZ - radius;
		int maximumChunkZ = centerChunkZ + radius;
		String dimension = client.world == null
			? blockScanner.getScanDimension()
			: client.world.getRegistryKey().getValue().toString();

		LOGGER.info(
			"Completed scanning chunk area: dimension={}, center=({}, {}), bounds=({}, {}) to ({}, {}), chunks={}",
			dimension,
			centerChunkX,
			centerChunkZ,
			minimumChunkX,
			minimumChunkZ,
			maximumChunkX,
			maximumChunkZ,
			blockScanner.getScannableChunkCount()
		);
	}

	private static void advanceWaypoint() {
		SpiralTraversal.Waypoint next = futureWaypoints.pollFirst();
		if (next == null) {
			next = SpiralTraversal.next(waypointX, waypointZ, waypointSpacing());
		}
		waypointX = next.x();
		waypointZ = next.z();
		refillWaypointBuffer();
	}

	private static void refillWaypointBuffer() {
		while (futureWaypoints.size() < FUTURE_WAYPOINT_BUFFER_SIZE) {
			SpiralTraversal.Waypoint previous = futureWaypoints.peekLast();
			if (previous == null) {
				previous = new SpiralTraversal.Waypoint(waypointX, waypointZ);
			}
			futureWaypoints.addLast(
				SpiralTraversal.next(previous.x(), previous.z(), waypointSpacing())
			);
		}
	}

	private static int waypointSpacing() {
		return blockScanner.getWaypointSpacingBlocks();
	}

	private static void loadLastWaypoint() {
		waypointX = 0;
		waypointZ = 0;
		lastReachedWaypointX = 0;
		lastReachedWaypointZ = 0;
		futureWaypoints.clear();
		try {
			if (!Files.exists(WAYPOINT_FILE)) {
				return;
			}
			String[] values = Files.readString(WAYPOINT_FILE).trim().split(",");
			if (values.length >= 2) {
				waypointX = Integer.parseInt(values[0].trim());
				waypointZ = Integer.parseInt(values[1].trim());
				lastReachedWaypointX = waypointX;
				lastReachedWaypointZ = waypointZ;
			}
		} catch (IOException | NumberFormatException exception) {
			LOGGER.warn("Unable to load last waypoint: {}", exception.getMessage());
		}
	}

	private static void saveLastWaypoint() {
		try {
			Files.createDirectories(WAYPOINT_FILE.getParent());
			Files.writeString(
				WAYPOINT_FILE,
				lastReachedWaypointX + "," + lastReachedWaypointZ + System.lineSeparator(),
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING
			);
		} catch (IOException exception) {
			LOGGER.warn("Unable to save last waypoint: {}", exception.getMessage());
		}
	}

	private static synchronized void finishScanSession() {
		if (!scanning) {
			return;
		}
		saveLastWaypoint();
		saveScanState(false);
		blockScanner.endScanSession();
		ScannerOverlay.recordScanEnded(blockScanner);
		scanning = false;
	}

	private static synchronized void saveInterruptedSession() {
		if (blockScanner == null) {
			return;
		}
		blockScanner.flushScanSession();
		saveLastWaypoint();
		saveScanState(scanning);
		if (scanning) {
			blockScanner.endScanSession();
				ScannerOverlay.recordScanEnded(blockScanner);
		}
	}

	private static void saveScanState(boolean active) {
		try {
			Files.createDirectories(SCAN_STATE_FILE.getParent());
			Properties properties = new Properties();
			properties.setProperty("waypoint.x", Integer.toString(waypointX));
			properties.setProperty("waypoint.z", Integer.toString(waypointZ));
			properties.setProperty("last_reached.x", Integer.toString(lastReachedWaypointX));
			properties.setProperty("last_reached.z", Integer.toString(lastReachedWaypointZ));
			properties.setProperty("scan.radius", Integer.toString(blockScanner.getScanRadius()));
			properties.setProperty("auto.mode", Boolean.toString(autoMode));
			properties.setProperty("manual_target.active", Boolean.toString(manualTargetActive));
			properties.setProperty("manual_target.x", Integer.toString(manualTargetX));
			properties.setProperty("manual_target.z", Integer.toString(manualTargetZ));
			properties.setProperty("manual_chunk_target.x", Integer.toString(manualChunkTargetX));
			properties.setProperty("manual_chunk_target.z", Integer.toString(manualChunkTargetZ));
			properties.setProperty("scan.dimension", blockScanner.getScanDimension() == null ? "unknown" : blockScanner.getScanDimension());
			properties.setProperty("scan.active", Boolean.toString(active));
			properties.setProperty("blacklist", serializeBlacklist());
			Path temporary = SCAN_STATE_FILE.resolveSibling(SCAN_STATE_FILE.getFileName() + ".tmp");
			try (java.io.BufferedWriter writer = Files.newBufferedWriter(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				properties.store(writer, "Euphoric's Fast Scanner state");
			}
			try {
				Files.move(temporary, SCAN_STATE_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporary, SCAN_STATE_FILE, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException exception) {
			LOGGER.warn("Unable to save scan state: {}", exception.getMessage());
		}
	}

	private static void loadPersistentState() {
		if (!Files.exists(SCAN_STATE_FILE)) {
			return;
		}
		try (java.io.BufferedReader reader = Files.newBufferedReader(SCAN_STATE_FILE)) {
			Properties properties = new Properties();
			properties.load(reader);
			waypointX = integerProperty(properties, "waypoint.x", 0);
			waypointZ = integerProperty(properties, "waypoint.z", 0);
			lastReachedWaypointX = integerProperty(properties, "last_reached.x", waypointX);
			lastReachedWaypointZ = integerProperty(properties, "last_reached.z", waypointZ);
			manualTargetX = integerProperty(properties, "manual_target.x", 0);
			manualTargetZ = integerProperty(properties, "manual_target.z", 0);
			manualChunkTargetX = integerProperty(properties, "manual_chunk_target.x", 0);
			manualChunkTargetZ = integerProperty(properties, "manual_chunk_target.z", 0);
			manualTargetActive = Boolean.parseBoolean(properties.getProperty("manual_target.active", "false"));
			autoMode = Boolean.parseBoolean(properties.getProperty("auto.mode", "true"));
			try {
				blockScanner.setScanRadius(integerProperty(properties, "scan.radius", blockScanner.getScanRadius()));
			} catch (IllegalArgumentException ignored) {
				LOGGER.warn("Ignoring invalid saved scan radius");
			}
			loadBlacklist(properties.getProperty("blacklist", ""));
			savedScanDimension = properties.getProperty("scan.dimension", "unknown");
			pendingScanRestore = Boolean.parseBoolean(properties.getProperty("scan.active", "false"));
		} catch (IOException exception) {
			LOGGER.warn("Unable to load scanner state: {}", exception.getMessage());
		}
	}

	private static void restoreActiveScanIfNeeded(MinecraftClient client) {
		if (!pendingScanRestore || scanning || client.world == null) {
			return;
		}
		String dimension = client.world.getRegistryKey().getValue().toString();
		if (!dimension.equals(savedScanDimension) || !hasSufficientRenderDistance(client)) {
			pendingScanRestore = false;
			return;
		}
		pendingScanRestore = false;
		toggleScanning(client);
	}

	private static int integerProperty(Properties properties, String key, int fallback) {
		try {
			return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	private static String serializeBlacklist() {
		Set<String> ids = new HashSet<>();
		for (net.minecraft.block.Block block : blockScanner.getIgnoredBlocks()) {
			ids.add(Registries.BLOCK.getId(block).toString());
		}
		return String.join(",", ids);
	}

	private static void loadBlacklist(String value) {
		if (value.isBlank()) {
			return;
		}
		Set<net.minecraft.block.Block> blocks = new HashSet<>();
		for (String rawId : value.split(",")) {
			Identifier id = Identifier.tryParse(rawId.trim());
			if (id != null && Registries.BLOCK.containsId(id)) {
				blocks.add(Registries.BLOCK.get(id));
			}
		}
		if (!blocks.isEmpty()) {
			blockScanner.setIgnoredBlocks(blocks);
		}
	}

	public static void persistSettings() {
		if (blockScanner != null) {
			saveScanState(scanning);
		}
	}

	private static String csv(String value) {
		String escaped = value == null ? "" : value.replace("\"", "\"\"");
		return "\"" + escaped + "\"";
	}

    //Checks if the player's render distance is sufficient for scanning
	private static boolean hasSufficientRenderDistance(MinecraftClient client) {
		return client.options.getViewDistance().getValue() >= blockScanner.getScanRadius();
	}
    //Sends an error message to the player if the render distance is insufficient
	private static void sendRenderDistanceError(MinecraftClient client) {
		if (client.player == null) {return;}
        String message = "[SCAMMER ERROR] Render distance must be at least " + blockScanner.getScanRadius() + " chunks! Increase render distance or lower scam radius";
		client.player.networkHandler.sendChatMessage(message);
	}

    //Code to send and log when the scan is toggled
    private static void sendToggleMessage(MinecraftClient client) {
        if (client.player == null) {return;}
        String message = scanning ? "> Auto-scam started <" : "> Auto-scam stopped <";
        client.player.networkHandler.sendChatMessage(message);
        message = scanning ? "GULP..." : "-- Mr. Beast Slain :3 --";
        client.player.networkHandler.sendChatMessage(message);
    }

	public static boolean isScanning() {
		return scanning;
	}

	public static BlockScanner getBlockScanner() {
		return blockScanner;
	}

	public static void printBlacklist(MinecraftClient client) {
		if (client == null || client.player == null || blockScanner == null) {
			return;
		}

		String blacklist = blockScanner.getIgnoredBlocks().stream()
			.map(Registries.BLOCK::getId)
			.map(Identifier::toString)
			.sorted()
			.collect(Collectors.joining(", "));
		String message = blacklist.isEmpty() ? "Blacklist is empty." : "Blacklist: " + blacklist;
		client.player.sendMessage(Text.literal("[Block Scanner] " + message), false);
	}
}
