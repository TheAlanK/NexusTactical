package com.nexustactical;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.input.InputEventAPI;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * CombatTracker - Collects combat ship data including weapon and engine status.
 *
 * Registered as a combat plugin via data/config/settings.json "plugins" entry.
 * Builds an immutable snapshot every 0.1s and publishes via static volatile.
 */
public class CombatTracker extends BaseEveryFrameCombatPlugin {

    private static final Logger log = Logger.getLogger(CombatTracker.class);

    // ========================================================================
    // Immutable snapshot types
    // ========================================================================

    public static final class WeaponSnapshot {
        public final String name;
        public final String size;   // S, M, L
        public final String type;   // BAL, ENR, MSL, CMP, SYN, HYB, UNI
        public final boolean isDisabled;
        public final boolean isDestroyed;

        public WeaponSnapshot(String name, String size, String type,
                boolean isDisabled, boolean isDestroyed) {
            this.name = name;
            this.size = size;
            this.type = type;
            this.isDisabled = isDisabled;
            this.isDestroyed = isDestroyed;
        }
    }

    public static final class ShipSnapshot {
        public final String name;
        public final String hullId;
        public final String hullSize;
        public final int hullSizeOrdinal;
        public final boolean isFlagship;

        public final float hullLevel;
        public final float fluxLevel;
        public final float hardFluxFraction;
        public final float currentCR;

        public final boolean isOverloaded;
        public final boolean isVenting;
        public final boolean isPhased;
        public final boolean isAlive;
        public final boolean isRetreating;

        public final boolean hasShield;
        public final boolean shieldOn;
        public final float shieldLevel;

        public final float[][] armorFractions;
        public final int gridCols;
        public final int gridRows;

        public final int weaponCount;
        public final int weaponsDisabled;
        public final int weaponsDestroyed;
        public final WeaponSnapshot[] weapons;

        public final int engineCount;
        public final int enginesDisabled;
        public final int enginesDestroyed;
        public final boolean enginesFlamedOut;

        private ShipSnapshot(Builder b) {
            this.name = b.name;
            this.hullId = b.hullId;
            this.hullSize = b.hullSize;
            this.hullSizeOrdinal = b.hullSizeOrdinal;
            this.isFlagship = b.isFlagship;
            this.hullLevel = b.hullLevel;
            this.fluxLevel = b.fluxLevel;
            this.hardFluxFraction = b.hardFluxFraction;
            this.currentCR = b.currentCR;
            this.isOverloaded = b.isOverloaded;
            this.isVenting = b.isVenting;
            this.isPhased = b.isPhased;
            this.isAlive = b.isAlive;
            this.isRetreating = b.isRetreating;
            this.hasShield = b.hasShield;
            this.shieldOn = b.shieldOn;
            this.shieldLevel = b.shieldLevel;
            this.armorFractions = b.armorFractions;
            this.gridCols = b.gridCols;
            this.gridRows = b.gridRows;
            this.weaponCount = b.weaponCount;
            this.weaponsDisabled = b.weaponsDisabled;
            this.weaponsDestroyed = b.weaponsDestroyed;
            this.weapons = b.weapons;
            this.engineCount = b.engineCount;
            this.enginesDisabled = b.enginesDisabled;
            this.enginesDestroyed = b.enginesDestroyed;
            this.enginesFlamedOut = b.enginesFlamedOut;
        }

        public static final class Builder {
            String name, hullId, hullSize;
            int hullSizeOrdinal;
            boolean isFlagship;
            float hullLevel, fluxLevel, hardFluxFraction, currentCR;
            boolean isOverloaded, isVenting, isPhased, isAlive, isRetreating;
            boolean hasShield, shieldOn;
            float shieldLevel;
            float[][] armorFractions;
            int gridCols, gridRows;
            int weaponCount, weaponsDisabled, weaponsDestroyed;
            WeaponSnapshot[] weapons;
            int engineCount, enginesDisabled, enginesDestroyed;
            boolean enginesFlamedOut;

            public ShipSnapshot build() { return new ShipSnapshot(this); }
        }
    }

    public static final class CombatSnapshot {
        public final ShipSnapshot[] playerShips;
        public final int totalDeployed;
        public final int disabledCount;
        public final int retreatedCount;
        public final boolean combatOver;
        public final long timestamp;

        public CombatSnapshot(ShipSnapshot[] playerShips, int totalDeployed,
                int disabledCount, int retreatedCount, boolean combatOver,
                long timestamp) {
            this.playerShips = playerShips;
            this.totalDeployed = totalDeployed;
            this.disabledCount = disabledCount;
            this.retreatedCount = retreatedCount;
            this.combatOver = combatOver;
            this.timestamp = timestamp;
        }
    }

    // ========================================================================
    // Static volatile snapshot
    // ========================================================================

