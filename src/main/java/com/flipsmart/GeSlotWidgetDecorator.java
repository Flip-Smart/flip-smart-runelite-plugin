package com.flipsmart;

import com.flipsmart.domain.offer.OfferRecord;
import com.flipsmart.domain.offer.OfferSignal;
import com.flipsmart.util.TimeUtils;
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

    // Border-piece child indices within a GE slot widget (top,bottom,left,right,4 corners,divider,2 intersections,item box).
    // Values from Flipping Utilities' GeSpriteLoader; confirm vs current client in QA (AC1).
    static final int[] BORDER_CHILDREN = { 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17 };
    // "Buy"/"Sell"/"Empty" state-text child index; from FU SlotActivityTimer; confirm in QA (AC6).
    static final int STATE_TEXT_CHILD = 16;

    // Custom sprite-id namespace: high base to avoid colliding with game sprite ids.
    private static final int CUSTOM_SPRITE_BASE = 0x7F00_0000;

    private static final int SLOT_STATE_FONT_ID = 495;

    private final Client client;
    private final FlipSmartConfig config;
    private final FlipSmartPlugin plugin;
    private final SpriteManager spriteManager;

    // (vanillaSpriteId, tint) -> custom sprite id already registered in the override map
    private final Map<Long, Integer> registered = new HashMap<>();
    private int nextCustomId = CUSTOM_SPRITE_BASE;

    // slot index -> (borderChildIndex -> vanilla sprite id) captured before first override
    private final Map<Integer, Map<Integer, Integer>> vanillaBorderIds = new HashMap<>();

    private final Map<Integer, VanillaText> vanillaText = new HashMap<>();

    private static final class VanillaText
    {
        final String text;
        final int fontId;
        final int xAlignment;
        VanillaText(String text, int fontId, int xAlignment)
        {
            this.text = text;
            this.fontId = fontId;
            this.xAlignment = xAlignment;
        }
    }

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

    int customSpriteId(int vanillaSpriteId, SlotBorderTint tint)
    {
        Long k = key(vanillaSpriteId, tint);
        Integer existing = registered.get(k);
        if (existing != null)
        {
            return existing;
        }

        BufferedImage vanilla = spriteManager.getSprite(vanillaSpriteId, 0);
        if (vanilla == null)
        {
            // Not loaded yet; keep vanilla this tick, retry next reconcile.
            return vanillaSpriteId;
        }

        SpritePixels recolored = ImageUtil.getImageSpritePixels(SpriteRecolor.tint(vanilla, tint.getColor()), client);
        int id = nextCustomId++;
        client.getSpriteOverrides().put(id, recolored);
        registered.put(k, id);
        return id;
    }

    void applyBorder(int slot, Widget slotWidget, SlotBorderTint tint)
    {
        Map<Integer, Integer> captured = vanillaBorderIds
            .computeIfAbsent(slot, k -> new HashMap<>());

        for (int childIndex : BORDER_CHILDREN)
        {
            Widget piece = slotWidget.getChild(childIndex);
            if (piece == null)
            {
                continue;
            }
            captured.putIfAbsent(childIndex, piece.getSpriteId());
            int vanillaId = captured.get(childIndex);
            piece.setSpriteId(customSpriteId(vanillaId, tint));
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

    void applyStateText(int slot, Widget slotWidget, String label, String timer, String timerColorHex)
    {
        Widget text = slotWidget.getChild(STATE_TEXT_CHILD);
        if (text == null)
        {
            return;
        }
        vanillaText.computeIfAbsent(slot,
            k -> new VanillaText(text.getText(), text.getFontId(), text.getXTextAlignment()));

        text.setXTextAlignment(WidgetTextAlignment.LEFT);
        text.setFontId(SLOT_STATE_FONT_ID);
        text.setText(GeSlotStateText.build(label, timer, timerColorHex));
    }

    void revertStateText(int slot, Widget slotWidget)
    {
        Widget text = slotWidget.getChild(STATE_TEXT_CHILD);
        if (text == null)
        {
            return;
        }
        VanillaText v = vanillaText.get(slot);
        if (v == null)
        {
            return;
        }
        text.setText(v.text);
        text.setFontId(v.fontId);
        text.setXTextAlignment(v.xAlignment);
    }

    public void reconcile()
    {
        boolean bordersOn = config.highlightSlotBorders();
        boolean timersOn = config.showOfferTimers();

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
            reconcileStateText(slotWidget, slot, tracked, offer, timersOn);
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

    private void reconcileStateText(Widget slotWidget, int slot, OfferRecord tracked, GrandExchangeOffer offer, boolean timersOn)
    {
        long lastActivity = tracked == null ? 0L : tracked.getEffectiveLastActivityAtMillis();
        if (!timersOn || tracked == null || lastActivity <= 0)
        {
            revertStateText(slot, slotWidget);
            return;
        }

        boolean complete = offer.getState() == GrandExchangeOfferState.BOUGHT
            || offer.getState() == GrandExchangeOfferState.SOLD;
        String timer = complete && tracked.getCompletedAtMillis() > 0
            ? TimeUtils.formatFrozenElapsedTime(lastActivity, tracked.getCompletedAtMillis())
            : TimeUtils.formatElapsedTime(lastActivity);

        String label = OfferSignal.isBuyState(offer.getState()) ? "Buy" : "Sell";
        String colorHex = complete
            ? (config.colorblindMode() ? "0066cc" : "4cbb17")
            : "ffffff";

        applyStateText(slot, slotWidget, label, timer, colorHex);
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
        vanillaText.clear();
    }
}
