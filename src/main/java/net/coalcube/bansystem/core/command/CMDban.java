package net.coalcube.bansystem.core.command;

import dev.dejvokep.boostedyaml.YamlDocument;
import net.coalcube.bansystem.core.BanSystem;
import net.coalcube.bansystem.core.ban.Ban;
import net.coalcube.bansystem.core.ban.BanManager;
import net.coalcube.bansystem.core.ban.Type;
import net.coalcube.bansystem.core.sql.Database;
import net.coalcube.bansystem.core.util.ConfigurationUtil;
import net.coalcube.bansystem.core.util.User;
import net.coalcube.bansystem.core.uuidfetcher.UUIDFetcher;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class CMDban implements Command {

    private final BanManager banmanager;
    private final YamlDocument config;
    private final YamlDocument messages;
    private final Database sql;
    private final ConfigurationUtil configurationUtil;

    private SimpleDateFormat simpleDateFormat;
    private Ban ban;
    private Type type;
    private String reason;
    private String creator;
    private String creatorName;
    private Date endDate;
    private int lvl;
    private long duration;
    private UUID uuid;
    private String name;
    private InetAddress address;
    private ArrayList<Integer> ids;


    public CMDban(BanManager banmanager, YamlDocument config, YamlDocument messages, Database sql, ConfigurationUtil configurationUtil) {
        this.banmanager = banmanager;
        this.config = config;
        this.messages = messages;
        this.sql = sql;
        this.configurationUtil = configurationUtil;
    }

    @Override
    public void execute(User user, String[] args) {
        simpleDateFormat = new SimpleDateFormat(configurationUtil.getMessage("DateTimePattern"));
        ids = new ArrayList<>();
        User target = null; // local variable to hold the target if online

        if (!user.hasPermission("bansys.ban") &&
                !user.hasPermission("bansys.ban.all") &&
                !user.hasPermission("bansys.ban.admin") &&
                !hasPermissionForAnyID(user)) {

            user.sendMessage(configurationUtil.getMessage("NoPermissionMessage"));

            return;
        }

        for (Object key : config.getSection("IDs").getKeys()) {
            ids.add(Integer.valueOf(key.toString()));
        }

        Collections.sort(ids);

        if (!sql.isConnected()) {
            try {
                sql.connect();
            } catch (SQLException ex) {
                user.sendMessage(configurationUtil.getMessage("NoDBConnection"));
                return;
            }
        }

        if (args.length <= 1) {
            user.sendMessage(configurationUtil.getMessage("Ban.ID.Listlayout.heading"));
            for (Integer key : ids) {
                if (config.getBoolean("IDs." + key + ".onlyAdmins")) {
                    user.sendMessage(
                            configurationUtil.getMessage("Ban.ID.Listlayout.IDs.onlyadmins")
                                    .replaceAll("%id%", key.toString())
                                    .replaceAll("%ID%", key.toString())
                                    .replaceAll("%reason%", config.getString("IDs." + key + ".reason")));
                } else
                    user.sendMessage(
                            configurationUtil.getMessage("Ban.ID.Listlayout.IDs.general")
                                    .replaceAll("%id%", key.toString())
                                    .replaceAll("%ID%", key.toString())
                                    .replaceAll("%reason%", config.getString("IDs." + key + ".reason")));
            }
            user.sendMessage(configurationUtil.getMessage("Ban.usage"));
            return;
        }

        if (args.length == 2) {
            if(BanSystem.getInstance().getUser(args[0]).getUniqueId() != null) {
                uuid = BanSystem.getInstance().getUser(args[0]).getUniqueId();
                name = BanSystem.getInstance().getUser(args[0]).getName();
            } else {
                try {
                    uuid = UUID.fromString(args[0]);
                    if(UUIDFetcher.getName(uuid) == null) {
                        if(banmanager.isSavedBedrockPlayer(uuid)) {
                            name = banmanager.getSavedBedrockUsername(uuid);
                            uuid = banmanager.getSavedBedrockUUID(name);
                        }
                    } else {
                        name = UUIDFetcher.getName(uuid);
                    }
                } catch (IllegalArgumentException exception) {
                    if(UUIDFetcher.getUUID(args[0].replaceAll("&", "§")) == null) {
                        try {
                            if(banmanager.isSavedBedrockPlayer(args[0].replaceAll("&", "§"))) {
                                uuid = banmanager.getSavedBedrockUUID(args[0].replaceAll("&", "§"));
                                name = banmanager.getSavedBedrockUsername(uuid);
                            } else
                                uuid = null;
                        } catch (SQLException | ExecutionException | InterruptedException e) {
                            BanSystem.getInstance().sendConsoleMessage("CMDban: " + e);
                        }
                    } else {
                        uuid = UUIDFetcher.getUUID(args[0].replaceAll("&", "§"));
                        name = UUIDFetcher.getName(uuid);
                    }
                } catch (SQLException | ExecutionException | InterruptedException throwables) {
                    BanSystem.getInstance().sendConsoleMessage("CMDban: " + throwables);
                }
            }

            if (uuid == null) {
                user.sendMessage(configurationUtil.getMessage("Playerdoesnotexist"));
                return;
            }

            if (!ids.contains(Integer.valueOf(args[1]))) {
                user.sendMessage(configurationUtil.getMessage("Ban.invalidinput"));
                return;
            }

            if(user.getUniqueId() != null && user.getUniqueId().equals(uuid)) {
                user.sendMessage(configurationUtil.getMessage("Ban.cannotban.yourself"));
                return;
            }

            if (config.getBoolean("IDs." + args[1] + ".onlyAdmins")) {
                if (!user.hasPermission("bansys.ban.admin")) {
                    user.sendMessage(configurationUtil.getMessage("Ban.onlyadmins"));
                    return;
                }
            }

            try {
                setParameters(user, args);
            } catch (UnknownHostException e) {
                user.sendMessage(configurationUtil.getMessage("Ban.failed"));
                BanSystem.getInstance().sendConsoleMessage("CMDban: " + e);
                return;
            }

            if (type != null) {
                if (user.hasPermission("bansys.ban." + args[1]) || user.hasPermission("bansys.ban.all")
                        || user.hasPermission("bansys.ban.admin")) {
                    String formattedEndDate;
                    if(endDate != null) {
                        formattedEndDate = simpleDateFormat.format(endDate);
                    } else
                        formattedEndDate = "§4§lPERMANENT";


                    try {
                        Ban chatBan = banmanager.getBan(uuid, Type.CHAT);
                        Ban netBan = banmanager.getBan(uuid, Type.NETWORK);
                        if ((type == Type.CHAT && chatBan != null) || (type == Type.NETWORK && netBan != null)) {
                            user.sendMessage(configurationUtil.getMessage("Ban.alreadybanned")
                                    .replaceAll("%player%", Objects.requireNonNull(name)));
                            return;
                        }
                    } catch (SQLException throwables) {
                        user.sendMessage(configurationUtil.getMessage("Ban.failed"));
                        BanSystem.getInstance().sendConsoleMessage("CMDban: " + throwables);
                    } catch (InterruptedException | ExecutionException e) {
                        BanSystem.getInstance().sendConsoleMessage("CMDban: " + e);
                    }

                    if (BanSystem.getInstance().getUser(name).getUniqueId() != null) {
                        target = BanSystem.getInstance().getUser(name.replaceAll("&", "§"));
                        address = target.getAddress();
                        if (target == user) {
                            user.sendMessage(configurationUtil.getMessage("Ban.cannotban.yourself"));
                            return;
                        }
                    }

                    // if target is online: only perform permission checks and bypass checks here; defer kick/message until after ban creation
                    if (target != null) {
                        if((target.hasPermission("bansys.ban") || target.hasPermission("bansys.ban.all") || hasPermissionForAnyID(target))
                                && !user.hasPermission("bansys.ban.admin")) {
                            user.sendMessage(configurationUtil.getMessage("Ban.cannotban.teammembers"));
                            return;
                        }

                        if(target.hasPermission("bansys.ban.admin") && user.getUniqueId() != null) {
                            user.sendMessage(configurationUtil.getMessage("Ban.cannotban.teammembers"));
                            return;
                        }

                        if (target.hasPermission("bansys.ban.bypass") && !user.hasPermission("bansys.ban.admin")) {
                            user.sendMessage(configurationUtil.getMessage("Ban.cannotban.bypassedplayers"));
                            return;
                        }
                    }

                    // Ban Player (create ban object before using ban.getId())
                    try {
                        if (address != null)
                            ban = banmanager.ban(uuid, duration, creator, type, reason, address);
                        else
                            ban = banmanager.ban(uuid, duration, creator, type, reason);

                        banmanager.log("Banned Player", creator, uuid.toString(), "banID: "  + ban.getId()
                                + "; reason: "+reason+"; lvl: "+lvl);
                    } catch (ExecutionException | InterruptedException | IOException | SQLException e) {
                        user.sendMessage(configurationUtil.getMessage("Ban.failed"));
                        throw new RuntimeException(e);
                    }

                    // now that ban is created, notify/kick the target if they're online
                    if (target != null) {
                        if (type == Type.NETWORK) {
                            String banScreen = configurationUtil.getMessage("Ban.Network.Screen");

                            banScreen = banScreen
                                    .replaceAll("%P%", configurationUtil.getMessage("prefix"))
                                    .replaceAll("%reason%", reason)
                                    .replaceAll("%reamingtime%", BanSystem.getInstance().getTimeFormatUtil()
                                            .getFormattedRemainingTime(duration))
                                    .replaceAll("%creator%", creatorName)
                                    .replaceAll("%enddate%", formattedEndDate)
                                    .replaceAll("%lvl%", String.valueOf(lvl))
                                    .replaceAll("%id%", ban.getId())
                                    .replaceAll("&", "§");

                            BanSystem.getInstance().disconnect(target, banScreen);
                        } else {
                            target.sendMessage(configurationUtil.getMessage("Ban.Chat.Screen")
                                    .replaceAll("%reason%", reason)
                                    .replaceAll("%player%", target.getDisplayName())
                                    .replaceAll("%reamingtime%", BanSystem.getInstance().getTimeFormatUtil()
                                            .getFormattedRemainingTime(duration))
                                    .replaceAll("%creator%", creatorName)
                                    .replaceAll("%enddate%", formattedEndDate)
                                    .replaceAll("%lvl%", String.valueOf(lvl))
                                    .replaceAll("%id%", ban.getId()));
                        }
                    }

                    String banSuccess = configurationUtil.getMessage("Ban.success")
                            .replaceAll("%Player%", Objects.requireNonNull(name))
                            .replaceAll("%reason%", reason)
                            .replaceAll("%reamingtime%", BanSystem.getInstance().getTimeFormatUtil()
                                    .getFormattedRemainingTime(duration))
                            .replaceAll("%banner%", creatorName)
                            .replaceAll("%type%", type.toString())
                            .replaceAll("%enddate%", formattedEndDate)
                            .replaceAll("%id%", ban.getId());

                    if(user.getUniqueId() != null)
                        user.sendMessage(banSuccess);
                    else
                        BanSystem.getInstance().sendConsoleMessage(banSuccess);

                    if(user.getUniqueId() != null) {
                        BanSystem.getInstance().sendConsoleMessage(configurationUtil.getMessage("Ban.notify")
                                .replaceAll("%player%", Objects.requireNonNull(name))
                                .replaceAll("%reason%", reason)
                                .replaceAll("%reamingtime%", BanSystem.getInstance().getTimeFormatUtil()
                                        .getFormattedRemainingTime(duration))
                                .replaceAll("%banner%", creatorName)
                                .replaceAll("%enddate%", formattedEndDate)
                                .replaceAll("%type%", type.toString())
                                .replaceAll("%id%", ban.getId()));
                    }
                    for (User all : BanSystem.getInstance().getAllPlayers()) {
                        if (all.hasPermission("bansys.notify") && (all.getUniqueId() != user.getUniqueId())) {
                            all.sendMessage(configurationUtil.getMessage("Ban.notify")
                                    .replaceAll("%player%", Objects.requireNonNull(name))
                                    .replaceAll("%reason%", reason)
                                    .replaceAll("%reamingtime%", BanSystem.getInstance().getTimeFormatUtil()
                                            .getFormattedRemainingTime(duration))
                                    .replaceAll("%banner%", creatorName)
                                    .replaceAll("%enddate%", formattedEndDate)
                                    .replaceAll("%type%", type.toString())
                                    .replaceAll("%id%", ban.getId()));
                        }
                    }
                } else
                    user.sendMessage(configurationUtil.getMessage("Ban.ID.NoPermission"));

            } else {
                user.sendMessage(configurationUtil.getMessage("Ban.failed"));
            }
            return;
        }

        if (args.length >= 3) {
            user.sendMessage(configurationUtil.getMessage("Ban.usage"));
        }
    }

    @Override
    public List<String> suggest(User user, String[] args) {
        if (!user.hasPermission("bansys.ban") &&
                !user.hasPermission("bansys.ban.all") &&
                !user.hasPermission("bansys.ban.admin") &&
                !hasPermissionForAnyID(user)) {
            return List.of();
        }
        List<String> suggests = new ArrayList<>();
        List<User> players = BanSystem.getInstance().getAllPlayers();

        if(args.length == 0 || args.length == 1) {
            for(User player : players) {
                suggests.add(player.getName());
            }

        } else if(args.length == 2) {
            for (Object key : config.getSection("IDs").getKeys()) {
                suggests.add(key.toString());
            }
        }

        return suggests;
    }

    private boolean hasPermissionForAnyID(User user) {
        for (Object keyObj : config.getSection("IDs").getKeys()) {
            String key = keyObj.toString();
            if (user.hasPermission("bansys.ban." + key))
                return true;
        }
        return false;
    }

    private void setParameters(User user, String[] args) throws UnknownHostException {

        if (user.getUniqueId() != null) {
            creator = user.getUniqueId().toString();
            creatorName = user.getDisplayName();
        } else {
            creatorName = user.getName();
            creator = user.getName();
        }

        if (config.getSection("IDs").getKeys().contains(args[1])) {
            String id = args[1];
            reason = config.getString("IDs." + id + ".reason");

            try {
                if (!banmanager.isMaxBanLvl(args[1], banmanager.getLevel(uuid, reason))) {
                    lvl = banmanager.getLevel(uuid, reason)+1;
                } else {
                    lvl = banmanager.getMaxLvl(args[1]);
                }
            } catch (SQLException | ExecutionException | InterruptedException throwables) {
                user.sendMessage(configurationUtil.getMessage("Ban.failed"));
                BanSystem.getInstance().sendConsoleMessage("CMDban: " + throwables);
            }

            for (Object lvlkey : config.getSection("IDs." + id + ".lvl").getKeys()) {
                if (Integer.parseInt(lvlkey.toString()) == lvl) {
                    duration = config.getLong("IDs." + id + ".lvl." + lvlkey + ".duration");
                    duration = (duration == -1) ? duration : duration * 1000;
                    type = Type.valueOf(config.getString("IDs." + id + ".lvl." + lvlkey + ".type"));
                }
            }
            if (duration == 0) {
                duration = -1;
            }


        }
        if(duration != -1) {
            endDate = new Date(System.currentTimeMillis() + duration);
        } else {
            endDate = null;
        }


    }
}
