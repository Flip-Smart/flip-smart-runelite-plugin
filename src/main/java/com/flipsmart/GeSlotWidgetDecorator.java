package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.SpritePixels;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class GeSlotWidgetDecorator
{
    static final int GE_INTERFACE_GROUP = 465;
    static final int SLOT_CONTAINER_START = 7;
    static final int GE_MAX_SLOTS = 8;

    // Frame pieces (drawn as a thin outer-edge outline) + the item background box (light fill).
    // The full-width divider (13) is left vanilla so we don't touch the slot interior.
    static final int[] BORDER_CHILDREN = { 5, 6, 7, 8, 9, 10, 11, 12, 14, 15, 17 };

    // Which outer edge(s) each frame piece contributes to the slot's perimeter outline.
    // Item box (17) is absent -> edges 0 -> rendered as a light fill instead of an outline.
    private static final Map<Integer, Integer> BORDER_EDGES = new HashMap<>();
    static
    {
        BORDER_EDGES.put(5, SpriteOutline.TOP);
        BORDER_EDGES.put(6, SpriteOutline.BOTTOM);
        BORDER_EDGES.put(7, SpriteOutline.LEFT);
        BORDER_EDGES.put(8, SpriteOutline.RIGHT);
        BORDER_EDGES.put(9, SpriteOutline.TOP | SpriteOutline.LEFT);
        BORDER_EDGES.put(10, SpriteOutline.TOP | SpriteOutline.RIGHT);
        BORDER_EDGES.put(11, SpriteOutline.BOTTOM | SpriteOutline.LEFT);
        BORDER_EDGES.put(12, SpriteOutline.BOTTOM | SpriteOutline.RIGHT);
        BORDER_EDGES.put(14, SpriteOutline.LEFT);
        BORDER_EDGES.put(15, SpriteOutline.RIGHT);
    }

    private static final int OUTLINE_THICKNESS = 2;
    // Blend strength for the item-box fill: 0.5 keeps it light so the native texture shows through.
    private static final double TINT_STRENGTH = 0.5;

    // "Buy"/"Sell"/"Empty" state-text child; we left-align it (clear of the top-right timer) and
    // nudge it a few px off the border.
    static final int STATE_TEXT_CHILD = 16;
    private static final int STATE_TEXT_INSET_X = 12;

    // Custom sprite-id namespace: high base to avoid colliding with game sprite ids.
    private static final int CUSTOM_SPRITE_BASE = 0x7F00_0000;

    private final Client client;
    private final FlipSmartConfig config;
    private final FlipSmartPlugin plugin;
    private final SpriteManager spriteManager;

    // (vanillaSpriteId, tint) -> custom sprite id already registered in the override map
    private final Map<Long, Integer> registered = new HashMap<>();
    private int nextCustomId = CUSTOM_SPRITE_BASE;

    // slot index -> (recolored child index -> vanilla sprite id) captured before first override
    private final Map<Integer, Map<Integer, Integer>> vanillaBorderIds = new HashMap<>();

    // slot index -> the state-text widget's original x-alignment, captured before left-aligning it
    private final Map<Integer, Integer> vanillaTextAlignment = new HashMap<>();

    // slot index -> the state-text widget's original x position, captured before nudging it right
    private final Map<Integer, Integer> vanillaTextX = new HashMap<>();

    @Inject
    GeSlotWidgetDecorator(Client client, FlipSmartConfig config, FlipSmartPlugin plugin, SpriteManager spriteManager)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.spriteManager = spriteManager;
    }

    private static long key(int vanillaSpriteId, SlotBorderTint tint)
    {
        return ((long) vanillaSpriteId << 8) | tint.ordinal();
    }

    int customSpriteId(int vanillaSpriteId, SlotBorderTint tint, int edges, int width, int height,
                       int relX, int relY, int slotWidth, int slotHeight)
    {
        Long k = key(vanillaSpriteId, tint);
        Integer existing = registered.get(k);
        if (existing != null)
        {
            return existing;
        }

        BufferedImage image;
        if (edges == 0)
        {
            // Item box: a light fill of the vanilla sprite.
            BufferedImage vanilla = spriteManager.getSprite(vanillaSpriteId, 0);
            if (vanilla == null)
            {
                // Not loaded yet; keep vanilla this tick, retry next reconcile.
                return vanillaSpriteId;
            }
            image = SpriteRecolor.tint(vanilla, tint.getColor(), TINT_STRENGTH);
        }
        else
        {
            // Frame piece: a thin edge outline sized to the piece's rendered bounds (so the client
            // tiles it exactly once), positioned at the slot border via the piece's overhang offset.
            if (width <= 0 || height <= 0 || slotWidth <= 0 || slotHeight <= 0)
            {
                return vanillaSpriteId;
            }
            image = SpriteOutline.build(width, height, edges, tint.getColor(), OUTLINE_THICKNESS,
                relX, relY, slotWidth, slotHeight);
        }

        int id = nextCustomId++;
        client.getSpriteOverrides().put(id, ImageUtil.getImageSpritePixels(image, client));
        registered.put(k, id);
        return id;
    }

    void applyBorder(int slot, Widget slotWidget, SlotBorderTint tint)
    {
        Map<Integer, Integer> captured = vanillaBorderIds
            .computeIfAbsent(slot, k -> new HashMap<>());

        int slotWidth = slotWidget.getWidth();
        int slotHeight = slotWidget.getHeight();

        for (int childIndex : BORDER_CHILDREN)
        {
            Widget piece = slotWidget.getChild(childIndex);
            if (piece == null)
            {
                continue;
            }
            captured.putIfAbsent(childIndex, piece.getSpriteId());
            int vanillaId = captured.get(childIndex);
            int edges = BORDER_EDGES.getOrDefault(childIndex, 0);
            piece.setSpriteId(customSpriteId(vanillaId, tint, edges, piece.getWidth(), piece.getHeight(),
                piece.getRelativeX(), piece.getRelativeY(), slotWidth, slotHeight));
        }
    }

    void revertBorder(int slot, Widget slotWidget)
    {
        Map<Integer, Integer> captured = vanillaBorderIds.get(slot);
        if (captured == null)
        {
            return;
        }
        for (Map.Entry<Integer, Integer> e : captured.entrySet())
        {
            Widget piece = slotWidget.getChild(e.getKey());
            if (piece != null)
            {
                piece.setSpriteId(e.getValue());
            }
        }
    }

    public void reconcile()
    {
        boolean bordersOn = config.highlightSlotBorders();
        boolean decorate = bordersOn || config.showOfferTimers();

        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        if (offers == null)
        {
            return;
        }

        for (int slot = 0; slot < Math.min(offers.length, GE_MAX_SLOTS); slot++)
        {
            Widget slotWidget = client.getWidget(GE_INTERFACE_GROUP, SLOT_CONTAINER_START + slot);
            if (slotWidget == null || slotWidget.isHidden())
            {
                continue;
            }

            GrandExchangeOffer offer = offers[slot];
            if (offer.getState() == GrandExchangeOfferState.EMPTY)
            {
                revertBorder(slot, slotWidget);
                revertStateText(slot, slotWidget);
                continue;
            }

            OfferRecord tracked = plugin.getOfferStore().bySlot(slot);
            reconcileBorder(slotWidget, slot, tracked, bordersOn);
            if (decorate)
            {
                applyStateText(slot, slotWidget);
            }
            else
            {
                revertStateText(slot, slotWidget);
            }
        }
    }

    void applyStateText(int slot, Widget slotWidget)
    {
        Widget text = slotWidget.getChild(STATE_TEXT_CHILD);
        if (text == null)
        {
            return;
        }
        vanillaTextAlignment.putIfAbsent(slot, text.getXTextAlignment());
        vanillaTextX.putIfAbsent(slot, text.getOriginalX());

        text.setXTextAlignment(WidgetTextAlignment.LEFT);
        int targetX = vanillaTextX.get(slot) + STATE_TEXT_INSET_X;
        if (text.getOriginalX() != targetX)
        {
            text.setOriginalX(targetX);
            text.revalidate();
        }
    }

    void revertStateText(int slot, Widget slotWidget)
    {
        Integer alignment = vanillaTextAlignment.get(slot);
        if (alignment == null)
        {
            return;
        }
        Widget text = slotWidget.getChild(STATE_TEXT_CHILD);
        if (text == null)
        {
            return;
        }
        text.setXTextAlignment(alignment);
        Integer originalX = vanillaTextX.get(slot);
        if (originalX != null && text.getOriginalX() != originalX)
        {
            text.setOriginalX(originalX);
            text.revalidate();
        }
    }

    private void reconcileBorder(Widget slotWidget, int slot, OfferRecord tracked, boolean bordersOn)
    {
        if (!bordersOn)
        {
            revertBorder(slot, slotWidget);
            return;
        }
        java.util.Optional<SlotBorderTint> tint =
            SlotBorderTint.forOffer(plugin.calculateCompetitiveness(tracked), config.colorblindMode());
        if (tint.isPresent())
        {
            applyBorder(slot, slotWidget, tint.get());
        }
        else
        {
            revertBorder(slot, slotWidget);
        }
    }

    public void revertAll()
    {
        for (int slot = 0; slot < GE_MAX_SLOTS; slot++)
        {
            Widget slotWidget = client.getWidget(GE_INTERFACE_GROUP, SLOT_CONTAINER_START + slot);
            if (slotWidget != null)
            {
                revertBorder(slot, slotWidget);
                revertStateText(slot, slotWidget);
            }
        }
    }

    public void shutDownRevert()
    {
        revertAll();
        for (Integer id : registered.values())
        {
            client.getSpriteOverrides().remove(id);
        }
        registered.clear();
        vanillaBorderIds.clear();
        vanillaTextAlignment.clear();
        vanillaTextX.clear();
    }
}
