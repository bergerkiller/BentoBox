package world.bentobox.bentobox.listeners;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.util.Vector;
import org.eclipse.jdt.annotation.NonNull;

import world.bentobox.bentobox.BentoBox;
import world.bentobox.bentobox.api.addons.GameModeAddon;
import world.bentobox.bentobox.blueprints.Blueprint;
import world.bentobox.bentobox.blueprints.BlueprintPaster;
import world.bentobox.bentobox.blueprints.dataobjects.BlueprintBundle;
import world.bentobox.bentobox.database.objects.Island;
import world.bentobox.bentobox.util.Util;
import world.bentobox.bentobox.util.teleport.SafeSpotTeleport;

/**
 * Handles teleportation via the Nether/End portals to the Nether and End dimensions of the worlds added by the GameModeAddons.
 *
 * @author tastybento
 * @deprecated replaced by better listeners.
 * @see world.bentobox.bentobox.listeners.teleports.PlayerTeleportListener
 * @see world.bentobox.bentobox.listeners.teleports.EntityTeleportListener
 */
@Deprecated
public class PortalTeleportationListener implements Listener {

    private final BentoBox plugin;
    private final Set<UUID> inPortal;
    private final Set<UUID> inTeleport;

    public PortalTeleportationListener(@NonNull BentoBox plugin) {
        this.plugin = plugin;
        inPortal = new HashSet<>();
        inTeleport = new HashSet<>();
    }

