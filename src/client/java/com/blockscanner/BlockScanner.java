package com.blockscanner;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.io.RandomAccessFile;

/**
 * Scans loaded chunks and writes scan results to two CSV files.
 */
public class BlockScanner {
	private static final int DEFAULT_SCAN_RADIUS = 7;
	private static final int MIN_SCAN_RADIUS = 1;
	private static final int MAX_SCAN_RADIUS = 32;
	private static final long FLUSH_INTERVAL_NS = 1_000_000_000L;
	private static final int FLUSH_ROW_LIMIT = 512;
	private static final int MAX_QUEUED_CHUNKS = 200;
	private static final String EMPTY_END_TIME = "00000000_000000";
	private static final Set<Block> DEFAULT_IGNORED_BLOCKS = Set.of(
		Blocks.AIR,
		Blocks.CAVE_AIR,
		Blocks.VOID_AIR,
		Blocks.TWISTING_VINES,
		Blocks.WARPED_HYPHAE,
		Blocks.NETHERRACK,
		Blocks.CRIMSON_FUNGUS,
		Blocks.WARPED_STEM,
		Blocks.NETHER_GOLD_ORE,
		Blocks.BEDROCK,
		Blocks.NETHER_BRICKS,
		Blocks.WEEPING_VINES_PLANT,
		Blocks.POLISHED_BLACKSTONE,
		Blocks.NETHER_WART,
		Blocks.GRAVEL,
		Blocks.BLACKSTONE,
		Blocks.SOUL_SAND,
		Blocks.ANCIENT_DEBRIS,
		Blocks.NETHER_SPROUTS,
		Blocks.WARPED_NYLIUM,
		Blocks.NETHER_WART_BLOCK,
		Blocks.GLOWSTONE,
		Blocks.CRIMSON_STEM,
		Blocks.WARPED_WART_BLOCK,
		Blocks.FIRE,
		Blocks.TWISTING_VINES_PLANT,
		Blocks.POLISHED_BLACKSTONE_BRICKS,
		Blocks.RED_MUSHROOM,
		Blocks.WARPED_FUNGUS,
		Blocks.SHROOMLIGHT,
		Blocks.SOUL_SOIL,
		Blocks.WEEPING_VINES,
		Blocks.CRIMSON_HYPHAE,
		Blocks.CRIMSON_ROOTS,
		Blocks.MAGMA_BLOCK,
		Blocks.LAVA,
		Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS,
		Blocks.NETHER_QUARTZ_ORE,
		Blocks.BASALT,
		Blocks.BROWN_MUSHROOM,
		Blocks.WARPED_ROOTS,
		Blocks.CRIMSON_NYLIUM,
		Blocks.SOUL_FIRE
	);

