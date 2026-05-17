package me.chamorot.playerstats;

import me.chamorot.playerstats.listeners.AfkListener;
import me.chamorot.playerstats.storage.StatsDatabase;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class PlayerStats extends JavaPlugin {

    private StatsDatabase db;
    private AfkListener afkListener;

    @Override
    public void onEnable() {
        // Open database first — other components depend on it.
        db = new StatsDatabase(this);
        if (!db.open()) {
            getLogger().severe("Could not open stats database. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        afkListener = new AfkListener(this, db);
        getServer().getPluginManager().registerEvents(afkListener, this);
        afkListener.start();

        PluginCommand statsCmd = getCommand("stats");
        if (statsCmd != null) {
            StatsCommand executor = new StatsCommand(this, afkListener);
            statsCmd.setExecutor(executor);
            statsCmd.setTabCompleter(executor);
        } else {
            getLogger().severe("Failed to register /stats — check plugin.yml!");
        }

        getLogger().info("PlayerStats v1.5.0 enabled! Made by chamorot.");
    }

    @Override
    public void onDisable() {
        if (afkListener != null) {
            afkListener.stop();
            // Save all online players before closing the DB.
            afkListener.saveAll();
        }
        if (db != null) {
            db.close();
        }
        getLogger().info("PlayerStats disabled.");
    }

    public StatsDatabase getDatabase() { return db; }
}
