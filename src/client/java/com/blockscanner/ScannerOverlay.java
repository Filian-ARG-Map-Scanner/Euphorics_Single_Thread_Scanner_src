package com.blockscanner;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;

import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Renders scanner status while the player is not using another GUI.
 */
public final class ScannerOverlay {
	private static final int LEFT = 8;
	private static final int TOP = 8;
	private static final int LINE_HEIGHT = 12;
	private static final int BACKGROUND_COLOR = 0xA0101010;
	private static final int TEXT_COLOR = 0xFFFFFFFF;
	private static final int ACTIVE_COLOR = 0xFF00FF00;
	private static final int INACTIVE_COLOR = 0xFFFF0000;
	private static final int AUTO_COLOR = 0xFF00FFFF;
	private static final int MANUAL_COLOR = 0xFFFFFF00;
	private static final int TPS_GOOD_COLOR = 0xFF00FF00;
	private static final int TPS_OK_COLOR = 0xFFFFFF00;
	private static final int TPS_WARNING_COLOR = 0xFFFFA500;
	private static final int TPS_DANGER_COLOR = 0xFFFF0000;
	private static final double REDUCED_MOVEMENT_SPEED = 0.225D;
	private static final long LOW_TPS_DELAY_NS = 30_000_000_000L;
	private static final long RECOVERY_DELAY_NS = 60_000_000_000L;
	private static final long CHUNKS_PER_MILESTONE = 1_000_000L;
	private static final int TOTAL_CHUNKS_LEFT_PADDING = 8;
	private static final Path SCAN_OUTPUT_DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("blockscanner");
	private static long lastTickAtNs;
	private static double estimatedTps = 20.0D;
	private static final Set<String> knownScannedChunks = new HashSet<>();
	private static long lastAnnouncedMillion;
	private static long lowTpsSinceNs;
	private static long highTpsSinceNs;
	private static boolean movementSpeedReduced;

	private ScannerOverlay() {
	}