	private final Set<Block> ignoredBlocks = new HashSet<>(DEFAULT_IGNORED_BLOCKS);
	private final Map<Block, String> blockIdCache = new HashMap<>();
	private final Set<String> scannedChunkKeys = new HashSet<>();
	private final Set<String> queuedChunkKeys = new HashSet<>();
	private final Queue<ChunkPos> scanQueue = new ArrayDeque<>();
	private int scanRadius = DEFAULT_SCAN_RADIUS;
	private final Path outputDirectory = FabricLoader.getInstance().getConfigDir().resolve("blockscanner");
	private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);
	private Path scannedChunksFile;
	private Path scannedBlocksFile;
	private String scanStartTime;
	private String scanDimension;
	private long scanStartedAtNs;
	private BufferedWriter scannedChunksWriter;
	private BufferedWriter scannedBlocksWriter;
	private int pendingRows;
	private long lastFlushAt;

	public int getScanRadius() {
		return scanRadius;
	}

	public void setScanRadius(int radius) {
		if (radius < MIN_SCAN_RADIUS || radius > MAX_SCAN_RADIUS) {
			throw new IllegalArgumentException("Radius must be between " + MIN_SCAN_RADIUS + " and " + MAX_SCAN_RADIUS);
		}
		scanRadius = radius;
		scanQueue.clear();
		queuedChunkKeys.clear();
	}

	public int getScannedChunkCount(int centerChunkX, int centerChunkZ) {
		int scanned = 0;
		for (int chunkX = centerChunkX - scanRadius; chunkX <= centerChunkX + scanRadius; chunkX++) {
			for (int chunkZ = centerChunkZ - scanRadius; chunkZ <= centerChunkZ + scanRadius; chunkZ++) {
				if (scannedChunkKeys.contains(chunkKey(scanDimension, chunkX, chunkZ))) {
					scanned++;
				}
			}
		}
		return scanned;
	}

	public int getScannableChunkCount() {
		int side = (scanRadius * 2) + 1;
		return side * side;
	}

	public int getScannedChunkCount() {
		return scannedChunkKeys.size();
	}

	public Set<String> getScannedChunkKeys() {
		return Set.copyOf(scannedChunkKeys);
	}

	/**
	 * Returns the center-to-center spacing that keeps adjacent scan windows disjoint.
	 */
	public int getWaypointSpacingBlocks() {
		return ((scanRadius * 2) + 1) * 16;
	}

	/**
	 * Creates a new pair of CSV files for a scan session and writes their metadata and headers.
	 */
	public void startScanSession(String dimension) {
		try {
			Files.createDirectories(outputDirectory);
			scannedChunkKeys.clear();
			queuedChunkKeys.clear();
			scanQueue.clear();
			scanStartTime = FILE_TIMESTAMP.format(Instant.now());
			scanDimension = dimension;
			scannedChunksFile = outputDirectory.resolve("scanned_chunks_" + scanStartTime + ".csv");
			scannedBlocksFile = outputDirectory.resolve("scanned_blocks_" + scanStartTime + ".csv");

			createCsvFile(scannedChunksFile, "chunk_x,chunk_z");
			createCsvFile(scannedBlocksFile, "block_id,x,y,z");
			scannedChunksWriter = openAppendWriter(scannedChunksFile);
			scannedBlocksWriter = openAppendWriter(scannedBlocksFile);
			pendingRows = 0;
			lastFlushAt = System.nanoTime();
			scanStartedAtNs = lastFlushAt;
		} catch (IOException exception) {
			BlockScannerMod.LOGGER.warn("Unable to create scan CSV files: {}", exception.getMessage());
			closeWriters();
			scannedChunksFile = null;
			scannedBlocksFile = null;
		}
	}

	public boolean isInScanDimension(String dimension) {
		return scanDimension != null && scanDimension.equals(dimension);
	}

	public String getScanDimension() {
		return scanDimension;
	}

	public long getScanElapsedSeconds() {
		if (scanStartedAtNs == 0L) {
			return 0L;
		}
		return (System.nanoTime() - scanStartedAtNs) / 1_000_000_000L;
	}

	/**
	 * Records the UTC end time in both active CSV files.
	 */
	public void endScanSession() {
		flushWriters();
		closeWriters();
		String endTime = FILE_TIMESTAMP.format(Instant.now());
		updateEndTime(scannedChunksFile, endTime);
		updateEndTime(scannedBlocksFile, endTime);
	}

	public void flushScanSession() {
		flushWriters();
	}

	/**
	 * Scans the player's current chunk and writes matching blocks to CSV.
	 */
	public void scanAroundPlayer(MinecraftClient client) {
		if (client == null || client.world == null || client.player == null) {
			return;
		}

		ChunkPos center = client.player.getChunkPos();
		scanWindow(client.world, center.x, center.z, scanRadius);
		processScanQueue(client.world);
	}

	/**
	 * Scans a square of already loaded chunks around a center chunk.
	 */
	public void scanWindow(World world, int centerChunkX, int centerChunkZ, int radius) {
		if (world == null || radius < 0) {
			return;
		}
		for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
			for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
				if (scanQueue.size() >= MAX_QUEUED_CHUNKS) {
					return;
				}
				String key = chunkKey(scanDimension, chunkX, chunkZ);
				if (!scannedChunkKeys.contains(key) && queuedChunkKeys.add(key)) {
					scanQueue.offer(new ChunkPos(chunkX, chunkZ));
				}
			}
		}
	}

	private void processScanQueue(World world) {
		int chunksToProcess = scanQueue.size();
		while (chunksToProcess-- > 0 && !scanQueue.isEmpty()) {
			ChunkPos chunkPos = scanQueue.poll();
			if (chunkPos == null) {
				break;
			}
			String key = chunkKey(scanDimension, chunkPos.x, chunkPos.z);
			queuedChunkKeys.remove(key);
			if (!scanChunk(world, chunkPos) && !scannedChunkKeys.contains(key) && queuedChunkKeys.add(key)) {
				scanQueue.offer(chunkPos);
			}
		}
		flushIfDue();
	}

	//Scans one loaded chunk. Unloaded chunks are ignored and are not logged as scanned
	public boolean scanChunk(World world, ChunkPos chunkPos) {
		if (world == null || chunkPos == null) {return false;}
		
        String dimension = world.getRegistryKey().getValue().toString();
		if (!isInScanDimension(dimension)) {return false;}
		String chunkKey = chunkKey(dimension, chunkPos.x, chunkPos.z);
		if (scannedChunkKeys.contains(chunkKey)) {return true;}

		Chunk chunk = world.getChunkManager().getChunk(chunkPos.x,chunkPos.z,ChunkStatus.FULL,false);
		if (chunk == null) {return false;}

		BlockPos.Mutable blockPos = new BlockPos.Mutable();
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				for (int y = world.getBottomY() + world.getHeight() - 1; y >= world.getBottomY(); y--) {
					blockPos.set(chunkPos.getStartX() + x, y, chunkPos.getStartZ() + z);
					Block block = world.getBlockState(blockPos).getBlock();
					if (ignoredBlocks.contains(block)) {continue;}

					String blockId = blockIdCache.computeIfAbsent(
						block,
						cachedBlock -> Registries.BLOCK.getId(cachedBlock).toString()
					);
					writeBlockResult(blockId, blockPos, dimension);
				}
			}
		}
		scannedChunkKeys.add(chunkKey);
		writeScannedChunk(chunkPos, dimension);
		return true;
	}

	public Set<Block> getIgnoredBlocks() {
		return Set.copyOf(ignoredBlocks);
	}

	public boolean isIgnoredBlock(Block block) {
		return ignoredBlocks.contains(block);
	}

	public void toggleIgnoredBlock(Block block) {
		if (block == null) {
			return;
		}
		if (!ignoredBlocks.add(block)) {
			ignoredBlocks.remove(block);
		}
	}

	public void setIgnoredBlocks(Set<Block> blocks) {
		if (blocks == null) {
			throw new IllegalArgumentException("Ignored blocks cannot be null");
		}
		ignoredBlocks.clear();
		ignoredBlocks.addAll(blocks);
	}

	private void writeScannedChunk(ChunkPos chunkPos, String dimension) {
		appendCsvRow(
			scannedChunksFile,
			chunkPos.x + "," + chunkPos.z
		);
	}

	// Writes a found block to the CSV file if it has not been logged yet.
	private void writeBlockResult(String blockId, BlockPos blockPos, String dimension) {
		appendCsvRow(
			scannedBlocksFile,
			csv(blockId) + "," + blockPos.getX() + "," + blockPos.getY() + "," + blockPos.getZ()
		);
	}

	private void createCsvFile(Path file, String header) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(
			file,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING
		)) {
			writer.write("# scan_start_utc=" + scanStartTime);
			writer.newLine();
			writer.write("# scan_end_utc=" + EMPTY_END_TIME);
			writer.newLine();
			writer.write("# dimension=" + csv(scanDimension));
			writer.newLine();
			writer.write(header);
			writer.newLine();
		}
	}

	// Appends a data row to an already-created CSV file.
	private void appendCsvRow(Path file, String row) {
		if (file == null || scannedChunksWriter == null || scannedBlocksWriter == null) {
			return;
		}

		try {
			BufferedWriter writer = file.equals(scannedChunksFile) ? scannedChunksWriter : scannedBlocksWriter;
			writer.write(row);
			writer.newLine();
			pendingRows++;
		} catch (IOException exception) {
			BlockScannerMod.LOGGER.warn("Unable to write scan CSV {}: {}", file, exception.getMessage());
		}
	}

	private BufferedWriter openAppendWriter(Path file) throws IOException {
		return Files.newBufferedWriter(file, StandardOpenOption.APPEND);
	}

	private void flushIfDue() {
		if (pendingRows >= FLUSH_ROW_LIMIT || System.nanoTime() - lastFlushAt >= FLUSH_INTERVAL_NS) {
			flushWriters();
		}
	}

	private void flushWriters() {
		try {
			if (scannedChunksWriter != null) {scannedChunksWriter.flush();}
			if (scannedBlocksWriter != null) {scannedBlocksWriter.flush();}
			pendingRows = 0;
			lastFlushAt = System.nanoTime();
		} catch (IOException exception) {
			BlockScannerMod.LOGGER.warn("Unable to flush scan CSV files: {}", exception.getMessage());
		}
	}

	private void closeWriters() {
		try {
			if (scannedChunksWriter != null) {scannedChunksWriter.close();}
			if (scannedBlocksWriter != null) {scannedBlocksWriter.close();}
		} catch (IOException exception) {
			BlockScannerMod.LOGGER.warn("Unable to close scan CSV files: {}", exception.getMessage());
		} finally {
			scannedChunksWriter = null;
			scannedBlocksWriter = null;
		}
	}

	private void updateEndTime(Path file, String endTime) {
		if (file == null) {
			return;
		}

		try (RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "rw")) {
			if (randomAccessFile.readLine() == null) {
				return;
			}
			long endTimeOffset = randomAccessFile.getFilePointer();
			String existingEndTimeLine = randomAccessFile.readLine();
			String replacement = "# scan_end_utc=" + endTime;
			if (existingEndTimeLine == null || existingEndTimeLine.length() != replacement.length()) {
				BlockScannerMod.LOGGER.warn("Unable to update scan end time in {}: invalid header", file);
				return;
			}
			randomAccessFile.seek(endTimeOffset);
			randomAccessFile.write(replacement.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (IOException exception) {
			BlockScannerMod.LOGGER.warn("Unable to record scan end time in {}: {}", file, exception.getMessage());
		}
	}

	private String chunkKey(String dimension, int chunkX, int chunkZ) {
		return dimension + ":" + chunkX + ":" + chunkZ;
	}

	private String csv(String value) {
		String escaped = value == null ? "" : value.replace("\"", "\"\"");
		return "\"" + escaped + "\"";
	}
}
