package com.flipsmart;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

import javax.inject.Inject;
import java.awt.*;
import java.util.Map;

public class FlipSmartOverlay extends OverlayPanel
{
	private final Client client;
	private final FlipSmartPlugin plugin;
	private final FlipSmartConfig config;

	@Inject
	private FlipSmartOverlay(Client client, FlipSmartPlugin plugin, FlipSmartConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showOverlay())
		{
			return null;
		}

		panelComponent.getChildren().clear();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Flip Smart")
			.color(Color.GREEN)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Status:")
			.right("Active")
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Min Profit:")
			.right(config.minimumProfit() + " GP")
			.build());

		// Count good flips in inventory
		if (config.highlightGoodFlips())
		{
			Map<Integer, FlipAnalysis> analysisCache = plugin.getItemAnalysisCache();
			long goodFlipCount = analysisCache.values().stream()
				.filter(analysis -> analysis.isGoodFlip(config.minEfficiencyScore()) && analysis.hasPositiveMargin())
				.count();

			// Add spacing
			panelComponent.getChildren().add(LineComponent.builder()
				.left("")
				.right("")
				.build());

			panelComponent.getChildren().add(LineComponent.builder()
				.left("Good Flips:")
				.right(String.valueOf(goodFlipCount))
				.leftColor(Color.CYAN)
				.rightColor(goodFlipCount > 0 ? Color.GREEN : Color.WHITE)
				.build());

			// Show best flip if any
			if (goodFlipCount > 0)
			{
				FlipAnalysis bestFlip = analysisCache.values().stream()
					.filter(analysis -> analysis.isGoodFlip(config.minEfficiencyScore()) && analysis.hasPositiveMargin())
					.max((a, b) -> {
						double scoreA = a.getEfficiency() != null && a.getEfficiency().getScore() != null 
							? a.getEfficiency().getScore() : 0;
						double scoreB = b.getEfficiency() != null && b.getEfficiency().getScore() != null 
							? b.getEfficiency().getScore() : 0;
						return Double.compare(scoreA, scoreB);
					})
					.orElse(null);

				if (bestFlip != null)
				{
					// Add spacing
					panelComponent.getChildren().add(LineComponent.builder()
						.left("")
						.right("")
						.build());

					panelComponent.getChildren().add(LineComponent.builder()
						.left("Best Item:")
						.right(bestFlip.getItemName())
						.leftColor(Color.CYAN)
						.rightColor(Color.YELLOW)
						.build());

					if (bestFlip.getCurrentPrices() != null && bestFlip.getCurrentPrices().getNetMargin() != null)
					{
						panelComponent.getChildren().add(LineComponent.builder()
							.left("  Net Margin:")
							.right(formatGP(bestFlip.getCurrentPrices().getNetMargin()))
							.leftColor(Color.CYAN)
							.rightColor(Color.GREEN)
							.build());
					}

					if (bestFlip.getEfficiency() != null && bestFlip.getEfficiency().getScore() != null)
					{
						panelComponent.getChildren().add(LineComponent.builder()
							.left("  Efficiency:")
							.right(String.format("%.1f", bestFlip.getEfficiency().getScore()))
							.leftColor(Color.CYAN)
							.rightColor(Color.GREEN)
							.build());
					}
				}
			}
		}

		return super.render(graphics);
	}

	private String formatGP(int amount)
	{
		if (amount >= 1_000_000)
		{
			return String.format("%.1fM", amount / 1_000_000.0);
		}
		else if (amount >= 1_000)
		{
			return String.format("%.1fK", amount / 1_000.0);
		}
		return String.valueOf(amount);
	}
}

