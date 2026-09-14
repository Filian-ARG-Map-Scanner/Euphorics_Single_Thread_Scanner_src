package com.blockscanner;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

//==========================================================//
//                      Control Screen                      //
//==========================================================//

public class ControllerScreen extends Screen {
	private static final Text TITLE = Text.literal("Euphoric's Fast Scam Controller");
	private int panelWidth;
	private int panelHeight;
	private int panelLeft;
	private int panelTop;
	private ButtonWidget modeButton;
	private ButtonWidget targetButton;
	private net.minecraft.client.gui.widget.TextFieldWidget targetXField;
	private net.minecraft.client.gui.widget.TextFieldWidget targetZField;
	private Text targetError;
	private ButtonWidget chunkTargetButton;
	private ButtonWidget spiralStartButton;
	private net.minecraft.client.gui.widget.TextFieldWidget chunkXField;
	private net.minecraft.client.gui.widget.TextFieldWidget chunkZField;
	private Text chunkTargetError;

	public ControllerScreen() {
		super(TITLE);
	}

	@Override
	protected void init() {
		panelWidth = (int) (this.width * 0.8D);
		panelHeight = (int) (this.height * 0.8D);
		panelLeft = (this.width - panelWidth) / 2;
		panelTop = (this.height - panelHeight) / 2;
		int v_height = (int) (2*(panelHeight/8)/3);
		int v_pad = (int) ((panelHeight/8)/12); //(int) ((panelHeight/8)/6);
		int h_width = (int) ((panelWidth - 0.3*panelWidth)/2);
		int h_pad = (int) (0.1*panelWidth/2);
		
		//X button
		this.addDrawableChild(ButtonWidget.builder(Text.literal("X"),button -> this.close())
			.dimensions(
				panelLeft + panelWidth - (int) ((panelHeight/8)/6) - (int) (2*(panelHeight/8)/3), 
				panelTop + (int) ((panelHeight/8)/6), 
				v_height, 
				v_height
			).build());
		
		//Start/stop button
		this.addDrawableChild(ButtonWidget.builder(Text.literal("Start/Stop"),button -> BlockScannerMod.toggleScanning(this.client))
			.dimensions(
				panelLeft + 2*h_pad, 
				panelTop + 3*v_pad + v_height, 
				h_width, 
				v_height
			).build());
		
		modeButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Auto/Manual"),button -> {BlockScannerMod.toggleAutoMode(); updateModeButton();})
			.dimensions(
				panelLeft + 2*h_pad, 
				panelTop + 7*v_pad + 3*v_height, 
				h_width, 
				v_height
			).build());
		
		updateModeButton();

		targetXField = new net.minecraft.client.gui.widget.TextFieldWidget(this.textRenderer, 
			(int) (panelLeft + 4.5*h_pad), 
			panelTop + 9*v_pad + 4*v_height, 
			(int) (h_width - 2.5*h_pad), 
			v_height, 
			Text.literal("Block X")
		);
		targetXField.setMaxLength(12);
		targetXField.setText(Integer.toString(BlockScannerMod.getManualTargetX()));
		this.addDrawableChild(targetXField);

		targetZField = new net.minecraft.client.gui.widget.TextFieldWidget(this.textRenderer, 
			(int) (panelLeft + h_width + 6.5*h_pad), 
			panelTop + 9*v_pad + 4*v_height, 
			(int) (h_width - 2.5*h_pad), 
			v_height, 
			Text.literal("Block Z")
		);
		targetZField.setMaxLength(12);
		targetZField.setText(Integer.toString(BlockScannerMod.getManualTargetZ()));
		this.addDrawableChild(targetZField);

		targetButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Go to Target Block"),button -> goToTarget())
			.dimensions(
				panelLeft + 2*h_pad, 
				panelTop + 11*v_pad + 5*v_height, 
				2*h_width + 2*h_pad, 
				v_height
			).build());

		chunkXField = new net.minecraft.client.gui.widget.TextFieldWidget(this.textRenderer, 
			(int) (panelLeft + 4.5*h_pad), 
			panelTop + 13*v_pad + 6*v_height, 
			(int) (h_width - 2.5*h_pad), 
			v_height, 
			Text.literal("Chunk X")
		);
		chunkXField.setMaxLength(12);
		chunkXField.setText(Integer.toString(BlockScannerMod.getManualChunkTargetX()));
		this.addDrawableChild(chunkXField);

		chunkZField = new net.minecraft.client.gui.widget.TextFieldWidget(this.textRenderer, 
			(int) (panelLeft + h_width + 6.5*h_pad), 
			panelTop + 13*v_pad + 6*v_height, 
			(int) (h_width - 2.5*h_pad), 
			v_height, 
			Text.literal("Chunk Z")
		);
		chunkZField.setMaxLength(12);
		chunkZField.setText(Integer.toString(BlockScannerMod.getManualChunkTargetZ()));
		this.addDrawableChild(chunkZField);

		chunkTargetButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Go to Target Chunk"),button -> goToTargetChunk())
			.dimensions(
				panelLeft + 2*h_pad, 
				panelTop + 15*v_pad + 7*v_height, 
				2*h_width + 2*h_pad, 
				v_height
			).build());

		spiralStartButton = this.addDrawableChild(ButtonWidget.builder(Text.literal("Start Spiral At Chunk"),button -> startSpiralAtChunk())
			.dimensions(
				panelLeft + 2*h_pad,
				panelTop + 17*v_pad + 8*v_height,
				2*h_width + 2*h_pad,
				v_height
			).build());

		updateTargetControls();
	}

	private void updateModeButton() {
		modeButton.active = !BlockScannerMod.isScanning();
		modeButton.setMessage(Text.literal("Auto/Manual"));
	}

	private void updateTargetControls() {
		boolean enabled = !BlockScannerMod.isScanning() || !BlockScannerMod.isAutoMode();
		targetXField.active = enabled;
		targetZField.active = enabled;
		targetButton.active = enabled;
		chunkXField.active = enabled;
		chunkZField.active = enabled;
		chunkTargetButton.active = enabled;
		spiralStartButton.active = !BlockScannerMod.isScanning();
	}

	private void startSpiralAtChunk() {
		try {
			int chunkX = Integer.parseInt(chunkXField.getText().trim());
			int chunkZ = Integer.parseInt(chunkZField.getText().trim());
			BlockScannerMod.startSpiralAtChunk(this.client, chunkX, chunkZ);
			chunkTargetError = null;
		} catch (NumberFormatException exception) {
			chunkTargetError = Text.literal("Enter valid chunk X and Z coordinates");
		}
	}

	private void goToTargetChunk() {
		try {
			int chunkX = Integer.parseInt(chunkXField.getText().trim());
			int chunkZ = Integer.parseInt(chunkZField.getText().trim());
			if (!BlockScannerMod.startManualChunkTarget(chunkX, chunkZ)) {
				return;
			}
			chunkTargetError = null;
		} catch (NumberFormatException exception) {
			chunkTargetError = Text.literal("Enter valid chunk X and Z coordinates");
		}
	}

	private void goToTarget() {
		try {
			int x = Integer.parseInt(targetXField.getText().trim());
			int z = Integer.parseInt(targetZField.getText().trim());
			if (!BlockScannerMod.startManualTarget(x, z)) {
				return;
			}
			targetError = null;
		} catch (NumberFormatException exception) {
			targetError = Text.literal("Enter valid X and Z coordinates");
		}
	}

	@Override
	public boolean keyPressed(KeyInput input) {
		if (input.key() == GLFW.GLFW_KEY_KP_9) {
			this.close();
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		panelWidth = (int) (this.width * 0.8D);
		panelHeight = (int) (this.height * 0.8D);
		panelLeft = (this.width - panelWidth) / 2;
		panelTop = (this.height - panelHeight) / 2;
		int v_height = (int) (2*(panelHeight/8)/3);
		int v_pad = (int) ((panelHeight/8)/12);
		int h_width = (int) ((panelWidth - 0.3*panelWidth)/2);
		int h_pad = (int) (0.1*panelWidth/2);

		context.fill(panelLeft - 1, panelTop - 1, panelLeft + panelWidth + 1, panelTop + panelHeight + 1, 0xFF101010);
		context.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight, 0xFFC6C6C6);
		context.fill(panelLeft + 4, panelTop + 4, panelLeft + panelWidth - 4, panelTop + panelHeight - 4, 0xFF8B8B8B);

		context.drawCenteredTextWithShadow(this.textRenderer, TITLE, 
			this.width / 2, 
			(int) (panelTop + 2.5*v_pad), 
			0xFFFFFFFF
		);

		updateModeButton();
		updateTargetControls();


		int statusLeft = panelLeft + h_width + 4*h_pad;
		int statusTop = panelTop + 3*v_pad + v_height;
		int statusRight = statusLeft + h_width;
		int statusBottom = statusTop + v_height;
		int statusColor = BlockScannerMod.isScanning() ? 0xFF55AA55 : 0xFFCC5555;
		context.fill(statusLeft, statusTop, statusRight, statusBottom, statusColor);
		Text status = Text.literal(BlockScannerMod.isScanning() ? "Scanning" : "Stopped");
		context.drawCenteredTextWithShadow(this.textRenderer, status,
			(statusLeft + statusRight) / 2,
			(int)((statusTop + statusBottom - h_pad/2) / 2),
			0xFFFFFFFF
		);

		boolean interlocked = BlockScannerMod.isScanning() && BlockScannerMod.isAutoMode();
		Text interlock = Text.literal(interlocked ? "INTERLOCK: ON" : "INTERLOCK: OFF");
		context.drawCenteredTextWithShadow(this.textRenderer, interlock,
			this.width / 2,
			(int) (panelTop + 6*v_pad + 2*v_height),
			interlocked ? 0xFFFF0000 : 0xFF00FF00
		);

		int modeLeft = panelLeft + h_width + 4*h_pad;
		int modeTop = panelTop + 7*v_pad + 3*v_height;
		int modeRight = modeLeft + h_width;
		int modeBottom = modeTop + v_height;
		int statusColor_mode = BlockScannerMod.isAutoMode() ? 0xFF55CCCC : 0xFFE6C84F;
		context.fill(modeLeft, modeTop, modeRight, modeBottom, statusColor_mode);
		Text mode = Text.literal(BlockScannerMod.isAutoMode() ? "Auto" : "Manual");
		context.drawCenteredTextWithShadow(this.textRenderer, mode,
			(modeLeft + modeRight) / 2,
			(int)((modeTop + modeBottom - h_pad/2) / 2),
			0xFFFFFFFF
		);

		context.drawTextWithShadow(this.textRenderer, Text.literal("Block X"), 
			panelLeft + 2*h_pad, 
			(int) (panelTop + 10.5*v_pad + 4*v_height), 
			0xFFFFFFFF);
		context.drawTextWithShadow(this.textRenderer, Text.literal("Block Z"), 
			panelLeft + h_width + 4*h_pad, 
			(int) (panelTop + 10.5*v_pad + 4*v_height), 
			0xFFFFFFFF);
		//if (targetError != null) {context.drawCenteredTextWithShadow(this.textRenderer, targetError, this.width / 2, panelTop + 178, 0xFF5555);}
		
		context.drawTextWithShadow(this.textRenderer, Text.literal("Chunk X"), 
			panelLeft + 2*h_pad, 
			(int) (panelTop + 14.5*v_pad + 6*v_height), 
			0xFFFFFFFF);
		context.drawTextWithShadow(this.textRenderer, Text.literal("Chunk Z"), 
			panelLeft + h_width + 4*h_pad, 
			(int) (panelTop + 14.5*v_pad + 6*v_height), 
			0xFFFFFFFF);
		//if (chunkTargetError != null) {context.drawCenteredTextWithShadow(this.textRenderer, chunkTargetError, this.width / 2, panelTop + 276, 0xFF5555);}

		super.render(context, mouseX, mouseY, delta);
	}
}
