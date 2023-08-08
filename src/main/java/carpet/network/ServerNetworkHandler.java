package carpet.network;

import carpet.CarpetServer;
import carpet.CarpetSettings;
import carpet.api.settings.CarpetRule;
import carpet.api.settings.RuleHelper;
import carpet.fakes.MinecraftServerInterface;
import carpet.fakes.ServerGamePacketListenerImplInterface;
import carpet.helpers.ServerTickRateManager;
import carpet.script.utils.SnoopyCommandSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class ServerNetworkHandler
{
    private static final Map<ServerPlayer, String> remoteCarpetPlayers = new HashMap<>();
    private static final Set<ServerPlayer> validCarpetPlayers = new HashSet<>();

    private static final Map<String, BiConsumer<ServerPlayer, Tag>> dataHandlers = Map.of(
        "clientCommand", (p, t) -> {
            handleClientCommand(p, (CompoundTag)t);
        }
    );

    public static void handleData(CarpetClient.CarpetPayload data, ServerPlayer player)
    {
        int id = data.command();
        if (id == CarpetClient.HELLO)
            onHello(player, data.data());
        if (id == CarpetClient.DATA)
            onClientData(player, data.data());
    }

    private static ClientboundCustomPayloadPacket make(DataBuilder builder) {
        CarpetClient.CarpetPayload carpetPayload = builder.build();
        return new ClientboundCustomPayloadPacket(carpetPayload);
    }

    private static ClientboundCustomPayloadPacket make(int command, CompoundTag data) {
        return new ClientboundCustomPayloadPacket(new CarpetClient.CarpetPayload(command, data));
    }

    public static void onPlayerJoin(ServerPlayer playerEntity)
    {
        if (!((ServerGamePacketListenerImplInterface)playerEntity.connection).getConnection().isMemoryConnection())
        {
            CompoundTag data = new CompoundTag();
            data.putString("version", CarpetSettings.carpetVersion);
            playerEntity.connection.send( make( CarpetClient.HI,  data));
        }
        else
        {
            validCarpetPlayers.add(playerEntity);
        }
    }

    public static void onHello(ServerPlayer playerEntity, CompoundTag packetData)
    {
        validCarpetPlayers.add(playerEntity);
        String clientVersion = packetData.getString("version");
        remoteCarpetPlayers.put(playerEntity, clientVersion);
        if (clientVersion.equals(CarpetSettings.carpetVersion))
            CarpetSettings.LOG.info("Player "+playerEntity.getName().getString()+" joined with a matching carpet client");
        else
            CarpetSettings.LOG.warn("Player "+playerEntity.getName().getString()+" joined with another carpet version: "+clientVersion);
        DataBuilder data = DataBuilder.create(playerEntity.server); // tickrate related settings are sent on world change
        CarpetServer.forEachManager(sm -> sm.getCarpetRules().forEach(data::withRule));
        playerEntity.connection.send(make(data));
    }

    public static void sendPlayerLevelData(ServerPlayer player, ServerLevel level) {
        if (CarpetSettings.superSecretSetting || !validCarpetPlayers.contains(player)) return;
        DataBuilder data = DataBuilder.create(player.server).withTickRate().withFrozenState().withTickPlayerActiveTimeout(); // .withSuperHotState()
        player.connection.send(make(data));
    }

    private static void handleClientCommand(ServerPlayer player, CompoundTag commandData)
    {
        String command = commandData.getString("command");
        String id = commandData.getString("id");
        List<Component> output = new ArrayList<>();
        Component[] error = {null};
        int resultCode = -1;
        if (player.getServer() == null)
        {
            error[0] = Component.literal("No Server");
        }
        else
        {
            resultCode = player.getServer().getCommands().performPrefixedCommand(
                    new SnoopyCommandSource(player, error, output), command
            );
        }
        CompoundTag result = new CompoundTag();
        result.putString("id", id);
        result.putInt("code", resultCode);
        if (error[0] != null) result.putString("error", error[0].getContents().toString());
        ListTag outputResult = new ListTag();
        for (Component line: output) outputResult.add(StringTag.valueOf(Component.Serializer.toJson(line)));
        if (!output.isEmpty()) result.put("output", outputResult);
        player.connection.send(make( DataBuilder.create(player.server).withCustomNbt("clientCommand", result)));
        // run command plug to command output,
    }

    private static void onClientData(ServerPlayer player, CompoundTag compound)
    {
        for (String key: compound.getAllKeys())
        {
            if (dataHandlers.containsKey(key))
                dataHandlers.get(key).accept(player, compound.get(key));
            else
                CarpetSettings.LOG.warn("Unknown carpet client data: "+key);
        }
    }

    public static void updateRuleWithConnectedClients(CarpetRule<?> rule)
    {
        if (CarpetSettings.superSecretSetting) return;
        for (ServerPlayer player : remoteCarpetPlayers.keySet())
        {
            player.connection.send(make(DataBuilder.create(player.server).withRule(rule)));
        }
    }
    
    public static void updateTickSpeedToConnectedPlayers(MinecraftServer server)
    {
        if (CarpetSettings.superSecretSetting) return;
        for (ServerPlayer player : validCarpetPlayers)
        {
            player.connection.send(make(DataBuilder.create(player.server).withTickRate()));
        }
    }

    public static void updateFrozenStateToConnectedPlayers(MinecraftServer server)
    {
        if (CarpetSettings.superSecretSetting) return;
        for (ServerPlayer player : validCarpetPlayers)
        {
            player.connection.send(make(DataBuilder.create(player.server).withFrozenState()));
        }
    }

    public static void updateSuperHotStateToConnectedPlayers(MinecraftServer server)
    {
        if(CarpetSettings.superSecretSetting) return;
        for (ServerPlayer player : validCarpetPlayers)
        {
            player.connection.send(make(DataBuilder.create(player.server).withSuperHotState()));
        }
    }

    public static void updateTickPlayerActiveTimeoutToConnectedPlayers(MinecraftServer server)
    {
        if (CarpetSettings.superSecretSetting) return;
        for (ServerPlayer player : validCarpetPlayers)
        {
            player.connection.send(make(DataBuilder.create(player.server).withTickPlayerActiveTimeout()));
        }
    }

    public static void broadcastCustomCommand(String command, Tag data)
    {
        if (CarpetSettings.superSecretSetting) return;
        for (ServerPlayer player : validCarpetPlayers)
        {
            player.connection.send(make(DataBuilder.create(player.server).withCustomNbt(command, data)));
        }
    }

    public static void sendCustomCommand(ServerPlayer player, String command, Tag data)
    {
        if (isValidCarpetPlayer(player))
        {
            player.connection.send(make(DataBuilder.create(player.server).withCustomNbt(command, data)));
        }
    }

    public static void onPlayerLoggedOut(ServerPlayer player)
    {
        validCarpetPlayers.remove(player);
        if (!((ServerGamePacketListenerImplInterface)player.connection).getConnection().isMemoryConnection())
            remoteCarpetPlayers.remove(player);
    }

    public static void close()
    {
        remoteCarpetPlayers.clear();
        validCarpetPlayers.clear();
    }

    public static boolean isValidCarpetPlayer(ServerPlayer player)
    {
        if (CarpetSettings.superSecretSetting) return false;
        return validCarpetPlayers.contains(player);

    }

    public static String getPlayerStatus(ServerPlayer player)
    {
        if (remoteCarpetPlayers.containsKey(player)) return "carpet "+remoteCarpetPlayers.get(player);
        if (validCarpetPlayers.contains(player)) return "carpet "+CarpetSettings.carpetVersion;
        return "vanilla";
    }

    private static class DataBuilder
    {
        private CompoundTag tag;
        private MinecraftServer server;
        private static DataBuilder create(final MinecraftServer server)
        {
            return new DataBuilder(server);
        }
        private DataBuilder(MinecraftServer server)
        {
            tag = new CompoundTag();
            this.server = server;
        }
        private DataBuilder withTickRate()
        {
            ServerTickRateManager trm = ((MinecraftServerInterface)server).getTickRateManager();
            tag.putFloat("TickRate", trm.tickrate());
            return this;
        }
        private DataBuilder withFrozenState()
        {
            ServerTickRateManager trm = ((MinecraftServerInterface)server).getTickRateManager();
            CompoundTag tickingState = new CompoundTag();
            tickingState.putBoolean("is_paused", trm.gameIsPaused());
            tickingState.putBoolean("deepFreeze", trm.deeplyFrozen());
            tag.put("TickingState", tickingState);
            return this;
        }
        private DataBuilder withSuperHotState()
        {
            ServerTickRateManager trm = ((MinecraftServerInterface)server).getTickRateManager();
        	tag.putBoolean("SuperHotState", trm.isSuperHot());
        	return this;
        }
        private DataBuilder withTickPlayerActiveTimeout()
        {
            ServerTickRateManager trm = ((MinecraftServerInterface)server).getTickRateManager();
            tag.putInt("TickPlayerActiveTimeout", trm.getPlayerActiveTimeout());
            return this;
        }
        private DataBuilder withRule(CarpetRule<?> rule)
        {
            CompoundTag rules = (CompoundTag) tag.get("Rules");
            if (rules == null)
            {
                rules = new CompoundTag();
                tag.put("Rules", rules);
            }
            String identifier = rule.settingsManager().identifier();
            String key = rule.name();
            while (rules.contains(key)) { key = key+"2";}
            CompoundTag ruleNBT = new CompoundTag();
            ruleNBT.putString("Value", RuleHelper.toRuleString(rule.value()));
            ruleNBT.putString("Manager", identifier);
            ruleNBT.putString("Rule", rule.name());
            rules.put(key, ruleNBT);
            return this;
        }

        public DataBuilder withCustomNbt(String key, Tag value)
        {
            tag.put(key, value);
            return this;
        }

        private CarpetClient.CarpetPayload build()
        {
            return new CarpetClient.CarpetPayload(CarpetClient.DATA, tag);
        }
    }
}
