package me.chamorot.playerstats.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.chamorot.playerstats.PlayerStats;
import me.chamorot.playerstats.storage.StatsDatabase;
import me.chamorot.playerstats.storage.StatsDatabase.PlayerRecord;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks AFK time, crop harvests, and villager breeding — all persisted to SQLite.
 *
 * ── AFK tracking ───────────────────────────────────────────────────────
 * A player is considered AFK after AFK_THRESHOLD_TICKS of no meaningful activity.
 *
 * Internal AFK time is tracked strictly in TICKS to avoid integer-division
 * precision loss. Conversion to seconds happens only at display time (/ 20).
 *
 * ── Data loading state machine ─────────────────────────────────────────
 * A player's lifecycle goes through three states:
 *
 *   LOADING  → async DB read is in flight (set in onJoin, before load completes)
 *   LOADED   → DB values merged into baselines; normal operation
 *   (absent) → player not online
 *
 * Rules enforced by this state machine:
 *   - onQuit MUST NOT overwrite DB if the player is still LOADING.
 *     Instead it cancels the pending load and leaves DB untouched.
 *   - saveAll() (onDisable) skips players still in LOADING state for the same reason.
 *   - start() (handles /reload) treats already-online players exactly like onJoin:
 *     kicks off an async load so their baselines are populated correctly.
 *
 * ── Crop harvest / villager breeding ──────────────────────────────────
 * PlayerHarvestBlockEvent covers right-click harvests (no block break).
 * EntityBreedEvent covers villager breeding attributed to a player.
 * Both are persisted to SQLite; Bukkit's own statistics cover the rest.
 *
 * ── Thread safety ──────────────────────────────────────────────────────
 * All maps are ConcurrentHashMap. AsyncChatEvent bounces markActive() to
 * the main thread. DB saves from onQuit are dispatched async (safe since
 * onQuit is not onDisable). saveAll() from onDisable runs synchronously.
 */
public class AfkListener implements Listener {

    private static final long AFK_THRESHOLD_TICKS = 20L * 60 * 5; // 5 minutes

    /** Crops that Paper 1.21's right-click-harvest mechanic supports. */
    private static final Set<Material> HARVESTABLE_CROPS = Set.of(
            Material.WHEAT,
            Material.CARROTS,
            Material.POTATOES,
            Material.BEETROOTS,
            Material.NETHER_WART,
            Material.TORCHFLOWER_CROP,   // added 1.20 (Trails & Tales)
            Material.PITCHER_CROP        // added 1.20 (Trails & Tales)
    );

    // ── Loading state ──────────────────────────────────────────────────
    public enum LoadState { LOADING, LOADED }
    private final ConcurrentHashMap<UUID, LoadState> loadState = new ConcurrentHashMap<>();

    // ── AFK maps (main-thread mutations only unless noted) ─────────────
    private final ConcurrentHashMap<UUID, Long> lastActivity    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> afkSince        = new ConcurrentHashMap<>();
    /**
     * Accumulated AFK ticks from completed segments + historical DB data
     * (converted back to ticks on load). Unit: TICKS.
     * Converting to seconds only at display time avoids precision loss.
     */
    private final ConcurrentHashMap<UUID, Long> baseAfkTicks    = new ConcurrentHashMap<>();

    // ── Harvest / breeding maps ────────────────────────────────────────
    private final ConcurrentHashMap<UUID, Long> sessionCropsHarvested = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> sessionVillagersBred  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> baseCropsHarvested    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> baseVillagersBred     = new ConcurrentHashMap<>();

    private final PlayerStats plugin;
    private final StatsDatabase db;
    private BukkitTask checkTask;

