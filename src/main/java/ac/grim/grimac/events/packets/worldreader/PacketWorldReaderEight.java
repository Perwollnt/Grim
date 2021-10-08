package ac.grim.grimac.events.packets.worldreader;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.chunkdata.eight.EightChunk;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.data.ChangeBlockData;
import io.github.retrooper.packetevents.event.impl.PacketPlaySendEvent;
import io.github.retrooper.packetevents.packettype.PacketType;
import io.github.retrooper.packetevents.packetwrappers.WrappedPacket;
import io.github.retrooper.packetevents.packetwrappers.play.out.mapchunk.WrappedPacketOutMapChunk;
import io.github.retrooper.packetevents.utils.reflection.Reflection;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class PacketWorldReaderEight extends PacketWorldReaderSeven {
    @Override
    public void onPacketPlaySend(PacketPlaySendEvent event) {
        super.onPacketPlaySend(event);

        byte packetID = event.getPacketId();

        // Time to dump chunk data for 1.9+ - 0.07 ms
        // Time to dump chunk data for 1.8 - 0.02 ms
        // Time to dump chunk data for 1.7 - 0.04 ms
        if (packetID == PacketType.Play.Server.MAP_CHUNK) {
            WrappedPacketOutMapChunk packet = new WrappedPacketOutMapChunk(event.getNMSPacket());
            GrimPlayer player = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(event.getPlayer());
            if (player == null) return;

            try {
                int chunkX = packet.getChunkX();
                int chunkZ = packet.getChunkZ();

                // Map chunk packet with 0 sections and continuous chunk is the unload packet in 1.7 and 1.8
                // Optional is only empty on 1.17 and above
                Object chunkMap = packet.readAnyObject(2);
                if (chunkMap.getClass().getDeclaredField("b").getInt(chunkMap) == 0 && packet.isGroundUpContinuous().get()) {
                    unloadChunk(player, chunkX, chunkZ);
                    return;
                }

                addChunkToCache(player, chunkX, chunkZ, false);
            } catch (NoSuchFieldException | IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void handleMultiBlockChange(GrimPlayer player, PacketPlaySendEvent event) {
        WrappedPacket packet = new WrappedPacket(event.getNMSPacket());
        if (player == null) return;

        try {
            // Section Position or Chunk Section - depending on version
            Object position = packet.readAnyObject(0);

            Object[] blockInformation;
            blockInformation = (Object[]) packet.readAnyObject(1);

            // This shouldn't be possible
            if (blockInformation.length == 0) return;

            Field getX = position.getClass().getDeclaredField("x");
            Field getZ = position.getClass().getDeclaredField("z");

            int chunkX = getX.getInt(position) << 4;
            int chunkZ = getZ.getInt(position) << 4;

            Field shortField = Reflection.getField(blockInformation[0].getClass(), 0);
            Field blockDataField = Reflection.getField(blockInformation[0].getClass(), 1);

            int range = (player.getTransactionPing() / 100) + 32;
            if (Math.abs(chunkX - player.x) < range && Math.abs(chunkZ - player.z) < range)
                event.setPostTask(player::sendTransaction);


            for (Object o : blockInformation) {
                short pos = shortField.getShort(o);
                int blockID = (int) getByCombinedID.invoke(null, blockDataField.get(o));

                int blockX = pos >> 12 & 15;
                int blockY = pos & 255;
                int blockZ = pos >> 8 & 15;

                player.compensatedWorld.worldChangedBlockQueue.add(new ChangeBlockData(player.lastTransactionSent.get() + 1, chunkX + blockX, blockY, chunkZ + blockZ, blockID));
            }

        } catch (IllegalAccessException | InvocationTargetException | NoSuchFieldException exception) {
            exception.printStackTrace();
        }
    }

    public void addChunkToCache(GrimPlayer player, int chunkX, int chunkZ, boolean isSync) {
        boolean wasAdded = false;

        try {
            EightChunk[] chunks = new EightChunk[16];

            if (isSync || player.bukkitPlayer.getWorld().isChunkLoaded(chunkX, chunkZ)) {
                Chunk sentChunk = player.bukkitPlayer.getWorld().getChunkAt(chunkX, chunkZ);

                Method handle = Reflection.getMethod(sentChunk.getClass(), "getHandle", 0);
                Object nmsChunk = handle.invoke(sentChunk);
                Method sections = Reflection.getMethod(nmsChunk.getClass(), "getSections", 0);
                Object sectionsArray = sections.invoke(nmsChunk);

                int arrayLength = Array.getLength(sectionsArray);

                Object zeroElement = Array.get(sectionsArray, 0);
                if (zeroElement == null)
                    return;

                Method getIds = Reflection.getMethod(zeroElement.getClass(), "getIdArray", 0);

                for (int x = 0; x < arrayLength; x++) {
                    Object section = Array.get(sectionsArray, x);
                    if (section == null) break;

                    chunks[x] = new EightChunk((char[]) getIds.invoke(section));
                }

                Column column = new Column(chunkX, chunkZ, chunks, player.lastTransactionSent.get() + 1);
                player.compensatedWorld.addToCache(column, chunkX, chunkZ);
                wasAdded = true;
            }
        } catch (InvocationTargetException | IllegalAccessException e) {
            e.printStackTrace();
        } finally {
            // If we fail on the main thread, we can't recover from this.
            if (!wasAdded && !isSync) {
                Bukkit.getScheduler().runTask(GrimAPI.INSTANCE.getPlugin(), () -> {
                    addChunkToCache(player, chunkX, chunkZ, true);
                });
            }
        }
    }
}
