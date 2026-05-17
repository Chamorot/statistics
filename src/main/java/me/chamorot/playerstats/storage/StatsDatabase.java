package me.chamorot.playerstats.storage;

import me.chamorot.playerstats.PlayerStats;

import java.io.File;
import java.sql.*;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQLite-backed persistence for stats that Bukkit does not track natively:
 *   - Total AFK seconds (accumulated across all sessions)
 *   - Total crop harvests (right-click harvests that don't break the block)
 *   - Total villager breeding events
 *
 * All public methods are safe to call from the main server thread.
 * Heavy I/O (save/load) should be dispatched async by callers where noted.
 *
 * Schema (auto-created on first start):
 *
 *   player_stats
 *     uuid          TEXT PRIMARY KEY
 *     afk_seconds   INTEGER NOT NULL DEFAULT 0
 *     crops_harvested INTEGER NOT NULL DEFAULT 0
 *     villagers_bred  INTEGER NOT NULL DEFAULT 0
 */
public class StatsDatabase {

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS player_stats (
                uuid              TEXT    PRIMARY KEY,
                afk_seconds       INTEGER NOT NULL DEFAULT 0,
                crops_harvested   INTEGER NOT NULL DEFAULT 0,
                villagers_bred    INTEGER NOT NULL DEFAULT 0
            )
            """;

    private static final String UPSERT = """
            INSERT INTO player_stats (uuid, afk_seconds, crops_harvested, villagers_bred)
                VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                afk_seconds     = excluded.afk_seconds,
                crops_harvested = excluded.crops_harvested,
                villagers_bred  = excluded.villagers_bred
            """;

    private static final String SELECT = """
            SELECT afk_seconds, crops_harvested, villagers_bred
              FROM player_stats
             WHERE uuid = ?
            """;

    private final PlayerStats plugin;
    private Connection connection;

    public StatsDatabase(PlayerStats plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    /**
     * Opens (or creates) the SQLite database file and ensures the schema
     * exists. Called synchronously from onEnable — fast enough for startup.
     */
    public boolean open() {
        File dbFile = new File(plugin.getDataFolder(), "stats.db");
        plugin.getDataFolder().mkdirs();
        try {
            // Load the server's built-in SQLite JDBC driver (provided by Paper)
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL;"); // better concurrency
                st.execute(CREATE_TABLE);
            }
            plugin.getLogger().info("Stats database opened: " + dbFile.getName());
            return true;
        } catch (ClassNotFoundException | SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to open stats database", e);
            return false;
        }
    }

    /** Closes the database connection cleanly. Called from onDisable. */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                plugin.getLogger().info("Stats database closed.");
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Error closing stats database", e);
        }
    }

    // ── Read / Write ───────────────────────────────────────────────────

    /**
     * Loads persisted stats for a player. Returns a zeroed record if the
     * player has never been seen before.
     *
     * Safe to call from any thread; does a single indexed SELECT.
     */
    public synchronized PlayerRecord load(UUID uuid) {
        try (PreparedStatement ps = connection.prepareStatement(SELECT)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PlayerRecord(
                            rs.getLong("afk_seconds"),
                            rs.getLong("crops_harvested"),
                            rs.getLong("villagers_bred")
                    );
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load stats for " + uuid, e);
        }
        return new PlayerRecord(0, 0, 0);
    }

    /**
     * Persists (upserts) a player record.
     *
     * Callers should invoke this from an async task to avoid stalling the
     * main thread, except during onDisable where async scheduling is unsafe.
     */
    public synchronized void save(UUID uuid, PlayerRecord record) {
        try (PreparedStatement ps = connection.prepareStatement(UPSERT)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, record.afkSeconds());
            ps.setLong(3, record.cropsHarvested());
            ps.setLong(4, record.villagersBred());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save stats for " + uuid, e);
        }
    }

    // ── Record ─────────────────────────────────────────────────────────

    /** Immutable snapshot of what we persist per player. */
    public record PlayerRecord(long afkSeconds, long cropsHarvested, long villagersBred) {}
}
