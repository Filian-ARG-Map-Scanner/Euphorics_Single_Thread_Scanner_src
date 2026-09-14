package com.blockscanner;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Main settings screen for the scanner.
 */
public class ScannerConfigScreen extends Screen {
	private int panelWidth;
	private int panelHeight;
	private int panelLeft;
	private int panelTop;
	private static final Text TITLE = Text.literal("Euphoric's Fast Scammer Blacklist Modifier");
	private static final int VISIBLE_ROWS = 5;
	private final List<Block> allBlocks = new ArrayList<>();
	private final List<Block> filteredBlocks = new ArrayList<>();
	private final List<ButtonWidget> blacklistButtons = new ArrayList<>();
	private TextFieldWidget searchField;
	private TextFieldWidget radiusField;
	private Text radiusError;
	private ButtonWidget radiusApplyButton;
	private int scrollOffset;

	public ScannerConfigScreen() {
		super(TITLE);
	}

	@Override
	protected void init() {
		allBlocks.clear();
		allBlocks.addAll(Registries.BLOCK.getIds().stream()
			.map(Registries.BLOCK::get)
			.filter(Objects::nonNull)
			.sorted(Comparator.comparing(block -> Registries.BLOCK.getId(block).toString()))
			.toList());

		panelWidth = (int) (this.width * 0.8D);
		panelHeight = (int) (this.height * 0.8D);
		panelLeft = (this.width - panelWidth) / 2;
		panelTop = (this.height - panelHeight) / 2;
		int v_height = (int) (2*(panelHeight/8)/3);
		int v_pad = (int) ((panelHeight/8)/6);
		int h_width = (int) ((panelWidth - 0.3*panelWidth)/2);
		int h_pad = (int) (0.1*panelWidth/2);
		
		//X button
		this.addDrawableChild(ButtonWidget.builder(Text.literal("X"),button -> this.close())
			.dimensions(
				panelLeft + panelWidth - v_pad - v_height, 
				panelTop + v_pad, 
				v_height, 
				v_height
			).build());

		this.addDrawableChild(ButtonWidget.builder(Text.literal("Print Blacklist"),button -> BlockScannerMod.printBlacklist(this.client))
			.dimensions(
				panelLeft + 2*h_pad, 
				panelTop + 3*v_pad + v_height, 
				2*h_width + 2*h_pad, 
				v_height
			).build());

		searchField = new TextFieldWidget(this.textRenderer,
			panelLeft + 2*h_pad, 
			panelTop + 5*v_pad + 2*v_height, 
			2*h_width + 2*h_pad, 
			v_height,
			Text.literal("Search block ID")
		);
		searchField.setMaxLength(128);
		searchField.setChangedListener(value -> refreshBlockList());
		this.addDrawableChild(searchField);

		for (int index = 0; index < VISIBLE_ROWS; index++) {
			int rowIndex = index;
			ButtonWidget button = ButtonWidget.builder(Text.literal("[ ]"),ignored -> toggleVisibleBlock(rowIndex))
				.dimensions(
					panelLeft + 2*h_pad, 
					panelTop + (7 + 2*index)*v_pad + (3 + index)*v_height,
					3*v_height/2, 
					v_height
				).build();
			blacklistButtons.add(this.addDrawableChild(button));
		}
		
		refreshBlockList();
		updateEditableControls();
	}

	private void contextualRadiusControls() {
		radiusField = new TextFieldWidget(
			this.textRenderer,
			30,
			42,
			55,
			20,
			Text.literal("Radius")
		);
		radiusField.setMaxLength(2);
		radiusField.setText(Integer.toString(BlockScannerMod.getBlockScanner().getScanRadius()));
		this.addDrawableChild(radiusField);
		radiusApplyButton = this.addDrawableChild(ButtonWidget.builder(
			Text.literal("Apply"),
			ignored -> applyRadius()
		).dimensions(90, 42, 55, 20).build());
		updateEditableControls();
	}

	private void updateEditableControls() {
		boolean enabled = !BlockScannerMod.isScanning();
		for (ButtonWidget button : blacklistButtons) {
			button.active = enabled;
		}
		if (radiusField != null) {
			radiusField.active = enabled;
		}
		if (radiusApplyButton != null) {
			radiusApplyButton.active = enabled;
		}
	}

	private void applyRadius() {
		if (BlockScannerMod.isScanning()) {
			return;
		}
		try {
			int radius = Integer.parseInt(radiusField.getText().trim());
			MinecraftClient client = MinecraftClient.getInstance();
			if (client.options.getViewDistance().getValue() < radius) {
				radiusError = Text.literal("Render distance is too low");
				return;
			}
			BlockScannerMod.getBlockScanner().setScanRadius(radius);
			BlockScannerMod.persistSettings();
			radiusError = null;
		} catch (IllegalArgumentException exception) {
			radiusError = Text.literal("Radius must be 1-32");
		}
	}