    private static volatile CombatSnapshot snapshot;

    public static CombatSnapshot getSnapshot() {
        return snapshot;
    }

    /** Clear the snapshot (called on combat start to avoid showing stale data). */
    public static void clearSnapshot() {
        snapshot = null;
    }

    // ========================================================================
    // Per-frame state
    // ========================================================================

    private float timer = 0f;
    private static final float SNAPSHOT_INTERVAL = 0.1f;

    @Override
    public void init(CombatEngineAPI engine) {
        // Clear stale data from any previous combat session
        clearSnapshot();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        timer += amount;
        if (timer < SNAPSHOT_INTERVAL) return;
        timer = 0f;

        try {
            buildSnapshot(engine);
        } catch (Exception e) {
            log.warn("NexusTactical: Snapshot build failed: " + e.getMessage());
        }
    }

    private void buildSnapshot(CombatEngineAPI engine) {
        ShipAPI flagship = engine.getPlayerShip();
        String flagshipId = flagship != null ? flagship.getId() : "";

        List<ShipAPI> allShips = engine.getShips();
        ArrayList<ShipSnapshot> playerShipList = new ArrayList<ShipSnapshot>();
        int disabledCount = 0;
        int retreatedCount = 0;

        for (int i = 0; i < allShips.size(); i++) {
            ShipAPI ship = allShips.get(i);

            if (ship.getOwner() != 0) continue;
            if (ship.isFighter()) continue;
            if (ship.isStation()) continue;

            ShipHullSpecAPI hullSpec = ship.getHullSpec();
            if (hullSpec == null) continue;

            ShipAPI.HullSize size = ship.getHullSize();
            if (size == ShipAPI.HullSize.DEFAULT || size == ShipAPI.HullSize.FIGHTER) continue;

            boolean alive = ship.isAlive();
            boolean retreating = ship.isRetreating();
            if (!alive && !ship.isHulk()) continue;
            if (!alive) disabledCount++;
            if (retreating) retreatedCount++;

            ShipSnapshot snap = buildShipSnapshot(ship, hullSpec, size,
                    ship.getId() != null && ship.getId().equals(flagshipId),
                    alive, retreating);
            playerShipList.add(snap);
        }

        sortShips(playerShipList);

        ShipSnapshot[] arr = playerShipList.toArray(new ShipSnapshot[0]);
        boolean combatOver = engine.isCombatOver();

        snapshot = new CombatSnapshot(arr, arr.length, disabledCount, retreatedCount,
                combatOver, System.currentTimeMillis());
    }

    private ShipSnapshot buildShipSnapshot(ShipAPI ship, ShipHullSpecAPI hullSpec,
            ShipAPI.HullSize size, boolean isFlagship, boolean alive, boolean retreating) {

        ShipSnapshot.Builder b = new ShipSnapshot.Builder();

        // Identity
        b.name = ship.getName() != null ? ship.getName() : hullSpec.getHullName();
        b.hullId = hullSpec.getHullId();
        b.hullSize = prettifyHullSize(size);
        b.hullSizeOrdinal = size.ordinal();
        b.isFlagship = isFlagship;

        // Hull & flux
        b.hullLevel = ship.getHullLevel();
        FluxTrackerAPI flux = ship.getFluxTracker();
        b.fluxLevel = flux.getFluxLevel();
        b.hardFluxFraction = flux.getMaxFlux() > 0
                ? flux.getHardFlux() / flux.getMaxFlux() : 0f;
        b.isOverloaded = flux.isOverloaded();
        b.isVenting = flux.isVenting();
        b.isPhased = ship.isPhased();
        b.currentCR = ship.getCurrentCR();
        b.isAlive = alive;
        b.isRetreating = retreating;

        // Shield
        ShieldAPI shield = ship.getShield();
        b.hasShield = shield != null
                && shield.getType() != ShieldAPI.ShieldType.NONE
                && shield.getType() != ShieldAPI.ShieldType.PHASE;
        b.shieldOn = b.hasShield && shield.isOn();
        b.shieldLevel = b.shieldOn ? 1f : 0f;

        // Armor grid
        ArmorGridAPI grid = ship.getArmorGrid();
        int cols = grid.getLeftOf() + 1 + grid.getRightOf();
        int rows = grid.getBelow() + 1 + grid.getAbove();
        float[][] armorFrac = new float[cols][rows];
        for (int x = 0; x < cols; x++) {
            for (int y = 0; y < rows; y++) {
                armorFrac[x][y] = grid.getArmorFraction(x, y);
            }
        }
        b.armorFractions = armorFrac;
        b.gridCols = cols;
        b.gridRows = rows;

        // Weapons
        collectWeapons(ship, b);

        // Engines
        collectEngines(ship, b);

        return b.build();
    }

