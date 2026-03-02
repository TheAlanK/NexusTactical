package com.nexustactical;

import com.nexusui.api.NexusPage;
import com.nexusui.overlay.NexusFrame;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * TacticalPage - Real-time combat fleet status built on NexusUI.
 *
 * Data-focused display showing hull, armor, flux, CR, weapon damage,
 * and engine status for all deployed player ships.
 *
 * <p>The inner liveTimer (150ms) is the sole data controller.
 * {@link #refresh()} is intentionally a no-op to prevent the slower
 * NexusFrame refresh cycle from re-assigning stale snapshots, which
 * would cause visible flashing between ship data and "Not in combat".</p>
 */
public class TacticalPage implements NexusPage {

    private TacticalPanel panel;
    private CombatTracker.CombatSnapshot data;

    // ========================================================================
    // Pre-allocated colors (avoid GC pressure from per-frame Color allocations)
    // ========================================================================

    private static final Color STATUS_DISABLED = new Color(255, 80, 80, 150);

    private static final Color FLUX_HARD = new Color(255, 140, 40, 200);
    private static final Color FLUX_SOFT = new Color(100, 220, 255, 160);
    private static final Color FLUX_BORDER_NORMAL = withAlpha(NexusFrame.CYAN, 60);
    private static final Color FLUX_BORDER_OVERLOADED = withAlpha(NexusFrame.RED, 60);

    private static final Color BAR_FILL_GREEN = withAlpha(NexusFrame.GREEN, 180);
    private static final Color BAR_FILL_YELLOW = withAlpha(NexusFrame.YELLOW, 180);
    private static final Color BAR_FILL_RED = withAlpha(NexusFrame.RED, 180);

    private static final Color BAR_BORDER_GREEN = withAlpha(NexusFrame.GREEN, 60);
    private static final Color BAR_BORDER_YELLOW = withAlpha(NexusFrame.YELLOW, 60);
    private static final Color BAR_BORDER_RED = withAlpha(NexusFrame.RED, 60);

    private static final AlphaComposite DISABLED_COMPOSITE =
            AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f);

    private static Color withAlpha(Color c, int alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    // ========================================================================
    // NexusPage interface
    // ========================================================================

    public String getId() { return "nexus_tactical"; }
    public String getTitle() { return "Tactical"; }

    public JPanel createPanel(int port) {
        panel = new TacticalPanel();
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setBackground(NexusFrame.BG_PRIMARY);
        scroll.getViewport().setBackground(NexusFrame.BG_PRIMARY);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        scroll.getVerticalScrollBar().setBackground(NexusFrame.BG_SECONDARY);
        scroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            protected void configureScrollBarColors() {
                this.thumbColor = NexusFrame.BORDER;
                this.trackColor = NexusFrame.BG_SECONDARY;
            }
        });

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(NexusFrame.BG_PRIMARY);
        wrapper.add(scroll, BorderLayout.CENTER);
        return wrapper;
    }

    /**
     * No-op. The liveTimer (150ms) is the sole data controller.
     * Allowing the NexusFrame refresh to reassign stale snapshots causes
     * visible flashing between ship data and "Not in combat".
     */
    public void refresh() {
        // Intentionally empty — liveTimer handles all data updates
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static Color barFillColor(float level) {
        if (level > 0.7f) return BAR_FILL_GREEN;
        if (level > 0.4f) return BAR_FILL_YELLOW;
        return BAR_FILL_RED;
    }

    private static Color barBorderColor(float level) {
        if (level > 0.7f) return BAR_BORDER_GREEN;
        if (level > 0.4f) return BAR_BORDER_YELLOW;
        return BAR_BORDER_RED;
    }

    private static Color hullSizeColor(String hullSize) {
        if ("Capital".equals(hullSize)) return NexusFrame.RED;
        if ("Cruiser".equals(hullSize)) return NexusFrame.ORANGE;
        if ("Destroyer".equals(hullSize)) return NexusFrame.YELLOW;
        if ("Frigate".equals(hullSize)) return NexusFrame.CYAN;
        return NexusFrame.TEXT_SECONDARY;
    }

    private static String statusText(CombatTracker.ShipSnapshot ship) {
        if (!ship.isAlive) return "DISABLED";
        if (ship.isOverloaded) return "OVERLOADED";
        if (ship.isVenting) return "VENTING";
        if (ship.isRetreating) return "RETREATING";
        if (ship.isPhased) return "PHASED";
        return "Active";
    }

    private static Color statusColor(CombatTracker.ShipSnapshot ship) {
        if (!ship.isAlive) return STATUS_DISABLED;
        if (ship.isOverloaded) return NexusFrame.RED;
        if (ship.isVenting) return NexusFrame.YELLOW;
        if (ship.isRetreating) return NexusFrame.ORANGE;
        if (ship.isPhased) return NexusFrame.CYAN;
        return NexusFrame.GREEN;
    }

    private static float avgArmor(CombatTracker.ShipSnapshot ship) {
        if (ship.armorFractions == null || ship.gridCols == 0 || ship.gridRows == 0) return 1f;
        float sum = 0;
        int count = 0;
        for (int x = 0; x < ship.gridCols; x++) {
            for (int y = 0; y < ship.gridRows; y++) {
                sum += ship.armorFractions[x][y];
                count++;
            }
        }
        return count > 0 ? sum / count : 1f;
    }

    // ========================================================================
    // TacticalPanel — inner panel with liveTimer-driven rendering
    // ========================================================================

    private class TacticalPanel extends JPanel {

        private static final int CARD_W = 430;
        private static final int CARD_GAP = 10;
        private static final int PADDING = 14;
        private static final int CARD_PADDING = 12;
        private static final int COLS = 2;
        private static final int BAR_H = 13;
        private static final int BAR_RADIUS = 4;
        private static final int CARD_RADIUS = 8;
        private static final int MAX_DAMAGED_SHOWN = 4;
        private static final int DAMAGED_LINE_H = 13;
        private static final int LIVE_TIMER_MS = 150;
        private static final long STALE_THRESHOLD_MS = 1000L;

        private final Timer liveTimer;
        private long lastTimestamp = 0;

        TacticalPanel() {
            setBackground(NexusFrame.BG_PRIMARY);
            liveTimer = new Timer(LIVE_TIMER_MS, new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    CombatTracker.CombatSnapshot snap = CombatTracker.getSnapshot();

                    // Snapshot is null (never entered combat, or cleared on combat init)
                    if (snap == null) {
                        if (data != null) {
                            data = null;
                            lastTimestamp = 0;
                            revalidate();
                            repaint();
                        }
                        return;
                    }

                    // Snapshot is stale — combat has ended
                    long age = System.currentTimeMillis() - snap.timestamp;
                    if (age > STALE_THRESHOLD_MS) {
                        if (data != null) {
                            data = null;
                            lastTimestamp = 0;
                            revalidate();
                            repaint();
                        }
                        return;
                    }

                    // Fresh snapshot — update if timestamp changed
                    if (snap.timestamp != lastTimestamp) {
                        lastTimestamp = snap.timestamp;
                        data = snap;
                        revalidate();
                        repaint();
                    }
                }
            });
            liveTimer.start();
        }

        public void removeNotify() {
            super.removeNotify();
            liveTimer.stop();
        }

        public void addNotify() {
            super.addNotify();
            liveTimer.start();
        }

        private int cardHeight(CombatTracker.ShipSnapshot ship) {
            int h = 162;
            int damaged = ship.weaponsDisabled + ship.weaponsDestroyed;
            if (damaged > 0) {
                int show = Math.min(damaged, MAX_DAMAGED_SHOWN);
                h += 4 + show * DAMAGED_LINE_H;
                if (damaged > MAX_DAMAGED_SHOWN) h += 14;
            }
            return h;
        }

        public Dimension getPreferredSize() {
            CombatTracker.CombatSnapshot snap = data;
            if (snap == null || snap.playerShips.length == 0) {
                return new Dimension(900, 400);
            }
            int totalW = PADDING * 2 + COLS * CARD_W + (COLS - 1) * CARD_GAP;
            int rows = (snap.playerShips.length + COLS - 1) / COLS;
            int totalH = PADDING + 40;
            for (int r = 0; r < rows; r++) {
                int maxH = 0;
                for (int c = 0; c < COLS; c++) {
                    int idx = r * COLS + c;
                    if (idx < snap.playerShips.length) {
                        maxH = Math.max(maxH, cardHeight(snap.playerShips[idx]));
                    }
                }
                totalH += maxH + CARD_GAP;
            }
            totalH += PADDING;
            return new Dimension(totalW, totalH);
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

            CombatTracker.CombatSnapshot snap = data;
            if (snap == null) {
                drawNotInCombat(g2);
                g2.dispose();
                return;
            }

            int y = PADDING;
            y = drawHeader(g2, snap, y);

            int rows = (snap.playerShips.length + COLS - 1) / COLS;
            for (int r = 0; r < rows; r++) {
                int rowH = 0;
                for (int c = 0; c < COLS; c++) {
                    int idx = r * COLS + c;
                    if (idx < snap.playerShips.length) {
                        rowH = Math.max(rowH, cardHeight(snap.playerShips[idx]));
                    }
                }
                for (int c = 0; c < COLS; c++) {
                    int idx = r * COLS + c;
                    if (idx < snap.playerShips.length) {
                        int cx = PADDING + c * (CARD_W + CARD_GAP);
                        drawShipCard(g2, snap.playerShips[idx], cx, y, rowH);
                    }
                }
                y += rowH + CARD_GAP;
            }

            g2.dispose();
        }

        private void drawNotInCombat(Graphics2D g2) {
            g2.setFont(NexusFrame.FONT_TITLE);
            g2.setColor(NexusFrame.TEXT_MUTED);
            String msg = "Not in combat";
            FontMetrics fm = g2.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(msg)) / 2;
            int y = getHeight() / 2;
            g2.drawString(msg, x, y);

            g2.setFont(NexusFrame.FONT_SMALL);
            String hint = "Enter a battle to see fleet status";
            fm = g2.getFontMetrics();
            x = (getWidth() - fm.stringWidth(hint)) / 2;
            g2.drawString(hint, x, y + 24);
        }

        private int drawHeader(Graphics2D g2, CombatTracker.CombatSnapshot snap, int y) {
            g2.setFont(NexusFrame.FONT_TITLE);
            g2.setColor(NexusFrame.TEXT_PRIMARY);
            g2.drawString("FLEET STATUS", PADDING, y + 16);

            g2.setFont(NexusFrame.FONT_SMALL);
            int badgeX = PADDING + 140;

            g2.setColor(NexusFrame.GREEN);
            String deployed = snap.totalDeployed + " Deployed";
            g2.drawString(deployed, badgeX, y + 16);
            badgeX += g2.getFontMetrics().stringWidth(deployed) + 20;

            if (snap.disabledCount > 0) {
                g2.setColor(NexusFrame.RED);
                String disabled = snap.disabledCount + " Disabled";
                g2.drawString(disabled, badgeX, y + 16);
                badgeX += g2.getFontMetrics().stringWidth(disabled) + 20;
            }

            if (snap.retreatedCount > 0) {
                g2.setColor(NexusFrame.ORANGE);
                String retreated = snap.retreatedCount + " Retreated";
                g2.drawString(retreated, badgeX, y + 16);
                badgeX += g2.getFontMetrics().stringWidth(retreated) + 20;
            }

            if (snap.combatOver) {
                g2.setColor(NexusFrame.YELLOW);
                g2.drawString("BATTLE ENDED", badgeX, y + 16);
            }

            y += 24;
            g2.setColor(NexusFrame.BORDER);
            g2.drawLine(PADDING, y, getWidth() - PADDING, y);
            return y + CARD_GAP;
        }

        private void drawShipCard(Graphics2D g2, CombatTracker.ShipSnapshot ship,
                int x, int y, int cardH) {

            Color borderColor = ship.isFlagship ? NexusFrame.CYAN : NexusFrame.BORDER;

            // Card background
            g2.setColor(NexusFrame.BG_CARD);
            g2.fillRoundRect(x, y, CARD_W, cardH, CARD_RADIUS, CARD_RADIUS);
            g2.setColor(borderColor);
            g2.drawRoundRect(x, y, CARD_W, cardH, CARD_RADIUS, CARD_RADIUS);

            // Flagship accent bar
            if (ship.isFlagship) {
                g2.setColor(NexusFrame.CYAN);
                g2.fillRect(x, y + 2, 3, cardH - 4);
            }

            Composite origComposite = g2.getComposite();
            if (!ship.isAlive) {
                g2.setComposite(DISABLED_COMPOSITE);
            }

            int px = x + CARD_PADDING;
            int py = y + CARD_PADDING;
            int contentW = CARD_W - CARD_PADDING * 2;

            // ── Ship Name ──
            g2.setFont(NexusFrame.FONT_HEADER);
            g2.setColor(ship.isFlagship ? NexusFrame.CYAN : NexusFrame.TEXT_PRIMARY);
            String displayName = NexusFrame.truncate(ship.name, 28);
            g2.drawString(displayName, px, py + 12);

            if (ship.isFlagship) {
                int starX = px + g2.getFontMetrics().stringWidth(displayName) + 6;
                g2.setColor(NexusFrame.YELLOW);
                g2.drawString("\u2605", starX, py + 12);
            }

            // Hull size (right-aligned)
            g2.setFont(NexusFrame.FONT_SMALL);
            g2.setColor(hullSizeColor(ship.hullSize));
            String sizeStr = ship.hullSize;
            int sizeW = g2.getFontMetrics().stringWidth(sizeStr);
            g2.drawString(sizeStr, x + CARD_W - CARD_PADDING - sizeW, py + 12);

            py += 24;

            // ── Bars ──
            int barW = contentW;
            float armorLevel = avgArmor(ship);

            drawBar(g2, px, py, barW, BAR_H, "Hull", ship.hullLevel,
                    barFillColor(ship.hullLevel), barBorderColor(ship.hullLevel));
            py += BAR_H + 4;

            drawBar(g2, px, py, barW, BAR_H, "Armor", armorLevel,
                    barFillColor(armorLevel), barBorderColor(armorLevel));
            py += BAR_H + 4;

            drawFluxBar(g2, px, py, barW, BAR_H, ship);
            py += BAR_H + 4;

            drawBar(g2, px, py, barW, BAR_H, "CR", ship.currentCR,
                    barFillColor(ship.currentCR), barBorderColor(ship.currentCR));
            py += BAR_H + 6;

            // ── Status + Shield ──
            g2.setFont(NexusFrame.FONT_SMALL);
            g2.setColor(statusColor(ship));
            String status = statusText(ship);
            g2.drawString(status, px, py + 10);

            if (ship.hasShield) {
                String shieldStr = ship.shieldOn ? "Shield ON" : "Shield OFF";
                Color shieldC = ship.shieldOn ? NexusFrame.CYAN : NexusFrame.TEXT_MUTED;
                g2.setColor(shieldC);
                int shieldX = px + g2.getFontMetrics().stringWidth(status) + 16;
                g2.drawString(shieldStr, shieldX, py + 10);
            }

            py += 18;

            // ── Weapons Summary ──
            g2.setFont(NexusFrame.FONT_SMALL);
            int wOk = ship.weaponCount - ship.weaponsDisabled - ship.weaponsDestroyed;

            g2.setColor(NexusFrame.TEXT_SECONDARY);
            String wpnLabel = "Weapons: " + wOk + "/" + ship.weaponCount;
            g2.drawString(wpnLabel, px, py + 10);
            int wpnX = px + g2.getFontMetrics().stringWidth(wpnLabel);

            if (ship.weaponsDisabled > 0) {
                g2.setColor(NexusFrame.YELLOW);
                String dis = "  " + ship.weaponsDisabled + " off";
                g2.drawString(dis, wpnX, py + 10);
                wpnX += g2.getFontMetrics().stringWidth(dis);
            }
            if (ship.weaponsDestroyed > 0) {
                g2.setColor(NexusFrame.RED);
                String des = "  " + ship.weaponsDestroyed + " lost";
                g2.drawString(des, wpnX, py + 10);
            }

            py += 14;

            // ── Engines Summary ──
            int eOk = ship.engineCount - ship.enginesDisabled - ship.enginesDestroyed;
            g2.setColor(NexusFrame.TEXT_SECONDARY);
            String engLabel = "Engines: " + eOk + "/" + ship.engineCount;
            g2.drawString(engLabel, px, py + 10);
            int engX = px + g2.getFontMetrics().stringWidth(engLabel);

            if (ship.enginesDisabled > 0) {
                g2.setColor(NexusFrame.YELLOW);
                String dis = "  " + ship.enginesDisabled + " off";
                g2.drawString(dis, engX, py + 10);
                engX += g2.getFontMetrics().stringWidth(dis);
            }
            if (ship.enginesDestroyed > 0) {
                g2.setColor(NexusFrame.RED);
                String des = "  " + ship.enginesDestroyed + " lost";
                g2.drawString(des, engX, py + 10);
                engX += g2.getFontMetrics().stringWidth(des);
            }
            if (ship.enginesFlamedOut) {
                g2.setColor(NexusFrame.RED);
                g2.drawString("  FLAMED OUT", engX, py + 10);
            }

            py += 12;

            // ── Damaged Weapon Details ──
            int totalDamaged = ship.weaponsDisabled + ship.weaponsDestroyed;
            if (totalDamaged > 0 && ship.weapons != null) {
                py += 4;
                int shown = 0;
                for (int w = 0; w < ship.weapons.length && shown < MAX_DAMAGED_SHOWN; w++) {
                    CombatTracker.WeaponSnapshot wpn = ship.weapons[w];
                    if (!wpn.isDisabled && !wpn.isDestroyed) continue;

                    Color mColor = wpn.isDestroyed ? NexusFrame.RED : NexusFrame.YELLOW;
                    String marker = wpn.isDestroyed ? "X " : "! ";
                    String wpnInfo = wpn.name + " (" + wpn.size + " " + wpn.type + ")";
                    String stateStr = wpn.isDestroyed ? " DESTROYED" : " DISABLED";

                    g2.setColor(mColor);
                    g2.drawString(marker, px + 4, py + 10);
                    g2.setColor(NexusFrame.TEXT_SECONDARY);
                    g2.drawString(wpnInfo, px + 16, py + 10);
                    g2.setColor(mColor);
                    int endX = px + 16 + g2.getFontMetrics().stringWidth(wpnInfo);
                    g2.drawString(stateStr, endX, py + 10);

                    py += DAMAGED_LINE_H;
                    shown++;
                }
                if (totalDamaged > MAX_DAMAGED_SHOWN) {
                    g2.setColor(NexusFrame.TEXT_MUTED);
                    g2.drawString("  +" + (totalDamaged - MAX_DAMAGED_SHOWN) + " more...",
                            px + 4, py + 10);
                }
            }

            if (!ship.isAlive) {
                g2.setComposite(origComposite);
            }
        }

        // ── Bar rendering ──

        private void drawBar(Graphics2D g2, int x, int y, int w, int h,
                String label, float value, Color fill, Color border) {

            g2.setColor(NexusFrame.BG_SECONDARY);
            g2.fillRoundRect(x, y, w, h, BAR_RADIUS, BAR_RADIUS);

            int fillW = (int) (w * Math.max(0, Math.min(1, value)));
            if (fillW > 0) {
                g2.setColor(fill);
                g2.fillRoundRect(x, y, fillW, h, BAR_RADIUS, BAR_RADIUS);
            }

            g2.setColor(border);
            g2.drawRoundRect(x, y, w, h, BAR_RADIUS, BAR_RADIUS);

            g2.setFont(NexusFrame.FONT_SMALL);
            g2.setColor(NexusFrame.TEXT_PRIMARY);
            FontMetrics fm = g2.getFontMetrics();
            int textY = y + h - (h - fm.getAscent()) / 2 - 1;
            g2.drawString(label, x + 4, textY);

            String pct = (int) (value * 100) + "%";
            g2.drawString(pct, x + w - fm.stringWidth(pct) - 4, textY);
        }

        private void drawFluxBar(Graphics2D g2, int x, int y, int w, int h,
                CombatTracker.ShipSnapshot ship) {

            g2.setColor(NexusFrame.BG_SECONDARY);
            g2.fillRoundRect(x, y, w, h, BAR_RADIUS, BAR_RADIUS);

            int totalFillW = (int) (w * Math.max(0, Math.min(1, ship.fluxLevel)));
            if (totalFillW > 0) {
                int hardW = (int) (w * Math.max(0, Math.min(1, ship.hardFluxFraction)));
                if (hardW > 0) {
                    g2.setColor(FLUX_HARD);
                    g2.fillRoundRect(x, y, hardW, h, BAR_RADIUS, BAR_RADIUS);
                }
                if (totalFillW > hardW) {
                    g2.setColor(FLUX_SOFT);
                    g2.fillRoundRect(x + hardW, y, totalFillW - hardW, h, BAR_RADIUS, BAR_RADIUS);
                }
            }

            g2.setColor(ship.isOverloaded ? FLUX_BORDER_OVERLOADED : FLUX_BORDER_NORMAL);
            g2.drawRoundRect(x, y, w, h, BAR_RADIUS, BAR_RADIUS);

            g2.setFont(NexusFrame.FONT_SMALL);
            g2.setColor(NexusFrame.TEXT_PRIMARY);
            FontMetrics fm = g2.getFontMetrics();
            int textY = y + h - (h - fm.getAscent()) / 2 - 1;
            String label = ship.isOverloaded ? "FLUX!" : "Flux";
            g2.drawString(label, x + 4, textY);

            String pct = (int) (ship.fluxLevel * 100) + "%";
            g2.drawString(pct, x + w - fm.stringWidth(pct) - 4, textY);
        }
    }
}
