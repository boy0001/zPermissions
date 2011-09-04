package org.tyrannyofheaven.bukkit.zPermissions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import javax.persistence.PersistenceException;

import org.bukkit.entity.Player;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event.Type;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.java.JavaPlugin;
import org.tyrannyofheaven.bukkit.util.ToHUtils;
import org.tyrannyofheaven.bukkit.util.command.ToHCommandExecutor;
import org.tyrannyofheaven.bukkit.zPermissions.dao.AvajePermissionDao;
import org.tyrannyofheaven.bukkit.zPermissions.dao.PermissionDao;
import org.tyrannyofheaven.bukkit.zPermissions.model.Entry;
import org.tyrannyofheaven.bukkit.zPermissions.model.Membership;
import org.tyrannyofheaven.bukkit.zPermissions.model.PermissionEntity;
import org.tyrannyofheaven.bukkit.zPermissions.model.PermissionWorld;

public class ZPermissionsPlugin extends JavaPlugin {

    private static final String DEFAULT_GROUP = "default"; // TODO configurable

    // FIXME should be synchronized
    private final Map<String, PermissionAttachment> attachments = new HashMap<String, PermissionAttachment>();

    private final Map<String, String> lastWorld = new HashMap<String, String>();

    private PermissionDao dao;

    PermissionDao getDao() {
        return dao;
    }

    @Override
    public void onDisable() {
        log("%s disabled.", getDescription().getVersion());
    }

    @Override
    public void onEnable() {
        log("%s enabled.", getDescription().getVersion());
        
        try {
            getDatabase().createQuery(Entry.class).findRowCount();
        }
        catch (PersistenceException e) {
            log("Creating SQL tables...");
            installDDL();
            log("Done.");
        }
        
        dao = new AvajePermissionDao(getDatabase());
        
        getCommand("perm").setExecutor(new ToHCommandExecutor<ZPermissionsPlugin>(this, new RootCommand()));
        
        ZPermissionsPlayerListener pl = new ZPermissionsPlayerListener(this);
        getServer().getPluginManager().registerEvent(Type.PLAYER_JOIN, pl, Priority.Monitor, this);
        getServer().getPluginManager().registerEvent(Type.PLAYER_KICK, pl, Priority.Monitor, this);
        getServer().getPluginManager().registerEvent(Type.PLAYER_QUIT, pl, Priority.Monitor, this);
        getServer().getPluginManager().registerEvent(Type.PLAYER_TELEPORT, pl, Priority.Monitor, this);
        
        // TODO resolve players already online
    }

    public void log(String format, Object... args) {
        ToHUtils.log(this, Level.INFO, format, args);
    }

    @Override
    public List<Class<?>> getDatabaseClasses() {
        List<Class<?>> result = new ArrayList<Class<?>>();
        result.add(PermissionEntity.class);
        result.add(PermissionWorld.class);
        result.add(Entry.class);
        result.add(Membership.class);
        return result;
    }

    private void resolveGroup(Map<String, Boolean> permissions, String world, PermissionEntity group) {
        // Build list of group ancestors
        List<PermissionEntity> ancestry = new ArrayList<PermissionEntity>();
        ancestry.add(group);
        while (group.getParent() != null) {
            group = group.getParent();
            ancestry.add(group);
        }
        
        // Reverse list (will be applying farthest ancestors first)
        Collections.reverse(ancestry);
        
        for (PermissionEntity ancestor : ancestry) {
            applyPermissions(permissions, ancestor, world);
        }
    }

    private Map<String, Boolean> resolvePlayer(Player player) {
        String world = player.getWorld().getName().toLowerCase();

        getDatabase().beginTransaction();
        try {
            Set<PermissionEntity> groups = getDao().getGroups(player.getName());
            if (groups.isEmpty()) {
                PermissionEntity defaultGroup = getDao().getEntity(getDefaultGroup(), true);
                if (defaultGroup != null)
                    groups.add(defaultGroup);
            }

            Map<String, Boolean> permissions = new HashMap<String, Boolean>();

            // For now, group ordering is arbitrary TODO priorities?
            for (PermissionEntity group : groups) {
                resolveGroup(permissions, world, group);
            }

            // Player-specific permissions overrides all group permissions
            PermissionEntity playerEntity = getDao().getEntity(player.getName(), false);
            if (playerEntity != null) {
                applyPermissions(permissions, playerEntity, world);
            }

            return permissions;
        }
        finally {
            getDatabase().endTransaction();
        }
    }

    private void applyPermissions(Map<String, Boolean> permissions, PermissionEntity entity, String world) {
        Map<String, Boolean> worldPermissions = new HashMap<String, Boolean>();

        // Apply non-world-specific permissions first
        for (Entry e : entity.getPermissions()) {
            if (e.getWorld() == null) {
                permissions.put(e.getPermission(), e.isValue());
            }
            else if (e.getWorld().getName().equals(world)) {
                worldPermissions.put(e.getPermission(), e.isValue());
            }
        }

        // Then override with world-specific permissions
        permissions.putAll(worldPermissions);
    }

    void removeAttachment(Player player) {
        PermissionAttachment pa = attachments.get(player.getName());
        if (pa != null)
            pa.remove();
    }

    void addAttachment(Player player, boolean force) {
        // Check if the player changed worlds
        if (!force) {
            String playerLastWorld = lastWorld.get(player.getName());
            
            force = !player.getWorld().getName().equals(playerLastWorld);
        }

        if (!force) return;

        lastWorld.put(player.getName(), player.getWorld().getName());

        PermissionAttachment pa = player.addAttachment(this);
        for (Map.Entry<String, Boolean> me : resolvePlayer(player).entrySet()) {
            pa.setPermission(me.getKey(), me.getValue());
        }
        PermissionAttachment old = attachments.put(player.getName(), pa);
        if (old != null)
            old.remove();
    }

    void refreshPlayer(String playerName) {
        Player player = getServer().getPlayer(playerName);
        if (player != null)
            addAttachment(player, true);
    }

    String getDefaultGroup() {
        return DEFAULT_GROUP;
    }

}