    private void collectWeapons(ShipAPI ship, ShipSnapshot.Builder b) {
        List<WeaponAPI> allWeapons = ship.getAllWeapons();
        ArrayList<WeaponSnapshot> weaponList = new ArrayList<WeaponSnapshot>();
        int count = 0, disabled = 0, destroyed = 0;

        for (int w = 0; w < allWeapons.size(); w++) {
            WeaponAPI wpn = allWeapons.get(w);
            WeaponAPI.WeaponType wtype = wpn.getType();
            if (wtype == WeaponAPI.WeaponType.DECORATIVE
                    || wtype == WeaponAPI.WeaponType.SYSTEM
                    || wtype == WeaponAPI.WeaponType.STATION_MODULE) {
                continue;
            }

            count++;
            boolean isDestroyed = wpn.isPermanentlyDisabled();
            boolean isDisabled = wpn.isDisabled();
            if (isDestroyed) destroyed++;
            else if (isDisabled) disabled++;

            weaponList.add(new WeaponSnapshot(
                    wpn.getDisplayName(),
                    prettifyWeaponSize(wpn.getSize()),
                    prettifyWeaponType(wtype),
                    isDisabled && !isDestroyed, isDestroyed));
        }

        b.weaponCount = count;
        b.weaponsDisabled = disabled;
        b.weaponsDestroyed = destroyed;
        b.weapons = weaponList.toArray(new WeaponSnapshot[0]);
    }

    private void collectEngines(ShipAPI ship, ShipSnapshot.Builder b) {
        ShipEngineControllerAPI engCtrl = ship.getEngineController();
        b.enginesFlamedOut = engCtrl != null && engCtrl.isFlamedOut();
        int count = 0, disabled = 0, destroyed = 0;

        if (engCtrl != null) {
            List<ShipEngineControllerAPI.ShipEngineAPI> engines = engCtrl.getShipEngines();
            if (engines != null) {
                for (int e = 0; e < engines.size(); e++) {
                    ShipEngineControllerAPI.ShipEngineAPI eng = engines.get(e);
                    count++;
                    if (eng.isPermanentlyDisabled()) destroyed++;
                    else if (eng.isDisabled()) disabled++;
                }
            }
        }

        b.engineCount = count;
        b.enginesDisabled = disabled;
        b.enginesDestroyed = destroyed;
    }

    // ========================================================================
    // Sorting and utilities
    // ========================================================================

    private static void sortShips(ArrayList<ShipSnapshot> list) {
        for (int i = 1; i < list.size(); i++) {
            ShipSnapshot key = list.get(i);
            int j = i - 1;
            while (j >= 0 && compareShips(list.get(j), key) > 0) {
                list.set(j + 1, list.get(j));
                j--;
            }
            list.set(j + 1, key);
        }
    }

    private static int compareShips(ShipSnapshot a, ShipSnapshot b) {
        if (a.isFlagship && !b.isFlagship) return -1;
        if (!a.isFlagship && b.isFlagship) return 1;
        if (a.hullSizeOrdinal != b.hullSizeOrdinal) return b.hullSizeOrdinal - a.hullSizeOrdinal;
        if (a.isAlive && !b.isAlive) return -1;
        if (!a.isAlive && b.isAlive) return 1;
        return a.name.compareToIgnoreCase(b.name);
    }

    private static String prettifyHullSize(ShipAPI.HullSize size) {
        if (size == ShipAPI.HullSize.CAPITAL_SHIP) return "Capital";
        if (size == ShipAPI.HullSize.CRUISER) return "Cruiser";
        if (size == ShipAPI.HullSize.DESTROYER) return "Destroyer";
        if (size == ShipAPI.HullSize.FRIGATE) return "Frigate";
        return size.name();
    }

    private static String prettifyWeaponSize(WeaponAPI.WeaponSize size) {
        if (size == WeaponAPI.WeaponSize.SMALL) return "S";
        if (size == WeaponAPI.WeaponSize.MEDIUM) return "M";
        if (size == WeaponAPI.WeaponSize.LARGE) return "L";
        return "?";
    }

    private static String prettifyWeaponType(WeaponAPI.WeaponType type) {
        if (type == WeaponAPI.WeaponType.BALLISTIC) return "BAL";
        if (type == WeaponAPI.WeaponType.ENERGY) return "ENR";
        if (type == WeaponAPI.WeaponType.MISSILE) return "MSL";
        if (type == WeaponAPI.WeaponType.COMPOSITE) return "CMP";
        if (type == WeaponAPI.WeaponType.SYNERGY) return "SYN";
        if (type == WeaponAPI.WeaponType.HYBRID) return "HYB";
        if (type == WeaponAPI.WeaponType.UNIVERSAL) return "UNI";
        return "???";
    }
}