	public static void register() {
		loadPersistedScannedChunks();
		HudRenderCallback.EVENT.register(ScannerOverlay::render);
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
			ScreenEvents.afterRender(screen).register(ScannerOverlay::renderAfterScreen));
	}

	public static void recordScanEnded(BlockScanner scanner) {
		if (scanner != null) {
			knownScannedChunks.addAll(scanner.getScannedChunkKeys());
		}
	}

	public static void updateScannedChunkMilestone(MinecraftClient client, BlockScanner scanner) {
		if (client == null || client.player == null || scanner == null) {
			return;
		}
		long totalScannedChunks = totalScannedChunks(scanner);
		long currentMillion = totalScannedChunks / CHUNKS_PER_MILESTONE;
		if (currentMillion > lastAnnouncedMillion) {
			lastAnnouncedMillion = currentMillion;
			String message = "!!! " + currentMillion + " million chunks scammed !!!";
			client.player.networkHandler.sendChatMessage(message);
		}
	}

	public static void recordClientTick() {
		long now = System.nanoTime();
		if (lastTickAtNs != 0L) {
			long tickDurationNs = now - lastTickAtNs;
			if (tickDurationNs > 0L) {
				double currentTps = 1_000_000_000.0D / tickDurationNs;
				estimatedTps = (estimatedTps * 0.9D) + (Math.min(20.0D, currentTps) * 0.1D);
			}
		}
		lastTickAtNs = now;
		updateMovementSpeed(now);
	}

	public static double getMovementSpeed() {
		return movementSpeedReduced ? REDUCED_MOVEMENT_SPEED : SpiralTraversal.DEFAULT_SPEED;
	}

	public static boolean isMovementSpeedReduced() {
		return movementSpeedReduced;
	}

	private static void updateMovementSpeed(long now) {
		if (estimatedTps < 11.0D) {
			if (lowTpsSinceNs == 0L) {
				lowTpsSinceNs = now;
			}
			highTpsSinceNs = 0L;
			if (!movementSpeedReduced && now - lowTpsSinceNs >= LOW_TPS_DELAY_NS) {
				movementSpeedReduced = true;
			}
			return;
		}

		lowTpsSinceNs = 0L;
		if (!movementSpeedReduced) {
			return;
		}
		if (estimatedTps > 11.0D) {
			if (highTpsSinceNs == 0L) {
				highTpsSinceNs = now;
			}
			if (now - highTpsSinceNs >= RECOVERY_DELAY_NS) {
				movementSpeedReduced = false;
				highTpsSinceNs = 0L;
			}
		} else {
			highTpsSinceNs = 0L;
		}
	}

	private static void render(DrawContext context, RenderTickCounter tickCounter) {
		renderOverlay(context);
	}

	private static void renderAfterScreen(net.minecraft.client.gui.screen.Screen screen, DrawContext context,
		int mouseX, int mouseY, float delta) {
		if (screen instanceof GameMenuScreen) {
			renderOverlay(context);
		}
	}

	private static void renderOverlay(DrawContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null) {
			return;
		}

		BlockScanner scanner = BlockScannerMod.getBlockScanner();
		if (scanner == null) {
			return;
		}

		boolean scanning = BlockScannerMod.isScanning();
		boolean autoMode = BlockScannerMod.isAutoMode();
		boolean interlocked = scanning && autoMode;
		int statusColor = scanning ? ACTIVE_COLOR : INACTIVE_COLOR;
		int modeColor = autoMode ? AUTO_COLOR : MANUAL_COLOR;
		int interlockColor = interlocked ? INACTIVE_COLOR : ACTIVE_COLOR;
		long elapsedSeconds = scanning ? scanner.getScanElapsedSeconds() : 0L;
		int scannedChunks = scanning ? scanner.getScannedChunkCount() : 0;
		String[] lines = {
			scanning ? "● Scan Active" : "● Scan Stopped",
			autoMode ? "● Auto Mode" : "● Manual Mode",
			interlocked ? "● Interlock On" : "● Interlock Off",
			"Block: x=" + client.player.getBlockPos().getX() + ", z=" + client.player.getBlockPos().getZ(),
			"Chunk: x=" + client.player.getChunkPos().x + ", z=" + client.player.getChunkPos().z,
			"TPS: " + String.format(java.util.Locale.ROOT, "%.1f", estimatedTps),
			"Elapsed Time: " + formatDuration(elapsedSeconds),
			"Scanned Chunks: " + scannedChunks,
			"Chunks/hour: " + formatChunksPerHour(scannedChunks, elapsedSeconds),
			"Chunks/sec: " + formatChunksPerSecond(scannedChunks, elapsedSeconds),
			"FPS: " + client.getCurrentFps()
		};
		int[] colors = {
			statusColor,
			modeColor,
			interlockColor,
			TEXT_COLOR,
			TEXT_COLOR,
			tpsColor(estimatedTps),
			TEXT_COLOR,
			TEXT_COLOR,
			TEXT_COLOR,
			TEXT_COLOR,
			TEXT_COLOR
		};
		int textWidth = 0;
		for (String line : lines) {
			textWidth = Math.max(textWidth, client.textRenderer.getWidth(Text.literal(line)));
		}
		int right = LEFT + textWidth + 8;
		int bottom = TOP + 4 + (lines.length * LINE_HEIGHT);
		context.fill(LEFT - 4, TOP - 4, right, bottom, BACKGROUND_COLOR);

		for (int line = 0; line < lines.length; line++) {
			drawLine(context, client, line, lines[line], colors[line]);
		}

		drawTotalScannedChunks(context, client);
	}

	private static void drawTotalScannedChunks(DrawContext context, MinecraftClient client) {
		BlockScanner scanner = BlockScannerMod.getBlockScanner();
		long totalScannedChunks = totalScannedChunks(scanner);
		String line = "Total Scanned Chunks: " + totalScannedChunks;
		String warning = "⚠ LOW TPS: MOVEMENT SLOWED";
		boolean showWarning = isMovementSpeedReduced();
		int textWidth = client.textRenderer.getWidth(Text.literal(line));
		if (showWarning) {
			textWidth = Math.max(textWidth, client.textRenderer.getWidth(Text.literal(warning)));
		}
		int right = client.getWindow().getScaledWidth() - 8;
		int left = right - textWidth - TOTAL_CHUNKS_LEFT_PADDING;
		int bottom = TOP + LINE_HEIGHT + (showWarning ? LINE_HEIGHT : 0);
		context.fill(left - 4, TOP - 4, right + 4, bottom, BACKGROUND_COLOR);
		context.drawTextWithShadow(client.textRenderer, Text.literal(line), left, TOP, TEXT_COLOR);
		if (showWarning) {
			context.drawTextWithShadow(client.textRenderer, Text.literal(warning), left, TOP + LINE_HEIGHT, INACTIVE_COLOR);
		}
	}

	private static long totalScannedChunks(BlockScanner scanner) {
		return knownScannedChunks.size() + (scanner == null ? 0L : scanner.getScannedChunkCount());
	}

	private static void loadPersistedScannedChunks() {
		if (!Files.isDirectory(SCAN_OUTPUT_DIRECTORY)) {
			return;
		}
		try (var files = Files.newDirectoryStream(SCAN_OUTPUT_DIRECTORY, "scanned_chunks_*.csv")) {
			for (Path file : files) {
				readScannedChunkFile(file);
			}
		} catch (IOException exception) {
			BlockScannerMod.LOGGER.warn("Unable to load persisted scanned chunks: {}", exception.getMessage());
		}
		lastAnnouncedMillion = knownScannedChunks.size() / CHUNKS_PER_MILESTONE;
	}

	private static void readScannedChunkFile(Path file) {
		String dimension = "unknown";
		try (BufferedReader reader = Files.newBufferedReader(file)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("# dimension=")) {
					dimension = line.substring("# dimension=".length()).trim();
					if (dimension.length() >= 2 && dimension.startsWith("\"") && dimension.endsWith("\"")) {
						dimension = dimension.substring(1, dimension.length() - 1);
					}
					continue;
				}
				if (line.isBlank() || line.startsWith("#") || line.equals("chunk_x,chunk_z")) {
					continue;
				}
				String[] values = line.split(",", -1);
				if (values.length < 2) {
					continue;
				}
				try {
					knownScannedChunks.add(dimension + ":" + Integer.parseInt(values[0].trim()) + ":"
						+ Integer.parseInt(values[1].trim()));
				} catch (NumberFormatException ignored) {
					BlockScannerMod.LOGGER.warn("Ignoring invalid chunk row in {}", file.getFileName());
				}
			}
		} catch (IOException exception) {
			BlockScannerMod.LOGGER.warn("Unable to read scanned chunk file {}: {}", file.getFileName(), exception.getMessage());
		}
	}

	private static int tpsColor(double tps) {
		if (tps >= 17.0D) {
			return TPS_GOOD_COLOR;
		}
		if (tps >= 14.0D) {
			return TPS_OK_COLOR;
		}
		if (tps >= 11.0D) {
			return TPS_WARNING_COLOR;
		}
		return TPS_DANGER_COLOR;
	}

	private static String formatDuration(long totalSeconds) {
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds);
	}

	private static String formatChunksPerHour(int scannedChunks, long elapsedSeconds) {
		if (elapsedSeconds <= 0L) {
			return "0.0";
		}
		return String.format(java.util.Locale.ROOT, "%.1f", scannedChunks * 3600.0D / elapsedSeconds);
	}

	private static String formatChunksPerSecond(int scannedChunks, long elapsedSeconds) {
		if (elapsedSeconds <= 0L) {
			return "0.00";
		}
		return String.format(java.util.Locale.ROOT, "%.2f", scannedChunks / (double) elapsedSeconds);
	}

	private static void drawLine(DrawContext context, MinecraftClient client, int line, String value, int color) {
		context.drawTextWithShadow(client.textRenderer, Text.literal(value), LEFT, TOP + (line * LINE_HEIGHT), color);
	}
}
