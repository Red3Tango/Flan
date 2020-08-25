package com.flemmli97.flan.player;

import com.flemmli97.flan.IClaimData;
import com.flemmli97.flan.claim.Claim;
import com.flemmli97.flan.claim.ClaimStorage;
import com.flemmli97.flan.claim.ParticleIndicators;
import com.flemmli97.flan.config.ConfigHandler;
import com.google.common.collect.Sets;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public class PlayerClaimData {

    private int claimBlocks, additionalClaimBlocks, confirmTick;

    private int lastBlockTick;
    private EnumEditMode mode = EnumEditMode.DEFAULT;
    private Claim editingClaim;
    private ClaimDisplay displayEditing;

    private BlockPos firstCorner;

    private final Set<ClaimDisplay> claimDisplayList = Sets.newHashSet();
    private final Set<ClaimDisplay> displayToAdd = Sets.newHashSet();

    private final ServerPlayerEntity player;

    private boolean confirmDeleteAll, adminIgnoreClaim;
    private boolean dirty;

    public PlayerClaimData(ServerPlayerEntity player) {
        this.player = player;
        this.claimBlocks = ConfigHandler.config.startingBlocks;
    }

    public static PlayerClaimData get(PlayerEntity player) {
        return (PlayerClaimData) ((IClaimData) player).getClaimData();
    }

    public int getClaimBlocks() {
        return this.claimBlocks;
    }

    public void setClaimBlocks(int amount) {
        this.claimBlocks = amount;
        this.dirty = true;
    }

    public boolean addClaimBlocks(int amount) {
        if (this.claimBlocks + amount > ConfigHandler.config.maxClaimBlocks)
            return false;
        this.claimBlocks += amount;
        this.dirty = true;
        return true;
    }

    public int getAdditionalClaims() {
        return this.additionalClaimBlocks;
    }

    public void setAdditionalClaims(int amount) {
        this.additionalClaimBlocks = amount;
        this.dirty = true;
    }

    public boolean canUseClaimBlocks(int amount) {
        int usedClaimsBlocks = this.usedClaimBlocks();
        return usedClaimsBlocks + amount <= this.claimBlocks + this.additionalClaimBlocks;
    }

    public int usedClaimBlocks() {
        return this.calculateUsedClaimBlocks();
    }

    public Claim currentEdit() {
        return this.editingClaim;
    }

    public void setEditClaim(Claim claim, int height) {
        if (claim != null)
            this.displayEditing = new ClaimDisplay(claim, EnumDisplayType.EDIT, height);
        else
            this.displayEditing = null;
        this.editingClaim = claim;
    }

    public void addDisplayClaim(Claim claim, EnumDisplayType type, int height) {
        this.displayToAdd.add(new ClaimDisplay(claim, type, height));
        if (type == EnumDisplayType.MAIN)
            for (Claim sub : claim.getAllSubclaims())
                this.displayToAdd.add(new ClaimDisplay(sub, EnumDisplayType.SUB, height));
    }

    public EnumEditMode getEditMode() {
        return this.mode;
    }

    public void setEditMode(EnumEditMode mode) {
        this.mode = mode;
        this.editingClaim = null;
        this.firstCorner = null;
    }

    public BlockPos editingCorner() {
        return this.firstCorner;
    }

    public void setEditingCorner(BlockPos pos) {
        if (pos != null) {
            BlockState state = this.player.world.getBlockState(pos);
            while (state.isAir() || state.getMaterial().isReplaceable()) {
                pos = pos.down();
                state = this.player.world.getBlockState(pos);
            }
        }
        this.firstCorner = pos;
    }

    public boolean confirmedDeleteAll() {
        return this.confirmDeleteAll;
    }

    public void setConfirmDeleteAll(boolean flag) {
        this.confirmDeleteAll = flag;
        this.confirmTick = 400;
    }

    public void setAdminIgnoreClaim(boolean flag) {
        this.adminIgnoreClaim = flag;
    }

    public boolean isAdminIgnoreClaim() {
        return this.adminIgnoreClaim;
    }

    public void tick() {
        this.displayToAdd.forEach(add -> {
            if (!this.claimDisplayList.add(add)) {
                this.claimDisplayList.removeIf(c -> c.equals(add) && c.type != add.type);
                this.claimDisplayList.add(add);
            }
        });
        this.displayToAdd.clear();
        this.claimDisplayList.removeIf(d -> d.display(this.player));
        if (++this.lastBlockTick > ConfigHandler.config.ticksForNextBlock) {
            this.addClaimBlocks(1);
            this.lastBlockTick = 0;
        }
        if (this.firstCorner != null)
            this.player.networkHandler.sendPacket(new ParticleS2CPacket(ParticleIndicators.SETCORNER, true, this.firstCorner.getX() + 0.5, this.firstCorner.getY() + 1.25, this.firstCorner.getZ() + 0.5, 0, 0.25f, 0, 0, 3));
        if (--this.confirmTick < 0)
            this.confirmDeleteAll = false;
        if (this.displayEditing != null)
            this.displayEditing.display(this.player);
        if (this.player.getMainHandStack().getItem() != ConfigHandler.config.claimingItem && this.player.getOffHandStack().getItem() != ConfigHandler.config.claimingItem) {
            this.firstCorner = null;
            this.editingClaim = null;
        }
    }

    public void save(MinecraftServer server) {
        File dir = new File(server.getSavePath(WorldSavePath.PLAYERDATA).toFile(), "/claimData/");
        if (!dir.exists())
            dir.mkdirs();
        try {
            if (!this.dirty)
                return;
            File file = new File(dir, this.player.getUuid() + ".json");
            if (!file.exists())
                file.createNewFile();
            FileWriter writer = new FileWriter(file);
            JsonObject obj = new JsonObject();
            obj.addProperty("ClaimBlocks", this.claimBlocks);
            obj.addProperty("AdditionalBlocks", this.additionalClaimBlocks);
            ConfigHandler.GSON.toJson(obj, writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void read(MinecraftServer server) {
        File dir = new File(server.getSavePath(WorldSavePath.PLAYERDATA).toFile(), "/claimData/");
        if (!dir.exists())
            return;
        try {
            File file = new File(dir, this.player.getUuid() + ".json");
            if (!file.exists())
                return;
            FileReader reader = new FileReader(file);
            JsonObject obj = ConfigHandler.GSON.fromJson(reader, JsonObject.class);
            this.claimBlocks = obj.get("ClaimBlocks").getAsInt();
            this.additionalClaimBlocks = obj.get("AdditionalBlocks").getAsInt();
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void editForOfflinePlayer(MinecraftServer server, UUID uuid, int additionalClaimBlocks) {
        File dir = new File(server.getSavePath(WorldSavePath.PLAYERDATA).toFile(), "/claimData/");
        if (!dir.exists())
            dir.mkdirs();
        try {
            File file = new File(dir, uuid.toString() + ".json");
            if (!file.exists())
                file.createNewFile();
            FileReader reader = new FileReader(file);
            JsonObject obj = ConfigHandler.GSON.fromJson(reader, JsonObject.class);
            reader.close();
            if (obj == null)
                obj = new JsonObject();
            int additionalBlocks = obj.get("AdditionalBlocks").getAsInt();
            obj.addProperty("AdditionalBlocks", additionalBlocks + additionalClaimBlocks);
            FileWriter writer = new FileWriter(file);
            ConfigHandler.GSON.toJson(obj, writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int calculateUsedClaimBlocks() {
        int usedClaimsBlocks = 0;
        for (ServerWorld world : this.player.getServer().getWorlds()) {
            Collection<Claim> claims = ClaimStorage.get(world).allClaimsFromPlayer(this.player.getUuid());
            if (claims != null)
                usedClaimsBlocks += claims.stream().filter(claim->claim.getOwner()!=null).mapToInt(Claim::getPlane).sum();
        }
        return usedClaimsBlocks;
    }

    public static void readGriefPreventionPlayerData(MinecraftServer server) {
        File griefPrevention = server.getSavePath(WorldSavePath.ROOT).resolve("plugins/GriefPreventionData/PlayerData").toFile();
        if (!griefPrevention.exists())
            return;
        try {
            for (File f : griefPrevention.listFiles()) {
                if (f.getName().startsWith("$")) {

                } else {
                    BufferedReader reader = new BufferedReader(new FileReader(f));
                    PlayerEntity player = server.getPlayerManager().getPlayer(UUID.fromString(f.getName()));
                    if (player != null) {
                        PlayerClaimData data = PlayerClaimData.get(player);
                        reader.readLine();
                        data.claimBlocks = Integer.parseInt(reader.readLine());
                        data.additionalClaimBlocks = Integer.parseInt(reader.readLine());
                    } else {
                        File dir = new File(server.getSavePath(WorldSavePath.PLAYERDATA).toFile(), "/claimData/");
                        if (!dir.exists())
                            dir.mkdir();
                        File file = new File(dir, f.getName() + ".json");
                        if (!file.exists())
                            file.createNewFile();
                        reader.readLine();
                        FileWriter writer = new FileWriter(file);
                        JsonObject obj = new JsonObject();
                        obj.addProperty("ClaimBlocks", reader.readLine());
                        obj.addProperty("AdditionalBlocks", reader.readLine());
                        ConfigHandler.GSON.toJson(obj, writer);
                        writer.close();
                    }
                    reader.close();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
