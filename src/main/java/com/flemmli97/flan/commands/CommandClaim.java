package com.flemmli97.flan.commands;

import com.flemmli97.flan.api.ClaimPermission;
import com.flemmli97.flan.api.PermissionRegistry;
import com.flemmli97.flan.claim.Claim;
import com.flemmli97.flan.claim.ClaimStorage;
import com.flemmli97.flan.claim.PermHelper;
import com.flemmli97.flan.config.ConfigHandler;
import com.flemmli97.flan.gui.ClaimMenuScreenHandler;
import com.flemmli97.flan.player.EnumDisplayType;
import com.flemmli97.flan.player.EnumEditMode;
import com.flemmli97.flan.player.OfflinePlayerData;
import com.flemmli97.flan.player.PlayerClaimData;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.CommandSource;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class CommandClaim {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, boolean dedicated) {
        dispatcher.register(CommandManager.literal("flan")
                .then(CommandManager.literal("reload").requires(src -> src.hasPermissionLevel(ConfigHandler.config.permissionLevel)).executes(CommandClaim::reloadConfig))
                .then(CommandManager.literal("addClaim").then(CommandManager.argument("from", BlockPosArgumentType.blockPos()).then(CommandManager.argument("to", BlockPosArgumentType.blockPos()).executes(CommandClaim::addClaim))))
                .then(CommandManager.literal("menu").executes(CommandClaim::openMenu))
                .then(CommandManager.literal("claimInfo").executes(CommandClaim::claimInfo))
                .then(CommandManager.literal("transferClaim").then(CommandManager.argument("player", GameProfileArgumentType.gameProfile()).executes(CommandClaim::transferClaim)))
                .then(CommandManager.literal("delete").executes(CommandClaim::deleteClaim))
                .then(CommandManager.literal("deleteAll").executes(CommandClaim::deleteAllClaim))
                .then(CommandManager.literal("deleteSubClaim").executes(CommandClaim::deleteSubClaim))
                .then(CommandManager.literal("deleteAllSubClaims").executes(CommandClaim::deleteAllSubClaim))
                .then(CommandManager.literal("list").executes(CommandClaim::listClaims).then(CommandManager.argument("player", GameProfileArgumentType.gameProfile()).requires(src -> src.hasPermissionLevel(ConfigHandler.config.permissionLevel))
                        .executes(cmd -> listClaims(cmd, GameProfileArgumentType.getProfileArgument(cmd, "player")))))
                .then(CommandManager.literal("switchMode").executes(cmd -> switchClaimMode(cmd, -1))
                        .then(CommandManager.literal("default").executes(cmd -> switchClaimMode(cmd, 0)))
                        .then(CommandManager.literal("subclaim").executes(cmd -> switchClaimMode(cmd, 1))))
                .then(CommandManager.literal("adminMode").requires(src -> src.hasPermissionLevel(ConfigHandler.config.permissionLevel)).executes(cmd -> switchAdminMode(cmd, -1))
                        .then(CommandManager.literal("on").executes(cmd -> switchAdminMode(cmd, 1)))
                        .then(CommandManager.literal("off").executes(cmd -> switchAdminMode(cmd, 0))))
                .then(CommandManager.literal("readGriefPrevention").requires(src -> src.hasPermissionLevel(ConfigHandler.config.permissionLevel)).executes(CommandClaim::readGriefPreventionData))
                .then(CommandManager.literal("setAdminClaim").requires(src -> src.hasPermissionLevel(ConfigHandler.config.permissionLevel)).then(CommandManager.argument("toggle", BoolArgumentType.bool()).executes(CommandClaim::toggleAdminClaim)))
                .then(CommandManager.literal("listAdminClaims").requires(src -> src.hasPermissionLevel(ConfigHandler.config.permissionLevel)).executes(CommandClaim::listAdminClaims))
                .then(CommandManager.literal("adminDelete").requires(src -> src.hasPermissionLevel(ConfigHandler.config.permissionLevel)).executes(CommandClaim::adminDelete)
                        .then(CommandManager.literal("all").then(CommandManager.argument("players", GameProfileArgumentType.gameProfile())
                                .executes(CommandClaim::adminDeleteAll))))
                .then(CommandManager.literal("giveClaimBlocks").requires(src -> src.hasPermissionLevel(ConfigHandler.config.permissionLevel)).then(CommandManager.argument("players", GameProfileArgumentType.gameProfile())
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer()).executes(CommandClaim::giveClaimBlocks))))
                .then(CommandManager.literal("group")
                        .then(CommandManager.literal("add").then(CommandManager.argument("group", StringArgumentType.string()).executes(CommandClaim::addGroup)))
                        .then(CommandManager.literal("remove").then(CommandManager.argument("group", StringArgumentType.string())
                                .suggests(CommandClaim::groupSuggestion).executes(CommandClaim::removeGroup)))
                        .then(CommandManager.literal("players")
                                .then(CommandManager.literal("add").then(CommandManager.argument("group", StringArgumentType.word()).suggests(CommandClaim::groupSuggestion)
                                        .then(CommandManager.argument("players", GameProfileArgumentType.gameProfile()).executes(CommandClaim::addPlayer)
                                                .then(CommandManager.literal("overwrite").executes(CommandClaim::forceAddPlayer)))))
                                .then(CommandManager.literal("remove").then(CommandManager.argument("group", StringArgumentType.word()).suggests(CommandClaim::groupSuggestion)
                                        .then(CommandManager.argument("players", GameProfileArgumentType.gameProfile()).suggests((context, build) -> {
                                            ServerPlayerEntity player = context.getSource().getPlayer();
                                            List<String> list = Lists.newArrayList();
                                            ServerCommandSource src = context.getSource();
                                            ClaimStorage storage = ClaimStorage.get(src.getWorld());
                                            Claim claim = storage.getClaimAt(src.getPlayer().getBlockPos());
                                            if (claim != null && claim.canInteract(src.getPlayer(), PermissionRegistry.EDITPERMS, src.getPlayer().getBlockPos())) {
                                                list = claim.playersFromGroup(player.getServer(), "");
                                            }
                                            return CommandSource.suggestMatching(list, build);
                                        }).executes(CommandClaim::removePlayer))))))
                .then(CommandManager.literal("permission")
                        .then(CommandManager.literal("global").then(CommandManager.argument("permission", StringArgumentType.word()).suggests((ctx, b) -> permSuggestions(ctx, b, false))
                                .then(CommandManager.argument("toggle", StringArgumentType.word()).suggests((ctx, b) -> CommandSource.suggestMatching(new String[]{"default", "true", "false"}, b)).executes(CommandClaim::editGlobalPerm))))
                        .then(CommandManager.literal("group").then(CommandManager.argument("group", StringArgumentType.string()).suggests(CommandClaim::groupSuggestion)
                                .then(CommandManager.argument("permission", StringArgumentType.word()).suggests((ctx, b) -> permSuggestions(ctx, b, true))
                                        .then(CommandManager.argument("toggle", StringArgumentType.word())
                                                .suggests((ctx, b) -> CommandSource.suggestMatching(new String[]{"default", "true", "false"}, b)).executes(CommandClaim::editGroupPerm))))))
        );
    }

    private static int reloadConfig(CommandContext<ServerCommandSource> context) {
        ConfigHandler.reloadConfigs();
        context.getSource().sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.configReload), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int addClaim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        BlockPos from = BlockPosArgumentType.getLoadedBlockPos(context, "from");
        BlockPos to = BlockPosArgumentType.getLoadedBlockPos(context, "to");
        storage.createClaim(from, to, player);
        return Command.SINGLE_SUCCESS;
    }

    private static int transferClaim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        Collection<GameProfile> profs = GameProfileArgumentType.getProfileArgument(context, "player");
        if (profs.size() != 1) {
            context.getSource().sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.onlyOnePlayer, Formatting.RED), false);
            return 0;
        }
        GameProfile prof = profs.iterator().next();
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        Claim claim = storage.getClaimAt(player.getBlockPos());
        if (claim == null) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noClaim, Formatting.RED), false);
            return 0;
        }
        if (!storage.transferOwner(claim, player, prof.getId())) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.ownerTransferFail, Formatting.RED), false);
            return 0;
        }
        player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.ownerTransferSuccess, prof.getName()), Formatting.GOLD), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int openMenu(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        PlayerClaimData data = PlayerClaimData.get(player);
        if (data.getEditMode() == EnumEditMode.DEFAULT) {
            Claim claim = PermHelper.checkReturn(player, PermissionRegistry.EDITPERMS, PermHelper.genericNoPermMessage(player));
            if (claim == null)
                return 0;
            ClaimMenuScreenHandler.openClaimMenu(player, claim);
            data.addDisplayClaim(claim, EnumDisplayType.MAIN, player.getBlockPos().getY());
        } else {
            Claim claim = ClaimStorage.get(player.getServerWorld()).getClaimAt(player.getBlockPos());
            Claim sub = claim.getSubClaim(player.getBlockPos());
            if (sub != null && (claim.canInteract(player, PermissionRegistry.EDITPERMS, player.getBlockPos()) || sub.canInteract(player, PermissionRegistry.EDITPERMS, player.getBlockPos())))
                ClaimMenuScreenHandler.openClaimMenu(player, sub);
            else if (claim.canInteract(player, PermissionRegistry.EDITPERMS, player.getBlockPos()))
                ClaimMenuScreenHandler.openClaimMenu(player, claim);
            else
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noPermission, Formatting.DARK_RED), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int claimInfo(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        Claim claim = ClaimStorage.get(player.getServerWorld()).getClaimAt(player.getBlockPos());
        PlayerClaimData data = PlayerClaimData.get(player);
        if (claim == null) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noClaim, Formatting.RED), false);
            return 0;
        }
        if (data.getEditMode() == EnumEditMode.SUBCLAIM) {
            Claim sub = claim.getSubClaim(player.getBlockPos());
            if (sub != null) {
                List<Text> info = sub.infoString(player);
                player.sendMessage(PermHelper.simpleColoredText("==SubclaimInfo==", Formatting.AQUA), false);
                for (Text text : info)
                    player.sendMessage(text, false);
                return Command.SINGLE_SUCCESS;
            }
        }
        List<Text> info = claim.infoString(player);
        for (Text text : info)
            player.sendMessage(text, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteClaim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        Claim claim = storage.getClaimAt(player.getBlockPos());
        boolean check = PermHelper.check(player, player.getBlockPos(), claim, PermissionRegistry.EDITCLAIM, b -> {
            if (!b.isPresent())
                PermHelper.noClaimMessage(player);
            else if (!b.get())
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.deleteClaimError, Formatting.DARK_RED), false);
            else
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.deleteClaim, Formatting.RED), false);
        });
        if (!check)
            return 0;
        storage.deleteClaim(claim, true, PlayerClaimData.get(player).getEditMode(), player.getServerWorld());
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteAllClaim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        PlayerClaimData data = PlayerClaimData.get(player);
        if (data.confirmedDeleteAll()) {
            for (ServerWorld world : player.getServer().getWorlds()) {
                ClaimStorage storage = ClaimStorage.get(world);
                storage.allClaimsFromPlayer(player.getUuid()).forEach((claim) -> storage.deleteClaim(claim, true, PlayerClaimData.get(player).getEditMode(), player.getServerWorld()));
            }
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.deleteAllClaim, Formatting.GOLD), false);
            data.setConfirmDeleteAll(false);
        } else {
            data.setConfirmDeleteAll(true);
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.deleteAllClaimConfirm, Formatting.DARK_RED), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteSubClaim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        Claim claim = storage.getClaimAt(player.getBlockPos());
        if (claim == null) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noClaim, Formatting.RED), false);
            return 0;
        }
        Claim sub = claim.getSubClaim(player.getBlockPos());
        if (sub == null) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noClaim, Formatting.RED), false);
            return 0;
        }
        boolean check = PermHelper.check(player, player.getBlockPos(), claim, PermissionRegistry.EDITCLAIM, b -> {
            if (!b.isPresent())
                PermHelper.noClaimMessage(player);
            else if (!b.get())
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.deleteClaimError, Formatting.DARK_RED), false);
            else
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.deleteSubClaim, Formatting.DARK_RED), false);
        });
        if (!check)
            return 0;
        claim.deleteSubClaim(sub);
        return Command.SINGLE_SUCCESS;
    }

    private static int deleteAllSubClaim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        Claim claim = PermHelper.checkReturn(player, PermissionRegistry.EDITCLAIM, PermHelper.genericNoPermMessage(player));
        if (claim == null)
            return 0;
        List<Claim> subs = claim.getAllSubclaims();
        subs.forEach(claim::deleteSubClaim);
        player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.deleteSubClaimAll, Formatting.DARK_RED), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int listClaims(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return listClaimsFromUUID(context, null);
    }

    private static int listClaims(CommandContext<ServerCommandSource> context, Collection<GameProfile> profs) throws CommandSyntaxException {
        if (profs.size() != 1) {
            context.getSource().sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.onlyOnePlayer, Formatting.RED), false);
            return 0;
        }
        GameProfile prof = profs.iterator().next();
        if (prof == null || prof.getId() == null)
            return 0;
        return listClaimsFromUUID(context, prof.getId());
    }

    private static int listClaimsFromUUID(CommandContext<ServerCommandSource> context, UUID of) throws CommandSyntaxException {
        MinecraftServer server = context.getSource().getMinecraftServer();
        ServerPlayerEntity player = of == null ? context.getSource().getPlayer() : server.getPlayerManager().getPlayer(of);
        Map<World, Collection<Claim>> claims = Maps.newHashMap();
        for (ServerWorld world : server.getWorlds()) {
            ClaimStorage storage = ClaimStorage.get(world);
            claims.put(world, storage.allClaimsFromPlayer(player != null ? player.getUuid() : of));
        }
        if (ConfigHandler.config.maxClaimBlocks != -1) {
            if (player != null) {
                PlayerClaimData data = PlayerClaimData.get(player);
                context.getSource().sendFeedback(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.claimBlocksFormat,
                        data.getClaimBlocks(), data.getAdditionalClaims(), data.usedClaimBlocks()), Formatting.GOLD), false);
            } else {
                OfflinePlayerData data = new OfflinePlayerData(server, of);
                context.getSource().sendFeedback(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.claimBlocksFormat,
                        data.claimBlocks, data.additionalClaimBlocks, data.getUsedClaimBlocks(server)), Formatting.GOLD), false);
            }
        }
        context.getSource().sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.listClaims, Formatting.GOLD), false);
        for (Map.Entry<World, Collection<Claim>> entry : claims.entrySet())
            for (Claim claim : entry.getValue())
                context.getSource().sendFeedback(PermHelper.simpleColoredText(
                        entry.getKey().getRegistryKey().getValue().toString() + " # " + claim.formattedClaim(), Formatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int switchClaimMode(CommandContext<ServerCommandSource> context, int mode) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        PlayerClaimData data = PlayerClaimData.get(player);
        data.setEditMode(mode == 0 ? EnumEditMode.DEFAULT : mode == 1 ? EnumEditMode.SUBCLAIM :
            (data.getEditMode() == EnumEditMode.DEFAULT ? EnumEditMode.SUBCLAIM : EnumEditMode.DEFAULT));
        player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.editMode, data.getEditMode()), Formatting.GOLD), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int switchAdminMode(CommandContext<ServerCommandSource> context, int mode ) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        PlayerClaimData data = PlayerClaimData.get(player);
        data.setAdminIgnoreClaim(mode == 1 ? true : mode == 0 ? false : !data.isAdminIgnoreClaim());
        player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.adminMode, data.isAdminIgnoreClaim()), Formatting.GOLD), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int adminDelete(CommandContext<ServerCommandSource> context) {
        ServerCommandSource src = context.getSource();
        ClaimStorage storage = ClaimStorage.get(src.getWorld());
        Claim claim = storage.getClaimAt(new BlockPos(src.getPosition()));
        if (claim == null) {
            src.sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.noClaim, Formatting.RED), false);
            return 0;
        }
        storage.deleteClaim(claim, true, EnumEditMode.DEFAULT, src.getWorld());
        src.sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.deleteClaim, Formatting.RED), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int adminDeleteAll(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource src = context.getSource();
        if (src.getEntity() instanceof ServerPlayerEntity) {
            ServerPlayerEntity player = (ServerPlayerEntity) src.getEntity();
            PlayerClaimData data = PlayerClaimData.get(player);
            if (!data.confirmedDeleteAll()) {
                data.setConfirmDeleteAll(true);
                player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.deleteAllClaimConfirm, Formatting.DARK_RED), false);
                return Command.SINGLE_SUCCESS;
            }
        }
        List<String> players = Lists.newArrayList();
        for (GameProfile prof : GameProfileArgumentType.getProfileArgument(context, "players")) {
            for (ServerWorld world : src.getWorld().getServer().getWorlds()) {
                ClaimStorage storage = ClaimStorage.get(world);
                storage.allClaimsFromPlayer(prof.getId()).forEach((claim) -> storage.deleteClaim(claim, true, EnumEditMode.DEFAULT, world));
            }
            players.add(prof.getName());
        }
        src.sendFeedback(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.adminDeleteAll, players.toString()), Formatting.GOLD), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleAdminClaim(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        Claim claim = storage.getClaimAt(player.getBlockPos());
        if (claim == null) {
            context.getSource().sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.noClaim, Formatting.RED), false);
            return 0;
        }
        storage.toggleAdminClaim(player, claim, BoolArgumentType.getBool(context, "toggle"));
        context.getSource().sendFeedback(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.setAdminClaim, claim.isAdminClaim()), Formatting.GOLD), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int listAdminClaims(CommandContext<ServerCommandSource> context) {
        ServerCommandSource src = context.getSource();
        Collection<Claim> claims = ClaimStorage.get(src.getWorld()).getAdminClaims();
        src.sendFeedback(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.listAdminClaims, src.getWorld().getRegistryKey().getValue()), Formatting.GOLD), false);
        for (Claim claim : claims)
            src.sendFeedback(PermHelper.simpleColoredText(claim.formattedClaim(), Formatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int readGriefPreventionData(CommandContext<ServerCommandSource> context) {
        ServerCommandSource src = context.getSource();
        src.sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.readGriefpreventionData, Formatting.GOLD), true);
        ClaimStorage.readGriefPreventionData(src.getMinecraftServer(), src);
        PlayerClaimData.readGriefPreventionPlayerData(src.getMinecraftServer(), src);
        src.sendFeedback(PermHelper.simpleColoredText(ConfigHandler.lang.readGriefpreventionDataSuccess, Formatting.GOLD), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int giveClaimBlocks(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerCommandSource src = context.getSource();
        List<String> players = Lists.newArrayList();
        int amount = IntegerArgumentType.getInteger(context, "amount");
        for (GameProfile prof : GameProfileArgumentType.getProfileArgument(context, "players")) {
            ServerPlayerEntity player = src.getMinecraftServer().getPlayerManager().getPlayer(prof.getId());
            if (player != null) {
                PlayerClaimData data = PlayerClaimData.get(player);
                data.setAdditionalClaims(data.getAdditionalClaims() + amount);
            } else
                PlayerClaimData.editForOfflinePlayer(src.getMinecraftServer(), prof.getId(), amount);
            players.add(prof.getName());
        }
        src.sendFeedback(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.giveClaimBlocks, players.toString(), amount), Formatting.GOLD), true);
        return Command.SINGLE_SUCCESS;
    }

    private static final Pattern allowed = Pattern.compile("[a-zA-Z0-9_+.-]+");

    private static CompletableFuture<Suggestions> groupSuggestion(CommandContext<ServerCommandSource> context, SuggestionsBuilder build) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        List<String> list = Lists.newArrayList();
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        Claim claim = storage.getClaimAt(player.getBlockPos());
        if (claim != null && claim.canInteract(player, PermissionRegistry.EDITPERMS, player.getBlockPos())) {
            list = claim.groups();
        }
        for (int i = 0; i < list.size(); i++) {
            if (allowed.matcher(list.get(i)).matches())
                continue;
            list.set(i, '\"' + list.get(i) + '\"');
        }
        return CommandSource.suggestMatching(list, build);
    }

    private static int addGroup(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return modifyGroup(context, false);

    }

    private static int removeGroup(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return modifyGroup(context, true);
    }

    private static int modifyGroup(CommandContext<ServerCommandSource> context, boolean remove) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        String group = StringArgumentType.getString(context, "group");
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        Claim claim = storage.getClaimAt(player.getBlockPos());
        if (claim == null) {
            PermHelper.noClaimMessage(player);
            return 0;
        }
        if (remove) {
            if (claim.removePermGroup(player, group))
                player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.groupRemove, group), Formatting.GOLD), false);
            else {
                PermHelper.genericNoPermMessage(player);
                return 0;
            }
        } else {
            if (claim.groups().contains(group)) {
                player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.groupExist, group), Formatting.RED), false);
                return 0;
            } else if (claim.editPerms(player, group, PermissionRegistry.EDITPERMS, -1))
                player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.groupAdd, group), Formatting.GOLD), false);
            else {
                PermHelper.genericNoPermMessage(player);
                return 0;
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int forceAddPlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String group = StringArgumentType.getString(context, "group");
        return modifyPlayer(context, group, true);
    }

    private static int addPlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        String group = StringArgumentType.getString(context, "group");
        return modifyPlayer(context, group, false);
    }

    private static int removePlayer(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        return modifyPlayer(context, null, false);
    }

    private static int modifyPlayer(CommandContext<ServerCommandSource> context, String group, boolean force) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        ClaimStorage storage = ClaimStorage.get(player.getServerWorld());
        Claim claim = storage.getClaimAt(player.getBlockPos());
        if (claim == null) {
            PermHelper.noClaimMessage(player);
            return 0;
        }
        if (!claim.canInteract(player, PermissionRegistry.EDITPERMS, player.getBlockPos())) {
            PermHelper.genericNoPermMessage(player);
            return 0;
        }
        List<String> modified = Lists.newArrayList();
        for (GameProfile prof : GameProfileArgumentType.getProfileArgument(context, "players")) {
            if (claim.setPlayerGroup(prof.getId(), group, force))
                modified.add(prof.getName());
        }
        if (!modified.isEmpty())
            player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.playerModify, group, modified), Formatting.GOLD), false);
        else
            player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.playerModifyNo, group, modified), Formatting.RED), false);
        return Command.SINGLE_SUCCESS;
    }

    private static CompletableFuture<Suggestions> permSuggestions(CommandContext<ServerCommandSource> context, SuggestionsBuilder build, boolean group) {
        ServerWorld world = context.getSource().getWorld();
        Claim claim = ClaimStorage.get(world).getClaimAt(new BlockPos(context.getSource().getPosition()));
        boolean admin = claim != null && claim.isAdminClaim();
        for (ClaimPermission perm : PermissionRegistry.getPerms()) {
            if (!admin && ConfigHandler.config.globallyDefined(world, perm)) {
                continue;
            }
            if (!group || !PermissionRegistry.globalPerms().contains(perm))
                build.suggest(perm.id);
        }
        return build.buildFuture();
    }

    private static int editGlobalPerm(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        int mode = 0;
        switch (StringArgumentType.getString(context, "toggle")) {
            case "true":
                mode = 1;
                break;
            case "false":
                mode = 0;
                break;
            case "default":
                mode = -1;
                break;
        }
        return editPerms(context, null, mode);
    }

    private static int editGroupPerm(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        int mode = 0;
        switch (StringArgumentType.getString(context, "toggle")) {
            case "true":
                mode = 1;
                break;
            case "false":
                mode = 0;
                break;
            case "default":
                mode = -1;
                break;
        }
        return editPerms(context, StringArgumentType.getString(context, "group"), mode);
    }

    private static int editPerms(CommandContext<ServerCommandSource> context, String group, int mode) throws CommandSyntaxException {
        ServerPlayerEntity player = context.getSource().getPlayer();
        Claim claim = ClaimStorage.get(player.getServerWorld()).getClaimAt(player.getBlockPos());
        PlayerClaimData data = PlayerClaimData.get(player);
        if (data.getEditMode() == EnumEditMode.SUBCLAIM) {
            Claim sub = claim.getSubClaim(player.getBlockPos());
            if (sub != null)
                claim = sub;
        }
        if (claim == null) {
            PermHelper.noClaimMessage(player);
            return 0;
        }
        if (!claim.canInteract(player, PermissionRegistry.EDITPERMS, player.getBlockPos())) {
            player.sendMessage(PermHelper.simpleColoredText(ConfigHandler.lang.noPermission, Formatting.DARK_RED), false);
            return 0;
        }
        ClaimPermission perm;
        String p = StringArgumentType.getString(context, "permission");
        try {
            perm = PermissionRegistry.get(p);
            if (group != null && PermissionRegistry.globalPerms().contains(perm))
                throw new IllegalArgumentException();
        } catch (NullPointerException e) {
            player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.noSuchPerm, p), Formatting.DARK_RED), false);
            return 0;
        }
        String setPerm = mode == 1 ? "true" : mode == 0 ? "false" : "default";
        if (group == null) {
            claim.editGlobalPerms(player, perm, mode);
            player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.editPerm, perm, setPerm), Formatting.GOLD), false);
        } else {
            claim.editPerms(player, group, perm, mode);
            player.sendMessage(PermHelper.simpleColoredText(String.format(ConfigHandler.lang.editPermGroup, perm, group, setPerm), Formatting.GOLD), false);
        }
        return Command.SINGLE_SUCCESS;
    }
}
