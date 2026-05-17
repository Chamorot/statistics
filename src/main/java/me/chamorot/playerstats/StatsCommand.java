package me.chamorot.playerstats;

import me.chamorot.playerstats.listeners.AfkListener;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class StatsCommand implements CommandExecutor, TabCompleter {

    private final PlayerStats plugin;
    private final AfkListener afkListener;

    public StatsCommand(PlayerStats plugin, AfkListener afkListener) {
        this.plugin      = plugin;
        this.afkListener = afkListener;
    }

    // ── Command handler ────────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        Player target;

        if (args.length >= 1) {
            // Permission check uses Adventure API — legacy &c codes don't render
            // in plugin.yml permission-message fields on Paper 1.21.
            if (!sender.hasPermission("playerstats.others")) {
                sender.sendMessage(Component.text("You don't have permission to view other players' stats.")
                        .color(NamedTextColor.RED));
                return true;
            }
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player '" + args[0] + "' not found or is offline.")
                        .color(NamedTextColor.RED));
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                // Console has implicit permission to look up any player.
                sender.sendMessage("Console must specify a player: /stats <player>");
                return true;
            }
            target = (Player) sender;
        }

        sendStats(sender, target);
        return true;
    }

    // ── Tab completion ─────────────────────────────────────────────────

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String label, @NotNull String[] args) {
        // Withhold suggestions from players without playerstats.others to avoid
        // leaking online player names.
        if (args.length == 1 && sender.hasPermission("playerstats.others")) {
            List<String> completions = new ArrayList<>();
            String partial = args[0].toLowerCase();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(partial)) {
                    completions.add(p.getName());
                }
            }
            return completions;
        }
        return List.of();
    }

    // ── Stat display ───────────────────────────────────────────────────

    private void sendStats(CommandSender sender, Player p) {
        // If the async DB load hasn't completed yet, all persistent baseline values
        // (AFK time, crops, villager breeds) would read as zero. Show a brief
        // message instead of displaying misleading zeroed-out stats.
        if (afkListener.getLoadState(p) == AfkListener.LoadState.LOADING) {
            sender.sendMessage(Component.text("  [Retrieving persistent stats, please try again in a moment...]")
                    .color(NamedTextColor.GRAY));
            return;
        }

        sender.sendMessage(Component.empty());
        sender.sendMessage(line());
        sender.sendMessage(header("  ★  " + p.getName() + "'s Stats  ★"));
        sender.sendMessage(line());

        sendTimeStats(sender, p);
        sendDistanceStats(sender, p);
        sendCombatStats(sender, p);
        sendVillagerStats(sender, p);
        sendMiningOreStats(sender, p);
        sendMiningBlockStats(sender, p);
        sendFarmingStats(sender, p);

        sender.sendMessage(line());
        sender.sendMessage(Component.empty());
    }

    // ── Stat sections ──────────────────────────────────────────────────

    private void sendTimeStats(CommandSender sender, Player p) {
        // PLAY_ONE_MINUTE is stored in ticks despite the name; /20 = seconds.
        long playtimeSeconds = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
        // getAfkSeconds() returns seconds directly (already converted in AfkListener).
        long afkSeconds = afkListener.getAfkSeconds(p);

        Component afkValue = Component.text(formatTime(afkSeconds)).color(NamedTextColor.WHITE);
        if (afkListener.isAfk(p)) {
            afkValue = afkValue.append(
                    Component.text(" (currently AFK)").color(NamedTextColor.GRAY));
        }

        sender.sendMessage(section("⏱  Time"));
        sender.sendMessage(stat("Playtime",    formatTime(playtimeSeconds)));
        sender.sendMessage(statComponent("AFK Time", afkValue));
    }

    private void sendDistanceStats(CommandSender sender, Player p) {
        // WALK_ONE_CM and SPRINT_ONE_CM are separate Bukkit statistics.
        // WALK_ONE_CM = walking only; SPRINT_ONE_CM = sprinting only.
        double distWalked   = p.getStatistic(Statistic.WALK_ONE_CM)   / 100.0;
        double distSprinted = p.getStatistic(Statistic.SPRINT_ONE_CM) / 100.0;
        double distElytra   = p.getStatistic(Statistic.AVIATE_ONE_CM) / 100.0;  // elytra gliding
        double distCreative = p.getStatistic(Statistic.FLY_ONE_CM)    / 100.0;  // creative/spectator

        double distTotal = (
                  p.getStatistic(Statistic.WALK_ONE_CM)             // walking
                + p.getStatistic(Statistic.SPRINT_ONE_CM)           // sprinting
                + p.getStatistic(Statistic.CROUCH_ONE_CM)           // sneaking
                + p.getStatistic(Statistic.SWIM_ONE_CM)             // swimming
                + p.getStatistic(Statistic.WALK_UNDER_WATER_ONE_CM) // seafloor walking
                + p.getStatistic(Statistic.CLIMB_ONE_CM)            // ladders / vines
                + p.getStatistic(Statistic.FALL_ONE_CM)             // falling
                + p.getStatistic(Statistic.AVIATE_ONE_CM)           // elytra gliding
                + p.getStatistic(Statistic.FLY_ONE_CM)              // creative flight
                + p.getStatistic(Statistic.HORSE_ONE_CM)            // horse / donkey / mule
                + p.getStatistic(Statistic.BOAT_ONE_CM)             // boat
                + p.getStatistic(Statistic.MINECART_ONE_CM)         // minecart
                + p.getStatistic(Statistic.PIG_ONE_CM)              // riding pig
                + p.getStatistic(Statistic.STRIDER_ONE_CM)          // riding strider (nether)
                + p.getStatistic(Statistic.CAMEL_ONE_CM)            // riding camel (1.20+)
        ) / 100.0;

        sender.sendMessage(section("🗺  Distance"));
        sender.sendMessage(stat("Total Traveled", String.format("%.1f blocks", distTotal)));
        sender.sendMessage(stat("Walked",         String.format("%.1f blocks", distWalked)));
        sender.sendMessage(stat("Sprinted",       String.format("%.1f blocks", distSprinted)));
        sender.sendMessage(stat("Elytra",         String.format("%.1f blocks", distElytra)));
        sender.sendMessage(stat("Creative Flight",String.format("%.1f blocks", distCreative)));
    }

    private void sendCombatStats(CommandSender sender, Player p) {
        int pillagerKills   = p.getStatistic(Statistic.KILL_ENTITY, org.bukkit.entity.EntityType.PILLAGER);
        int raidsStarted    = p.getStatistic(Statistic.RAID_TRIGGER);
        int witherSkelKills = p.getStatistic(Statistic.KILL_ENTITY, org.bukkit.entity.EntityType.WITHER_SKELETON);
        int witherKills     = p.getStatistic(Statistic.KILL_ENTITY, org.bukkit.entity.EntityType.WITHER);
        int playerKills     = p.getStatistic(Statistic.PLAYER_KILLS);
        int deaths          = p.getStatistic(Statistic.DEATHS);

        sender.sendMessage(section("⚔  Combat"));
        sender.sendMessage(stat("Player Kills",            fmt(playerKills)));
        sender.sendMessage(stat("Deaths",                  fmt(deaths)));
        sender.sendMessage(stat("Pillagers Killed",        fmt(pillagerKills)));
        sender.sendMessage(stat("Wither Skeletons Killed", fmt(witherSkelKills)));
        sender.sendMessage(stat("Withers Killed",          fmt(witherKills)));
        sender.sendMessage(stat("Raids Started",           fmt(raidsStarted)));
    }

    private void sendVillagerStats(CommandSender sender, Player p) {
        int  villagerTrades = p.getStatistic(Statistic.TRADED_WITH_VILLAGER);
        // FIX: ANIMALS_BRED does not accept an EntityType sub-statistic — it throws
        // IllegalArgumentException at runtime. We track villager breeding ourselves
        // in AfkListener via EntityBreedEvent.
        long villagersBred  = afkListener.getVillagersBred(p);
        // Total animals bred (all species) from Bukkit's built-in counter.
        int  animalsBred    = p.getStatistic(Statistic.ANIMALS_BRED);

        sender.sendMessage(section("🏘  Villagers & Breeding"));
        sender.sendMessage(stat("Villager Trades Completed", fmt(villagerTrades)));
        sender.sendMessage(stat("Villagers Bred",            fmt(villagersBred)));
        sender.sendMessage(stat("Animals Bred (total)",      fmt(animalsBred)));
    }

    private void sendMiningOreStats(CommandSender sender, Player p) {
        // FIX: Added lapis, emerald, coal, copper — all were missing previously.
        int coalMined     = p.getStatistic(Statistic.MINE_BLOCK, Material.COAL_ORE)
                          + p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_COAL_ORE);
        int ironMined     = p.getStatistic(Statistic.MINE_BLOCK, Material.IRON_ORE)
                          + p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_IRON_ORE);
        int copperMined   = p.getStatistic(Statistic.MINE_BLOCK, Material.COPPER_ORE)
                          + p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_COPPER_ORE);
        int goldMined     = p.getStatistic(Statistic.MINE_BLOCK, Material.GOLD_ORE)
                          + p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_GOLD_ORE);
        int netherGold    = p.getStatistic(Statistic.MINE_BLOCK, Material.NETHER_GOLD_ORE);
        int netherQuartz  = p.getStatistic(Statistic.MINE_BLOCK, Material.NETHER_QUARTZ_ORE);
        int redstoneMined = p.getStatistic(Statistic.MINE_BLOCK, Material.REDSTONE_ORE)
                          + p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_REDSTONE_ORE);
        int lapisMined    = p.getStatistic(Statistic.MINE_BLOCK, Material.LAPIS_ORE)
                          + p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_LAPIS_ORE);
        int diamondMined  = p.getStatistic(Statistic.MINE_BLOCK, Material.DIAMOND_ORE)
                          + p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_DIAMOND_ORE);
        int emeraldMined  = p.getStatistic(Statistic.MINE_BLOCK, Material.EMERALD_ORE)
                          + p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE_EMERALD_ORE);
        int debrisMined   = p.getStatistic(Statistic.MINE_BLOCK, Material.ANCIENT_DEBRIS);

        sender.sendMessage(section("⛏  Mining — Ores"));
        sender.sendMessage(stat("Coal Ore",        fmt(coalMined)));
        sender.sendMessage(stat("Iron Ore",        fmt(ironMined)));
        sender.sendMessage(stat("Copper Ore",      fmt(copperMined)));
        sender.sendMessage(stat("Gold Ore",        fmt(goldMined)));
        sender.sendMessage(stat("Nether Gold Ore",  fmt(netherGold)));
        sender.sendMessage(stat("Nether Quartz Ore",fmt(netherQuartz)));
        sender.sendMessage(stat("Redstone Ore",    fmt(redstoneMined)));
        sender.sendMessage(stat("Lapis Ore",       fmt(lapisMined)));
        sender.sendMessage(stat("Diamond Ore",     fmt(diamondMined)));
        sender.sendMessage(stat("Emerald Ore",     fmt(emeraldMined)));
        sender.sendMessage(stat("Ancient Debris",  fmt(debrisMined)));
    }

    private void sendMiningBlockStats(CommandSender sender, Player p) {
        int stoneMined      = p.getStatistic(Statistic.MINE_BLOCK, Material.STONE)
                            + p.getStatistic(Statistic.MINE_BLOCK, Material.COBBLESTONE);
        int deepslateMined  = p.getStatistic(Statistic.MINE_BLOCK, Material.DEEPSLATE)
                            + p.getStatistic(Statistic.MINE_BLOCK, Material.COBBLED_DEEPSLATE);
        int netherrackMined = p.getStatistic(Statistic.MINE_BLOCK, Material.NETHERRACK);
        int spawnersBroken  = p.getStatistic(Statistic.MINE_BLOCK, Material.SPAWNER);

        sender.sendMessage(section("🪨  Mining — Blocks"));
        sender.sendMessage(stat("Stone Mined",      fmt(stoneMined)));
        sender.sendMessage(stat("Deepslate Mined",  fmt(deepslateMined)));
        sender.sendMessage(stat("Netherrack Mined", fmt(netherrackMined)));
        sender.sendMessage(stat("Spawners Broken",  fmt(spawnersBroken)));
    }

    private void sendFarmingStats(CommandSender sender, Player p) {
        // Bukkit's MINE_BLOCK statistic counts break-and-replant harvests.
        // AfkListener.getCropsHarvested() counts right-click harvests (no break).
        // Together they give the full harvest picture without double-counting.
        int brokenCrops = p.getStatistic(Statistic.MINE_BLOCK, Material.WHEAT)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.CARROTS)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.POTATOES)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.BEETROOTS)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.MELON)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.PUMPKIN)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.SUGAR_CANE)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.NETHER_WART)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.COCOA)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.SWEET_BERRY_BUSH)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.BAMBOO)
                        + p.getStatistic(Statistic.MINE_BLOCK, Material.KELP);

        long rightClickHarvests = afkListener.getCropsHarvested(p);
        long totalHarvests      = brokenCrops + rightClickHarvests;

        sender.sendMessage(section("🌾  Farming"));
        sender.sendMessage(stat("Crops Harvested (total)", fmt(totalHarvests)));
        sender.sendMessage(stat("  Break-harvested",       fmt(brokenCrops)));
        sender.sendMessage(stat("  Right-click harvested", fmt((int) rightClickHarvests)));
    }

    // ── Formatting helpers ─────────────────────────────────────────────

    private Component line() {
        return Component.text("  ─────────────────────────────────────")
                .color(NamedTextColor.DARK_AQUA);
    }

    private Component header(String text) {
        return Component.text(text)
                .color(NamedTextColor.AQUA)
                .decorate(TextDecoration.BOLD);
    }

    private Component section(String label) {
        return Component.text("  " + label)
                .color(NamedTextColor.YELLOW)
                .decorate(TextDecoration.BOLD);
    }

    private Component stat(String label, String value) {
        return Component.text("    " + label + ": ")
                .color(NamedTextColor.GRAY)
                .append(Component.text(value).color(NamedTextColor.WHITE));
    }

    private Component statComponent(String label, Component value) {
        return Component.text("    " + label + ": ")
                .color(NamedTextColor.GRAY)
                .append(value);
    }

    private String fmt(int value) {
        return String.format("%,d", value);
    }

    private String fmt(long value) {
        return String.format("%,d", value);
    }

    private String formatTime(long totalSeconds) {
        long days    = totalSeconds / 86400;
        long hours   = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (days > 0)    return days + "d " + hours + "h " + minutes + "m";
        if (hours > 0)   return hours + "h " + minutes + "m " + seconds + "s";
        if (minutes > 0) return minutes + "m " + seconds + "s";
        return seconds + "s";
    }
}
