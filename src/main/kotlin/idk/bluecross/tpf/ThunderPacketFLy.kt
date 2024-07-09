package idk.bluecross.tpf

import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerMoveEvent
import com.lambda.client.event.events.PushOutOfBlocksEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.Bind
import com.lambda.client.util.threads.safeListener
import idk.bluecross.tpf.util.*
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent


object ThunderPacketFLy : PluginModule(
    name = "ThunderPacketFly",
    description = "PacketFly from Thunder client for 2b2t.org.ru",
    category = Category.MOVEMENT,
    alias = arrayOf("PF", "TPF"),
    modulePriority = 1000,
    pluginMain = ThunderPacketFLyPlugin
) {
    var speed by setting("Speed", 0.97f, 0.1f..2f, 0.1f)
    var boost by setting("Boost", false)
    var jitter by setting("Jitter", false,)
    var constrict by setting("Constrict", false)
    var noPhaseSlow by setting("NoPhaseSlow", false)
    var multiAxis by setting("MultiAxis", false)
    var bounds by setting("Bounds", false)
    var strict by setting("Strict", false)
    var type by setting("Type", Type.FAST)
    val factorize by setting("Snap", Bind(), { type === Type.FACTOR })
    var motion by setting("Distance", 20f, 1f..20f, 1f, { type === Type.FACTOR })
    var factor by setting("Factor", 2f, 1f..10f, 1f, { type === Type.FACTOR || type === Type.DESYNC })
    var packetMode by setting("PacketMode", Mode.LIMITJITTER)
    var phase by setting("Phase", Phase.NCP)
    var antiKickMode by setting("AntiKick", AntiKick.NONE)
    var limit by setting("Limit", Limit.NONE)

    var startingPacket by setting("Starting Out Of Bounds Packet", false)
    var upSpeed by setting("Up Speed", 0.062f, 0f..1f, 0.01f)
    var downSpeed by setting("Down Speed", 0.1f, 0f..1f, 0.01f)

    var fallSpeed by setting(
        "Fall speed",
        0.032f,
        0f..1f,
        0.01f,
        { antiKickMode != AntiKick.NONE && antiKickMode != AntiKick.STRICT })
    var outOfBoundsPackets by setting("Send Out Of Bounds", true)
    var duplicateNormalPacket by setting("Duplicate normal packet", false)

    var confirmTeleports by setting("Confirm Teleports", true)
    var preConfirmNextTeleport by setting("Pre Confirm Next Teleport", false)
    var preConfirmPreviousTeleport by setting("Pre Confirm Previous Teleport", false)
    var cancelCPacketPlayer by setting("Cancel CPacketPlayer other that Position", false)

    init {
        onEnable {
            MinecraftForge.EVENT_BUS.register(this)
            OriginalModule.onEnable(this, bounds, packetMode)
        }
        onDisable {
            OriginalModule.onDisable(this)
            MinecraftForge.EVENT_BUS.unregister(this)
        }
        safeListener<PacketEvent.Send> { event ->
            OriginalModule.onSend(event,cancelCPacketPlayer)
        }
        safeListener<PlayerMoveEvent> { event ->
            OriginalModule.onMove(event, type, phase)
        }
        safeListener<PacketEvent.Receive> { event ->
            OriginalModule.onReceive(event, type)
        }
        safeListener<PushOutOfBlocksEvent> { event ->
            OriginalModule.onPushOutOfBlocks(event)
        }
    }

    @SubscribeEvent
    fun PushEvent(event: PushEvent) {
        OriginalModule.onPush(event)
    }

    @SubscribeEvent
    fun UpdatePlayerEvent(event: UpdatePlayerEvent) {
        OriginalModule.onPlayerUpdate(
            this,
            type,
            multiAxis,
            limit,
            antiKickMode,
            phase,
            noPhaseSlow,
            packetMode,
            bounds,
            speed,
            jitter,
            factor,
            factorize,
            motion,
            constrict,
            strict,
            startingPacket,
            upSpeed,
            downSpeed,
            fallSpeed,
            confirmTeleports,
            preConfirmPreviousTeleport,
            preConfirmNextTeleport,
            outOfBoundsPackets,
            duplicateNormalPacket
        )
    }

    @SubscribeEvent
    fun ClientDisconnectionFromServerEvent(e: ClientDisconnectionFromServerEvent) {
        OriginalModule.onDisconnect(this)
    }

    @SubscribeEvent
    fun LivingUpdateEvent(e: LivingEvent.LivingUpdateEvent) {
        OriginalModule.onUpdate(this, boost)
    }
}