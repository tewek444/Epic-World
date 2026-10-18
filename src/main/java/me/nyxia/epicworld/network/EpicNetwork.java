package me.nyxia.epicworld.network;

import me.nyxia.epicworld.pregen.Pregenerator;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.PacketDistributor;

/** The two packets of the pregeneration screen: the server opens it, the screen sends back what to do. */
public final class EpicNetwork {
    private EpicNetwork() {}

    /** Server to client: open the screen, with the player's position and the current task. */
    public record OpenPregen(int x, int z, boolean running, boolean paused, Component status) implements CustomPacketPayload {
        public static final Type<OpenPregen> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("epicworld", "open_pregen"));
        public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, OpenPregen> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenPregen::x, ByteBufCodecs.VAR_INT, OpenPregen::z,
                ByteBufCodecs.BOOL, OpenPregen::running, ByteBufCodecs.BOOL, OpenPregen::paused,
                net.minecraft.network.chat.ComponentSerialization.TRUSTED_STREAM_CODEC, OpenPregen::status, OpenPregen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: start (block coordinates), pause, resume or cancel. */
    public record PregenAction(int action, int x1, int z1, int x2, int z2, boolean circle) implements CustomPacketPayload {
        public static final int START = 0, PAUSE = 1, RESUME = 2, CANCEL = 3;
        public static final Type<PregenAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("epicworld", "pregen_action"));
        public static final StreamCodec<FriendlyByteBuf, PregenAction> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, PregenAction::action, ByteBufCodecs.VAR_INT, PregenAction::x1, ByteBufCodecs.VAR_INT, PregenAction::z1,
                ByteBufCodecs.VAR_INT, PregenAction::x2, ByteBufCodecs.VAR_INT, PregenAction::z2, ByteBufCodecs.BOOL, PregenAction::circle,
                PregenAction::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToClient(OpenPregen.TYPE, OpenPregen.CODEC,
                (payload, context) -> context.enqueueWork(() -> me.nyxia.epicworld.client.PregenScreen.open(payload)));
        registrar.playToServer(PregenAction.TYPE, PregenAction.CODEC, EpicNetwork::handleAction);
    }

    public static void openScreen(ServerPlayer player) {
        var task = Pregenerator.task();
        Component status = task == null ? Component.empty() : task.progress();
        PacketDistributor.sendToPlayer(player, new OpenPregen(player.getBlockX(), player.getBlockZ(), task != null,
                task != null && task.paused(), status));
    }

    private static void handleAction(PregenAction action, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!player.hasPermissions(2)) {
                player.sendSystemMessage(Component.translatable("epicworld.pregen.no_permission"));
                return;
            }
            player.sendSystemMessage(apply(action, player));
        });
    }

    /** Runs an action from the screen or a command; the message to show. */
    public static Component apply(PregenAction action, ServerPlayer player) {
        var task = Pregenerator.task();
        return switch (action.action()) {
            case PregenAction.START -> {
                Component started = Pregenerator.start(player.serverLevel(), action.x1(), action.z1(), action.x2(), action.z2(), action.circle(), player);
                yield started != null ? started : Component.translatable("epicworld.pregen.busy");
            }
            case PregenAction.PAUSE, PregenAction.RESUME -> {
                if (task == null) yield Component.translatable("epicworld.pregen.none");
                boolean pause = action.action() == PregenAction.PAUSE;
                task.pause(pause);
                yield Component.translatable(pause ? "epicworld.pregen.paused" : "epicworld.pregen.resumed");
            }
            default -> {
                if (task == null) yield Component.translatable("epicworld.pregen.none");
                long done = task.done();
                task.cancel();
                yield Component.translatable("epicworld.pregen.cancelled", done);
            }
        };
    }
}
