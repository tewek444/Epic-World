package me.nyxia.epicworld.voxy;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Packets of the LOD service: a client with Voxy says hello, the server sends chunks' sections for its LODs. */
public final class LodPayloads {
    private LodPayloads() {}

    public record Section(int y, LevelChunkSection section) {}

    /** Client to server: this client has Voxy and wants LODs. */
    public record Hello(int protocol) implements CustomPacketPayload {
        public static final int PROTOCOL = 1;
        public static final Type<Hello> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("epicworld", "lod_hello"));
        public static final StreamCodec<FriendlyByteBuf, Hello> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.protocol()), buf -> new Hello(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: the non-empty sections of one chunk, in the network format of chunk packets. */
    public record Chunk(ResourceLocation dimension, int x, int z, List<Section> sections) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("epicworld", "lod_chunk"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Chunk> CODEC = StreamCodec.of(Chunk::write, Chunk::read);

        private static void write(RegistryFriendlyByteBuf buf, Chunk p) {
            buf.writeResourceLocation(p.dimension());
            buf.writeVarInt(p.x());
            buf.writeVarInt(p.z());
            buf.writeVarInt(p.sections().size());
            for (Section s : p.sections()) {
                buf.writeVarInt(s.y());
                s.section().write(buf);
            }
        }

        private static Chunk read(RegistryFriendlyByteBuf buf) {
            var dimension = buf.readResourceLocation();
            int x = buf.readVarInt();
            int z = buf.readVarInt();
            int n = buf.readVarInt();
            var biomes = buf.registryAccess().registryOrThrow(Registries.BIOME);
            var sections = new ArrayList<Section>(n);
            for (int i = 0; i < n; i++) {
                int y = buf.readVarInt();
                var section = new LevelChunkSection(biomes);
                section.read(buf);
                sections.add(new Section(y, section));
            }
            return new Chunk(dimension, x, z, sections);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