    /**
     * Fires the event if nether or end is disabled at the system level
     * @param e - EntityPortalEnterEvent
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(EntityPortalEnterEvent e) {
        if (!(e.getEntity() instanceof Player)) {
            return;
        }
        Entity entity = e.getEntity();
        Material type = e.getLocation().getBlock().getType();
        UUID uuid = entity.getUniqueId();
        if (inPortal.contains(uuid) || !plugin.getIWM().inWorld(Util.getWorld(e.getLocation().getWorld()))) {
            return;
        }
        inPortal.add(uuid);
        if (!Bukkit.getAllowNether() && type.equals(Material.NETHER_PORTAL)) {
            // Schedule a time
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Check again if still in portal
                if (inPortal.contains(uuid)) {
                    this.onIslandPortal(new PlayerPortalEvent((Player)entity, e.getLocation(), null, TeleportCause.NETHER_PORTAL, 0, false, 0));
                }
            }, 40);
            return;
        }
        // End portals are instant transfer
        if (!Bukkit.getAllowEnd() && (type.equals(Material.END_PORTAL) || type.equals(Material.END_GATEWAY))) {
            PlayerPortalEvent en = new PlayerPortalEvent((Player)entity,
                    e.getLocation(),
                    null,
                    type.equals(Material.END_PORTAL) ? TeleportCause.END_PORTAL : TeleportCause.END_GATEWAY,
                            0,
                            false,
                            0);
            this.onIslandPortal(en);
        }
    }

    /**
     * Handles non-player portal use.
     *
     * @param e - event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent e) {
        if (plugin.getIWM().inWorld(e.getFrom())) {
            Optional<Material> mat = Arrays.stream(BlockFace.values())
                    .map(bf -> e.getFrom().getBlock().getRelative(bf).getType())
                    .filter(m -> m.equals(Material.NETHER_PORTAL)
                            || m.equals(Material.END_PORTAL)
                            || m.equals(Material.END_GATEWAY))
                    .findFirst();
            if (mat.isEmpty()) {
                e.setCancelled(true);
            } else if (mat.get().equals(Material.NETHER_PORTAL)){
                processPortal(new PlayerEntityPortalEvent(e), Environment.NETHER);
            } else {
                processPortal(new PlayerEntityPortalEvent(e), Environment.THE_END);
            }
        }
    }

    /**
     * Remove inPortal flag only when player exits the portal
     * @param e player move event
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onExitPortal(PlayerMoveEvent e) {
        if (!inPortal.contains(e.getPlayer().getUniqueId())) {
            return;
        }
        if (e.getTo() != null && !e.getTo().getBlock().getType().equals(Material.NETHER_PORTAL)) {
            inPortal.remove(e.getPlayer().getUniqueId());
            inTeleport.remove(e.getPlayer().getUniqueId());
        }
    }

    /**
     * Handles nether or end portals
     * @param e - event
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIslandPortal(PlayerPortalEvent e) {
        switch (e.getCause()) {
        case END_GATEWAY, END_PORTAL -> processPortal(new PlayerEntityPortalEvent(e), Environment.THE_END);
        case NETHER_PORTAL -> processPortal(new PlayerEntityPortalEvent(e), Environment.NETHER);
        default -> {
            // Do nothing
        }

        };

    }

    /**
     * Process the portal action
     * @param e - event
     * @param env - environment that this relates to - NETHER or THE_END
     * @return true if portal happens, false if not
     */
    private boolean processPortal(final PlayerEntityPortalEvent e, final Environment env) {
        World fromWorld = e.getFrom().getWorld();
        World overWorld = Util.getWorld(fromWorld);
        if (overWorld == null || fromWorld == null || !plugin.getIWM().inWorld(overWorld)) {
            // Do nothing special
            return false;
        }

        if (!isGenerate(overWorld, env)) {
            e.setCancelled(true);
            return false;
        }

        if (!Bukkit.getAllowNether()) {
            e.setCancelled(true);
        }

        if (inTeleport.contains(e.getEntity().getUniqueId())) {
            return false;
        }
        inTeleport.add(e.getEntity().getUniqueId());

        // STANDARD NETHER OR END
        if (!isIslands(overWorld, env)) {
            handleStandardNetherOrEnd(e, fromWorld, overWorld, env);
            return true;
        }
        // FROM NETHER OR END
        // If entering a portal in the other world, teleport to a portal in overworld if there is one
        if (fromWorld.getEnvironment().equals(env)) {
            handleFromNetherOrEnd(e, overWorld, env);
            return true;
        }
        // TO NETHER OR END
        World toWorld = getNetherEndWorld(overWorld, env);
        // Set whether portals should be created or not
        e.setCanCreatePortal(plugin.getIWM().getAddon(overWorld).map(gm -> isMakePortals(gm, env)).orElse(false));
        // Set the destination location
        // If portals cannot be created, then destination is the spawn point, otherwise it's the vector
        e.setTo(getTo(e, env, toWorld));

        // Find the distance from edge of island's protection and set the search radius
        e.getIsland().ifPresent(i -> setSeachRadius(e, i));

        // Check if there is an island there or not
        if (e.getEntity().getType().equals(EntityType.PLAYER)
                && plugin.getIWM().isPasteMissingIslands(overWorld)
                && !plugin.getIWM().isUseOwnGenerator(overWorld)
                && isGenerate(overWorld, env)
                && isIslands(overWorld, env)
                && getNetherEndWorld(overWorld, env) != null
                && e.getIsland().filter(i -> !hasPartnerIsland(i, env)).map(i -> {
                    // No nether island present so paste the default one
                    e.setCancelled(true);
                    pasteNewIsland((Player)e.getEntity(), e.getTo(), i, env);
                    return true;
                }).orElse(false)) {
            // All done here
            return true;
        }
        if (e.getCanCreatePortal()) {
            // Let the server teleport
            return true;
        }
        if (env.equals(Environment.THE_END)) {
            // Prevent death from hitting the ground
            e.getEntity().setVelocity(new Vector(0,0,0));
            e.getEntity().setFallDistance(0);
        }

        // If we do not generate portals, teleportation should happen manually with safe spot builder.
        // Otherwise, we could end up with situations when player is placed in mid air, if teleportation
        // is done instantly.
        // Our safe spot task is triggered in next tick, however, end teleportation happens in the same tick.
        // It is placed outside THE_END check, as technically it could happen with the nether portal too.
        e.setCancelled(true);

        // If there is a portal to go to already, then the player will go there
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!e.getEntity().getWorld().equals(toWorld)) {
                // Else manually teleport entity
                new SafeSpotTeleport.Builder(plugin)
                .entity(e.getEntity())
                .location(e.getTo())
                .portal()
                .thenRun(() -> {
                    e.getEntity().setVelocity(new Vector(0,0,0));
                    e.getEntity().setFallDistance(0);
                })
                .build();
            }
        });
        return true;
    }


    /**
     * Set the destination of this portal action
     * @param e - event
     * @param env - environment
     * @param toWorld - to world
     */
    Location getTo(PlayerEntityPortalEvent e, Environment env, World toWorld)
    {
        // Null check - not that useful
        if (e.getFrom().getWorld() == null || toWorld == null)
        {
            return null;
        }

        Location toLocation = e.getIsland().map(island -> island.getSpawnPoint(env)).
            orElse(e.getFrom().toVector().toLocation(toWorld));

        // Limit Y to the min/max world height.
        toLocation.setY(Math.max(Math.min(toLocation.getY(), toWorld.getMaxHeight()), toWorld.getMinHeight()));

        if (!e.getCanCreatePortal())
        {
            // Legacy portaling
            return toLocation;
        }
        // Make portals
        // For anywhere other than the end - it is the player's location that is used
        if (!env.equals(Environment.THE_END))
        {
            return toLocation;
        }
        // If the-end then we want the platform to always be generated in the same place no matter where
        // they enter the portal
        final int x = e.getFrom().getBlockX();
        final int z = e.getFrom().getBlockZ();
        final int y = e.getFrom().getBlockY();
        int i = x;
        int j = z;
        int k = y;
        // If the from is not a portal, then we have to find it
        if (!e.getFrom().getBlock().getType().equals(Material.END_PORTAL))
        {
            // Find the portal - due to speed, it is possible that the player will be below or above the portal
            for (k = toWorld.getMinHeight(); (k < e.getWorld().getMaxHeight()) &&
                !e.getWorld().getBlockAt(x, k, z).getType().equals(Material.END_PORTAL); k++);
        }
        // Find the maximum x and z corner
        for (; (i < x + 5) && e.getWorld().getBlockAt(i, k, z).getType().equals(Material.END_PORTAL); i++) ;
        for (; (j < z + 5) && e.getWorld().getBlockAt(x, k, j).getType().equals(Material.END_PORTAL); j++) ;

        // Mojang end platform generation is:
        // AIR
        // AIR
        // OBSIDIAN
        // and player is placed on second air block above obsidian.
        // If Y coordinate is below 2, then obsidian platform is not generated and player falls in void.
        return new Location(toWorld, i, Math.max(toWorld.getMinHeight() + 2, k), j);
    }


    /**
     * Check if vanilla portals should be used
     * @param gm - game mode
     * @param env - environment
     * @return true or false
     */
    private boolean isMakePortals(GameModeAddon gm, Environment env) {
        return env.equals(Environment.NETHER) ?
            gm.getWorldSettings().isMakeNetherPortals() && Bukkit.getAllowNether() :
            gm.getWorldSettings().isMakeEndPortals() && Bukkit.getAllowEnd();
    }

    /**
     * Check if nether or end are generated
     * @param overWorld - game world
     * @param env - environment
     * @return true or false
     */
    private boolean isGenerate(World overWorld, Environment env) {
        return env.equals(Environment.NETHER) ? plugin.getIWM().isNetherGenerate(overWorld) : plugin.getIWM().isEndGenerate(overWorld);
    }

    /**
     * Check if nether or end islands are generated
     * @param overWorld - over world
     * @param env - environment
     * @return true or false
     */
    private boolean isIslands(World overWorld, Environment env) {
        return env.equals(Environment.NETHER) ? plugin.getIWM().isNetherIslands(overWorld) : plugin.getIWM().isEndIslands(overWorld);
    }

    /**
     * Get the nether or end world
     * @param overWorld - over world
     * @param env - environment
     * @return nether or end world
     */
    private World getNetherEndWorld(World overWorld, Environment env) {
        return env.equals(Environment.NETHER) ? plugin.getIWM().getNetherWorld(overWorld) : plugin.getIWM().getEndWorld(overWorld);
    }

    /**
     * Check if the island has a nether or end island already
     * @param i - island
     * @param env - environment
     * @return true or false
     */
    private boolean hasPartnerIsland(Island i, Environment env) {
        return env.equals(Environment.NETHER) ? i.hasNetherIsland() : i.hasEndIsland();
    }

    /**
     * Check if the default nether or end are allowed by the server settings
     * @param env - environment
     * @return true or false
     */
    private boolean isAllowedOnServer(Environment env) {
        return env.equals(Environment.NETHER) ? Bukkit.getAllowNether() : Bukkit.getAllowEnd();
    }

    /**
     * Handle teleport from nether or end to overworld
     * @param e - event
     * @param overWorld - over world
     * @param env - environment
     */
    private void handleFromNetherOrEnd(PlayerEntityPortalEvent e, World overWorld, Environment env) {
        // Standard portals
        if (plugin.getIWM().getAddon(overWorld).map(gm -> isMakePortals(gm, env)).orElse(false)) {
            e.setTo(e.getFrom().toVector().toLocation(overWorld));
            // Find distance from edge of island's protection
            plugin.getIslands().getIslandAt(e.getFrom()).ifPresent(i -> setSeachRadius(e, i));
            return;
        }
        // Custom portals
        e.setCancelled(true);
        // If this is from the island nether or end, then go to the same vector, otherwise try island home location
        Location to = plugin.getIslands().getIslandAt(e.getFrom()).map(i -> i.getSpawnPoint(Environment.NORMAL)).orElse(e.getFrom().toVector().toLocation(overWorld));
        e.setTo(to);
        // Else other worlds teleport to the nether
        new SafeSpotTeleport.Builder(plugin)
        .entity(e.getEntity())
        .location(to)
        .portal()
        .build();

    }


    /**
     * Handle teleport from or to standard nether or end
     * @param e - PlayerEntityPortalEvent
     * @param fromWorld - from world
     * @param overWorld - over world
     * @param env - environment involved
     */
    private void handleStandardNetherOrEnd(PlayerEntityPortalEvent e, World fromWorld, World overWorld, Environment env) {
        if (fromWorld.getEnvironment() != env) {
            World toWorld = Objects.requireNonNull(getNetherEndWorld(overWorld, env));
            Location spawnPoint = toWorld.getSpawnLocation();
            // If going to the nether and nether portals are active then just teleport to approx location
            if (env.equals(Environment.NETHER) && plugin.getIWM().getWorldSettings(overWorld).isMakeNetherPortals()) {
                spawnPoint = e.getFrom().toVector().toLocation(toWorld);
            }
            // If spawn is set as 0,63,0 in the End then move it to 100, 50 ,0.
            if (env.equals(Environment.THE_END) && spawnPoint.getBlockX() == 0 && spawnPoint.getBlockZ() == 0) {
                // Set to the default end spawn
                spawnPoint = new Location(toWorld, 100, 50, 0);
                toWorld.setSpawnLocation(100, 50, 0);
            }
            if (isAllowedOnServer(env)) {
                // To Standard Nether or end
                e.setTo(spawnPoint);
            } else {
                // Teleport to standard nether or end
                new SafeSpotTeleport.Builder(plugin)
                .entity(e.getEntity())
                .location(spawnPoint)
                .portal()
                .build();
            }
        }
        // From standard nether or end
        else if (e.getEntity() instanceof Player){
            e.setCancelled(true);
            plugin.getIslands().homeTeleportAsync(overWorld, (Player)e.getEntity());
        }

    }


    void setSeachRadius(PlayerEntityPortalEvent e, Island i) {
        if (!i.onIsland(e.getFrom())) return;
        // Find max x or max z
        int x = Math.abs(i.getProtectionCenter().getBlockX() - e.getFrom().getBlockX());
        int z = Math.abs(i.getProtectionCenter().getBlockZ() - e.getFrom().getBlockZ());
        int diff = Math.max(plugin.getSettings().getMinPortalSearchRadius(), i.getProtectionRange() - Math.max(x, z));
        if (diff > 0 && diff < 128) {
            e.setSearchRadius(diff);
        }
    }


    /**
     * Pastes the default nether or end island and teleports the player to the island's spawn point
     * @param player - player to teleport after pasting
     * @param to - the fallback location if a spawn point is not part of the blueprint
     * @param island - the island
     * @param env - NETHER or THE_END
     */
    private void pasteNewIsland(Player player, Location to, Island island, Environment env) {
        // Paste then teleport player
        plugin.getIWM().getAddon(island.getWorld()).ifPresent(addon -> {
            // Get the default bundle's nether or end blueprint
            BlueprintBundle bb = plugin.getBlueprintsManager().getDefaultBlueprintBundle(addon);
            if (bb != null) {
                Blueprint bp = plugin.getBlueprintsManager().getBlueprints(addon).get(bb.getBlueprint(env));
                if (bp != null) {
                    new BlueprintPaster(plugin, bp,
                            to.getWorld(),
                            island).paste().thenAccept(b -> new SafeSpotTeleport.Builder(plugin)
                                    .entity(player)
                                    .location(island.getSpawnPoint(env) == null ? to : island.getSpawnPoint(env))
                                    // No need to use portal because there will be no portal on the other end
                                    .build());
                } else {
                    plugin.logError("Could not paste default island in nether or end. Is there a nether-island or end-island blueprint?");
                }
            }
        });
    }
}
