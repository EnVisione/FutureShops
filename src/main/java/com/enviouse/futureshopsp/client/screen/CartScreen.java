package com.enviouse.futureshopsp.client.screen;

import com.enviouse.futureshopsp.client.ShopClientState;
import com.enviouse.futureshopsp.client.ShopColors;
import com.enviouse.futureshopsp.data.CatalogItem;
import com.enviouse.futureshopsp.network.ShopPackets;
import com.enviouse.futureshopsp.network.packets.C2SBuyRequestPacket;
import com.enviouse.futureshopsp.network.packets.C2SVerifyAdminCartPacket;
import com.enviouse.futureshopsp.network.packets.S2CVerifyCartResponsePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;

public class CartScreen extends AbstractShopScreen implements ShopScreenMarker {
    private final Screen parent;
    private int guiLeft;
    private int guiTop;
    private int guiW;
    private int guiH;
    private int visibleRows;
    private int scrollIndex;
    private Button checkoutButton;
    private boolean awaitingVerification = false;
    private ConfirmationModal confirmationModal = null;

    public CartScreen(Screen parent) {
        super(Component.translatable("gui.futureshops.cart.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        guiW = Math.max(360, this.width - 4);
        guiH = Math.max(250, this.height - 4);
        guiLeft = (this.width - guiW) / 2;
        guiTop = (this.height - guiH) / 2;
        visibleRows = Math.max(4, (guiH - 140) / 28);

        addRenderableWidget(Button.builder(Component.translatable("gui.futureshops.cart.back"), button -> onClose())
                .bounds(guiLeft + 10, guiTop + guiH - 24, 48, 18)
                .build());
        addRenderableWidget(Button.builder(Component.translatable("gui.futureshops.cart.clear_btn"), button -> {
            ShopClientState.clearCart();
            ShopClientState.clearCartVerification();
        }).bounds(guiLeft + 64, guiTop + guiH - 24, 48, 18).build());
        checkoutButton = addRenderableWidget(Button.builder(Component.translatable("gui.futureshops.cart.checkout_btn"), button -> requestVerifyAndCheckout())
                .bounds(guiLeft + guiW - 100, guiTop + guiH - 24, 90, 18)
                .build());
    }

    @Override
    public void tick() {
        super.tick();
        if (awaitingVerification && ShopClientState.isCartVerified()) {
            awaitingVerification = false;
            List<S2CVerifyCartResponsePacket.CartWarning> warnings = ShopClientState.getCartWarnings();
            if (warnings.isEmpty()) {
                // All OK — show confirmation modal before checkout
                showCheckoutModal();
            }
            // If warnings exist, they'll render — user must click Checkout again to force
        }
    }

    private void showCheckoutModal() {
        List<ShopClientState.CartEntry> entries = ShopClientState.getCartEntries();
        List<ConfirmationModal.SummaryLine> lines = new java.util.ArrayList<>();
        for (ShopClientState.CartEntry entry : entries) {
            CatalogItem item = ShopClientState.getCatalogItem(entry.listingId()).orElse(null);
            String name = item != null ? item.displayName() : entry.listingId();
            // Icon id must be a registry itemId (a valid ResourceLocation); the listingId is only the
            // cart key and may not parse. Fall back to the listingId string only when the row is unknown.
            String iconId = item != null ? item.itemId() : entry.listingId();
            lines.add(ConfirmationModal.SummaryLine.item(iconId, name + " ×" + entry.quantity()));
        }
        String totalStr = ShopUiUtil.formatMinorUnits(ShopClientState.getCartTotalMinorUnits());
        confirmationModal = new ConfirmationModal(
                "Confirm Checkout",
                lines,
                "Total: " + totalStr + " " + ShopClientState.getCurrencyName(),
                modal -> {
                    modal.setProcessing();
                    sendCheckout();
                },
                () -> confirmationModal = null
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ShopUiUtil.renderDimBackdrop(graphics, this.width, this.height);
        graphics.fill(guiLeft, guiTop, guiLeft + guiW, guiTop + guiH, ShopColors.SURFACE_BASE);
        ShopUiUtil.drawSoftOutline(graphics, guiLeft, guiTop, guiW, guiH, ShopColors.BORDER_STRONG, ShopColors.BORDER_SUBTLE);
        graphics.fill(guiLeft, guiTop, guiLeft + guiW, guiTop + 2, ShopColors.ACCENT_PRIMARY);
        renderHeader(graphics);
        renderRows(graphics);
        renderSummary(graphics);

        // Cart verification warnings
        List<S2CVerifyCartResponsePacket.CartWarning> warnings = ShopClientState.getCartWarnings();
        if (!warnings.isEmpty()) {
            int warnY = guiTop + guiH - 62;
            for (int wi = Math.min(warnings.size(), 3) - 1; wi >= 0; wi--) {
                S2CVerifyCartResponsePacket.CartWarning w = warnings.get(wi);
                String warnText = "§c⚠ #" + (w.cartLineIndex() + 1) + ": " + w.detail();
                graphics.drawString(this.font, this.font.plainSubstrByWidth(warnText, guiW - 30),
                        guiLeft + 10, warnY, ShopColors.ERROR, false);
                warnY -= 12;
            }
            if (warnings.size() > 3) {
                graphics.drawString(this.font, "§c... and " + (warnings.size() - 3) + " more warnings",
                        guiLeft + 10, warnY, ShopColors.ERROR, false);
            }
        }

        // Awaiting verification indicator
        if (awaitingVerification) {
            graphics.drawCenteredString(this.font, "§eVerifying cart...",
                    guiLeft + guiW / 2, guiTop + guiH - 62, ShopColors.ACCENT_ORANGE);
        }

        ShopUiUtil.renderStatusPanel(graphics, this.font, guiLeft + 10, Math.max(6, guiTop - 24), guiW - 20);
        checkoutButton.active = !ShopClientState.getCartEntries().isEmpty();
        super.render(graphics, mouseX, mouseY, partialTick);

        // Tooltip for cart "+" hover (Shift+Click: Max)
        List<ShopClientState.CartEntry> tooltipEntries = ShopClientState.getCartEntries();
        int listX = guiLeft + 10;
        int rowY = guiTop + 60;
        int listW = guiW - 20;
        for (int row = 0; row < visibleRows && row + scrollIndex < tooltipEntries.size(); row++) {
            int y = rowY + row * 28;
            int ctrlX = listX + listW - 130;
            if (mouseX >= ctrlX + 24 && mouseX <= ctrlX + 38 && mouseY >= y && mouseY <= y + 22) {
                graphics.renderTooltip(this.font, Component.translatable("gui.futureshops.cart.tooltip.shift_max"), mouseX, mouseY);
                break;
            }
        }

        // Spec §8: Render confirmation modal on top of everything
        if (confirmationModal != null) {
            confirmationModal.render(graphics, this.font, this.width, this.height, mouseX, mouseY);
            if (confirmationModal.shouldAutoDismiss()) {
                confirmationModal = null;
            }
        }
    }

    private void renderHeader(GuiGraphics graphics) {
        int hx = guiLeft + 8;
        int hy = guiTop + 8;
        int hw = guiW - 16;
        ShopUiUtil.renderHeroHeader(graphics, this.font, hx, hy, hw,
                this.title.getString(),
                "Review every cart line before checkout");
    }

    private void renderRows(GuiGraphics graphics) {
        int listX = guiLeft + 10;
        int listY = guiTop + 52;
        int listW = guiW - 20;
        int listH = guiH - 120;
        ShopUiUtil.renderCard(graphics, listX, listY, listW, listH);

        List<ShopClientState.CartEntry> entries = ShopClientState.getCartEntries();
        if (entries.isEmpty()) {
            graphics.drawCenteredString(this.font, Component.translatable("gui.futureshops.cart.empty"), listX + listW / 2, listY + listH / 2 - 8, ShopColors.TEXT_MUTED);
            graphics.drawCenteredString(this.font, Component.translatable("gui.futureshops.cart.empty_hint"), listX + listW / 2, listY + listH / 2 + 6, ShopColors.TEXT_FAINT);
            return;
        }

        int maxScroll = Math.max(0, entries.size() - visibleRows);
        scrollIndex = Math.max(0, Math.min(scrollIndex, maxScroll));
        int rowY = listY + 8;
        for (int row = 0; row < visibleRows && row + scrollIndex < entries.size(); row++) {
            ShopClientState.CartEntry entry = entries.get(row + scrollIndex);
            CatalogItem item = ShopClientState.getCatalogItem(entry.listingId()).orElse(null);
            if (item == null) continue;
            int y = rowY + row * 28;
            int rowBg = row % 2 == 0 ? ShopColors.SURFACE_RAISED : ShopColors.SURFACE_OVERLAY;
            ShopUiUtil.renderPanel(graphics, listX + 6, y, listW - 12, 22, rowBg, ShopColors.BORDER_SUBTLE);
            ShopUiUtil.renderItemIcon(graphics, this.font, item.itemId(), listX + 10, y + 3);

            // Name — scrolls (ping-pong) when too long so modded items with lengthy names stay readable.
            ShopUiUtil.renderScrollingString(graphics, this.font, item.displayName(),
                    listX + 30, y + 7, listW - 240, ShopColors.TEXT_STRONG);

            // Quantity controls
            int ctrlX = listX + listW - 130;
            graphics.drawString(this.font, "§7-", ctrlX, y + 7, ShopColors.TEXT_MUTED, false);
            graphics.drawString(this.font, "§f" + entry.quantity(), ctrlX + 14, y + 7, ShopColors.TEXT_STRONG, false);
            graphics.drawString(this.font, "§7+", ctrlX + 28, y + 7, ShopColors.TEXT_MUTED, false);

            // Price — currency amber
            long unitPrice = item.hasPromo() ? item.promoPrice() : item.buyPrice();
            String priceStr = this.font.plainSubstrByWidth(
                    ShopUiUtil.formatMinorUnits(saturatingMultiply(unitPrice, entry.quantity())), 60);
            graphics.drawString(this.font, priceStr, listX + listW - 86, y + 7, ShopColors.TEXT_CURRENCY, false);

            // Remove
            graphics.drawString(this.font, "§c✕", listX + listW - 22, y + 7, ShopColors.STATUS_DANGER, false);
        }
    }

    private void renderSummary(GuiGraphics graphics) {
        int x = guiLeft + 10;
        int y = guiTop + guiH - 56;
        int w = guiW - 20;
        graphics.fill(x, y, x + w, y + 24, ShopColors.SURFACE_RAISED);
        ShopUiUtil.drawBorder(graphics, x, y, w, 24, ShopColors.BORDER_MUTED);
        graphics.fill(x, y, x + w, y + 2, ShopColors.ACCENT_CURRENCY);
        // Item count — truncated
        String items = this.font.plainSubstrByWidth(ShopClientState.getCartTotalQuantity() + " items in cart", w / 2 - 10);
        graphics.drawString(this.font, items, x + 10, y + 8, ShopColors.TEXT_MUTED, false);
        // Total — amber
        String total = "Total: " + ShopUiUtil.formatMinorUnits(ShopClientState.getCartTotalMinorUnits());
        String clipped = this.font.plainSubstrByWidth(total, w / 2);
        graphics.drawString(this.font, clipped, x + w - this.font.width(clipped) - 10, y + 8, ShopColors.TEXT_CURRENCY, false);
    }

    private void requestVerifyAndCheckout() {
        List<ShopClientState.CartEntry> entries = ShopClientState.getCartEntries();
        if (entries.isEmpty()) return;

        // If we already have warnings (user saw them), force checkout on second click
        if (!ShopClientState.getCartWarnings().isEmpty()) {
            ShopClientState.clearCartVerification();
            sendCheckout();
            return;
        }

        // Build verification lines from cart state
        String shopId = ShopClientState.getActiveShopId();
        List<C2SVerifyAdminCartPacket.AdminCartLine> lines = entries.stream()
                .map(e -> {
                    CatalogItem item = ShopClientState.getCatalogItem(e.listingId()).orElse(null);
                    long expectedPrice = 0;
                    if (item != null) {
                        expectedPrice = item.hasPromo() ? item.promoPrice() : item.buyPrice();
                    }
                    return new C2SVerifyAdminCartPacket.AdminCartLine(e.listingId(), e.quantity(), expectedPrice);
                })
                .toList();
        ShopClientState.clearCartVerification();
        awaitingVerification = true;
        ShopPackets.sendToServer(new C2SVerifyAdminCartPacket(shopId, lines));
    }

    private void sendCheckout() {
        List<C2SBuyRequestPacket.LineItem> lines = ShopClientState.getCartEntries().stream()
                .map(entry -> new C2SBuyRequestPacket.LineItem(entry.listingId(), entry.quantity()))
                .toList();
        if (!lines.isEmpty()) {
            ShopPackets.sendToServer(C2SBuyRequestPacket.cart(ShopClientState.getActiveShopId(), lines,
                    ShopClientState.getSnapshotRevision()));
        }
    }

    private static long saturatingMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ignored) {
            return (left < 0L) == (right < 0L) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double delta) {
        List<ShopClientState.CartEntry> entries = ShopClientState.getCartEntries();
        if (entries.size() > visibleRows) {
            scrollIndex = Math.max(0, Math.min(entries.size() - visibleRows, scrollIndex - (int) delta));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (confirmationModal != null) {
            return confirmationModal.mouseClicked(mouseX, mouseY, button, this.font);
        }
        int listX = guiLeft + 10;
        int rowY = guiTop + 60;
        int listW = guiW - 20;
        List<ShopClientState.CartEntry> entries = ShopClientState.getCartEntries();
        for (int row = 0; row < visibleRows && row + scrollIndex < entries.size(); row++) {
            ShopClientState.CartEntry entry = entries.get(row + scrollIndex);
            int y = rowY + row * 28;
            int ctrlX = listX + listW - 130;
            // Minus
            if (mouseX >= ctrlX && mouseX <= ctrlX + 10 && mouseY >= y && mouseY <= y + 22) {
                ShopClientState.setCartQuantity(entry.listingId(), entry.quantity() - 1);
                return true;
            }
            // Plus (Shift+Click = Max)
            if (mouseX >= ctrlX + 24 && mouseX <= ctrlX + 38 && mouseY >= y && mouseY <= y + 22) {
                if (hasShiftDown()) {
                    CatalogItem item = ShopClientState.getCatalogItem(entry.listingId()).orElse(null);
                    int max = 2304;
                    if (item != null) {
                        if (!item.unlimited()) max = Math.min(max, Math.max(1, item.stock()));
                        long price = item.hasPromo() ? item.promoPrice() : item.buyPrice();
                        if (price > 0) {
                            long bal = ShopClientState.getCurrentBalanceMinorUnits();
                            max = Math.min(max, (int) Math.min(bal / price, 2304));
                        }
                    }
                    ShopClientState.setCartQuantity(entry.listingId(), Math.max(1, max));
                } else {
                    ShopClientState.setCartQuantity(entry.listingId(), entry.quantity() + 1);
                }
                return true;
            }
            // Remove
            if (mouseX >= listX + listW - 24 && mouseX <= listX + listW - 10 && mouseY >= y && mouseY <= y + 22) {
                ShopClientState.removeFromCart(entry.listingId());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (confirmationModal != null) {
            if (confirmationModal.keyPressed(keyCode)) return true;
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    public void onTransactionResult(boolean success, String message) {
        if (confirmationModal != null) {
            if (success) {
                confirmationModal.setSuccess(message);
            } else {
                confirmationModal.setFailed(message);
            }
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