	private void refreshBlockList() {
		String query = searchField == null ? "" : searchField.getText().trim().toLowerCase();
		filteredBlocks.clear();
		for (Block block : allBlocks) {
			String id = Registries.BLOCK.getId(block).toString();
			if (query.isEmpty() || id.contains(query)) {
				filteredBlocks.add(block);
			}
		}
		scrollOffset = Math.min(scrollOffset, Math.max(0, filteredBlocks.size() - VISIBLE_ROWS));
		updateRowButtons();
	}

	private void updateRowButtons() {
		BlockScanner scanner = BlockScannerMod.getBlockScanner();
		for (int index = 0; index < blacklistButtons.size(); index++) {
			int blockIndex = scrollOffset + index;
			ButtonWidget button = blacklistButtons.get(index);
			if (blockIndex >= filteredBlocks.size()) {
				button.visible = false;
				continue;
			}
			button.visible = true;
			Block block = filteredBlocks.get(blockIndex);
			button.setMessage(Text.literal(scanner.isIgnoredBlock(block) ? "[x]" : "[ ]"));
		}
	}

	private void toggleVisibleBlock(int rowIndex) {
		if (BlockScannerMod.isScanning()) {
			return;
		}
		int blockIndex = scrollOffset + rowIndex;
		if (blockIndex < 0 || blockIndex >= filteredBlocks.size()) {
			return;
		}
		BlockScannerMod.getBlockScanner().toggleIgnoredBlock(filteredBlocks.get(blockIndex));
		BlockScannerMod.persistSettings();
		updateRowButtons();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		int maximumOffset = Math.max(0, filteredBlocks.size() - VISIBLE_ROWS);
		scrollOffset = (int) Math.max(0, Math.min(maximumOffset, scrollOffset - Math.signum(verticalAmount)));
		updateRowButtons();
		return true;
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_KP_8) {
			this.close();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		updateEditableControls();
		panelWidth = (int) (this.width * 0.8D);
		panelHeight = (int) (this.height * 0.8D);
		panelLeft = (this.width - panelWidth) / 2;
		panelTop = (this.height - panelHeight) / 2;
		int v_height = (int) (2*(panelHeight/8)/3);
		int v_pad = (int) ((panelHeight/8)/6);
		int h_pad = (int) (0.1*panelWidth/2);

		context.fill(panelLeft - 1, panelTop - 1, panelLeft + panelWidth + 1, panelTop + panelHeight + 1, 0xFF101010);
		context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xFFC6C6C6);
		context.fill(panelLeft + 4, panelTop + 4, panelLeft + panelWidth - 4, panelTop + panelHeight - 4, 0xFF8B8B8B);

		context.drawCenteredTextWithShadow(this.textRenderer, TITLE, 
			this.width / 2, 
			(int) (panelTop + 2.5*v_pad), 
			0xFFFFFFFF
		);

		for (int index = 0; index < VISIBLE_ROWS; index++) {
			int blockIndex = scrollOffset + index;
			if (blockIndex >= filteredBlocks.size()) {continue;}
			Block block = filteredBlocks.get(blockIndex);
			int img_horizonal_pos = panelLeft + 2*h_pad + 2*v_height;
			int text_horizonal_pos = img_horizonal_pos + v_height + h_pad;
			int rowY_pos = panelTop + (7 + 2*index)*v_pad + (3 + index)*v_height;
			context.drawItem(new ItemStack(block), 
				img_horizonal_pos, 
				rowY_pos
			);
			context.drawTextWithShadow(this.textRenderer,Text.literal(Registries.BLOCK.getId(block).toString()),
				text_horizonal_pos,
				rowY_pos,
				0xFFFFFFFF
			);
		}

		/*context.drawTextWithShadow(this.textRenderer, Text.literal("Scan radius"), 30, 32, 0xFFFFFFFF);
		if (radiusError != null) {
			context.drawTextWithShadow(this.textRenderer, radiusError, 30, 68, 0xFF5555);
		}
		BlockScanner scanner = BlockScannerMod.getBlockScanner();*/

		super.render(context, mouseX, mouseY, delta);
		if (BlockScannerMod.isScanning()) {
			Text interlock = Text.literal("⚠ INTERLOCK ON ⚠");
			float textScale = 3.0F;
			int boxWidth = Math.max(160, (int) (this.textRenderer.getWidth(interlock) * textScale) + 32);
			int boxHeight = (int) (this.textRenderer.fontHeight * textScale) + 16;
			int boxLeft = (this.width - boxWidth) / 2;
			int boxTop = (this.height - boxHeight) / 2;
			context.fill(boxLeft - 1, boxTop - 1, boxLeft + boxWidth + 1, boxTop + boxHeight + 1, 0xFFFF0000);
			context.fill(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight, 0xE0101010);
			context.getMatrices().pushMatrix();
			context.getMatrices().translate(this.width / 2.0F, boxTop + (boxHeight / 2.0F));
			context.getMatrices().scale(textScale, textScale);
			context.drawCenteredTextWithShadow(this.textRenderer, interlock, 0, -this.textRenderer.fontHeight / 2, 0xFFFF0000);
			context.getMatrices().popMatrix();
		}
	}
}