    public AfkListener(PlayerStats plugin, StatsDatabase db) {
        this.plugin = plugin;
        this.db     = db;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Called from onEnable (and implicitly on /reload since the plugin is
     * re-enabled). For any player already online (hot-reload scenario) we
     * kick off the same async load as onJoin does, so their baselines are
     * correctly populated rather than silently zeroed.
     */
    public void start() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            scheduleLoad(p.getUniqueId());
        }
        checkTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now = currentTick();
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                UUID id   = player.getUniqueId();
                long last = lastActivity.getOrDefault(id, now);
                if (!afkSince.containsKey(id) && (now - last) >= AFK_THRESHOLD_TICKS) {
                    afkSince.put(id, last + AFK_THRESHOLD_TICKS);
                }
            }
        }, 100L, 100L);
    }

    public void stop() {
        if (checkTask != null) { checkTask.cancel(); checkTask = null; }
    }

    // ── Activity events ────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
            markActive(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e)   { markActive(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e)     { markActive(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e)     { markActive(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Player p) markActive(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) markActive(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) markActive(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) { markActive(e.getPlayer()); }

    /** Chat is async on Paper — bounce markActive() back to main thread. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player player = e.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> markActive(player));
    }

    // ── Crop harvest (right-click, no break) ──────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent e) {
        if (HARVESTABLE_CROPS.contains(e.getHarvestedBlock().getType())) {
            sessionCropsHarvested.merge(e.getPlayer().getUniqueId(), 1L, Long::sum);
            markActive(e.getPlayer());
        }
    }

    // ── Villager breeding ──────────────────────────────────────────────

    /**
     * Villagers breed autonomously by consuming food thrown on the ground —
     * the player never right-clicks them directly. Because of this,
     * EntityBreedEvent#getBreeder() is ALWAYS null for villagers; using it
     * would mean this stat stays permanently at 0.
     *
     * Instead we listen for CreatureSpawnEvent with reason BREEDING and
     * credit the nearest player within VILLAGER_BREED_RADIUS blocks.
     * This matches Minecraft's own "bred a villager" advancement logic.
     */
    private static final double VILLAGER_BREED_RADIUS = 8.0;

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVillagerBred(CreatureSpawnEvent e) {
        if (e.getEntityType() != EntityType.VILLAGER) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.BREEDING) return;

        Location loc = e.getLocation();
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Player p : e.getLocation().getWorld().getPlayers()) {
            if (!p.getWorld().equals(loc.getWorld())) continue;
            double dist = p.getLocation().distanceSquared(loc);
            if (dist < nearestDist && dist <= VILLAGER_BREED_RADIUS * VILLAGER_BREED_RADIUS) {
                nearestDist = dist;
                nearest = p;
            }
        }

        if (nearest != null) {
            sessionVillagersBred.merge(nearest.getUniqueId(), 1L, Long::sum);
        }
    }

    // ── Join / Quit ────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        scheduleLoad(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();

        // If the DB load hasn't completed yet, the baseline maps still hold zeros.
        // Saving now would overwrite the player's real historical data with zeros.
        // Cancel the pending load instead and leave the DB untouched — their data
        // is safe on disk exactly as it was when they joined.
        if (loadState.get(id) == LoadState.LOADING) {
            cleanupMaps(id);
            return;
        }

        flushAfkToBase(id);

        // Save async — onQuit runs on the main thread; blocking on disk I/O here
        // causes lag spikes when multiple players disconnect simultaneously.
        final long afkTicks  = baseAfkTicks.getOrDefault(id, 0L);
        final long crops     = baseCropsHarvested.getOrDefault(id, 0L)
                             + sessionCropsHarvested.getOrDefault(id, 0L);
        final long villagers = baseVillagersBred.getOrDefault(id, 0L)
                             + sessionVillagersBred.getOrDefault(id, 0L);

        cleanupMaps(id);

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                db.save(id, new PlayerRecord(afkTicks / 20L, crops, villagers)));
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * Returns the current load state for a player, or null if they are not online.
     * StatsCommand uses this to suppress zeroed stats while the async DB load is in flight.
     */
    public LoadState getLoadState(Player player) {
        return loadState.get(player.getUniqueId());
    }

    /**
     * Total AFK seconds across all sessions, including the current segment.
     * Division by 20 happens here — once — so no precision is lost during accumulation.
     */
    public long getAfkSeconds(Player player) {
        UUID id   = player.getUniqueId();
        long base = baseAfkTicks.getOrDefault(id, 0L);
        Long since = afkSince.get(id);
        if (since != null) base += (currentTick() - since);
        return base / 20L;
    }

    public boolean isAfk(Player player) {
        return afkSince.containsKey(player.getUniqueId());
    }

    public long getCropsHarvested(Player player) {
        UUID id = player.getUniqueId();
        return baseCropsHarvested.getOrDefault(id, 0L)
             + sessionCropsHarvested.getOrDefault(id, 0L);
    }

    public long getVillagersBred(Player player) {
        UUID id = player.getUniqueId();
        return baseVillagersBred.getOrDefault(id, 0L)
             + sessionVillagersBred.getOrDefault(id, 0L);
    }

    /**
     * Saves all fully-loaded online players synchronously.
     * Called from onDisable where async scheduling is not allowed.
     * Players still in LOADING state are skipped — their DB data is intact.
     */
    public void saveAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (loadState.get(id) != LoadState.LOADED) continue;
            flushAfkToBase(id);
            long afkTicks  = baseAfkTicks.getOrDefault(id, 0L);
            long crops     = baseCropsHarvested.getOrDefault(id, 0L)
                           + sessionCropsHarvested.getOrDefault(id, 0L);
            long villagers = baseVillagersBred.getOrDefault(id, 0L)
                           + sessionVillagersBred.getOrDefault(id, 0L);
            db.save(id, new PlayerRecord(afkTicks / 20L, crops, villagers));
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────

    /**
     * Marks a player as LOADING and kicks off an async DB load.
     * On completion, merges DB values into the baseline maps on the main thread
     * and transitions the player to LOADED.
     *
     * Safe to call from onJoin AND from start() (hot-reload). Using
     * loadState.putIfAbsent means a second concurrent call for the same UUID
     * (shouldn't happen in practice) is safely ignored.
     */
    private void scheduleLoad(UUID id) {
        if (loadState.putIfAbsent(id, LoadState.LOADING) != null) return; // already loading/loaded

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerRecord record = db.load(id);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                // Player may have quit before the load returned — don't write stale data.
                if (loadState.get(id) != LoadState.LOADING) return;

                // DB stores AFK as seconds; convert to ticks for internal precision.
                baseAfkTicks.merge(id, record.afkSeconds() * 20L, Long::sum);
                baseCropsHarvested.merge(id, record.cropsHarvested(), Long::sum);
                baseVillagersBred.merge(id, record.villagersBred(), Long::sum);

                // Initialise session maps and activity clock.
                lastActivity.putIfAbsent(id, currentTick());
                afkSince.remove(id);
                baseAfkTicks.putIfAbsent(id, 0L);
                baseCropsHarvested.putIfAbsent(id, 0L);
                baseVillagersBred.putIfAbsent(id, 0L);
                sessionCropsHarvested.putIfAbsent(id, 0L);
                sessionVillagersBred.putIfAbsent(id, 0L);

                loadState.put(id, LoadState.LOADED);
            });
        });
    }

    private void markActive(Player player) {
        UUID id = player.getUniqueId();
        lastActivity.put(id, currentTick());
        if (afkSince.containsKey(id)) flushAfkToBase(id);
    }

    /** Converts the current AFK segment to ticks and merges into baseAfkTicks. */
    private void flushAfkToBase(UUID id) {
        Long since = afkSince.remove(id);
        if (since != null) {
            baseAfkTicks.merge(id, currentTick() - since, Long::sum);
        }
    }

    /** Removes all in-memory state for a player (called on quit or aborted load). */
    private void cleanupMaps(UUID id) {
        loadState.remove(id);
        lastActivity.remove(id);
        afkSince.remove(id);
        baseAfkTicks.remove(id);
        baseCropsHarvested.remove(id);
        baseVillagersBred.remove(id);
        sessionCropsHarvested.remove(id);
        sessionVillagersBred.remove(id);
    }

    private long currentTick() {
        return plugin.getServer().getCurrentTick();
    }
}
