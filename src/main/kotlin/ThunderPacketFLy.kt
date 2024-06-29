import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerMoveEvent
import com.lambda.client.event.events.PushOutOfBlocksEvent
import com.lambda.client.manager.managers.TimerManager.modifyTimer
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.Bind
import com.lambda.client.util.threads.safeListener
import com.lambda.mixin.accessor.network.AccessorSPacketPosLook
import net.minecraft.client.gui.GuiDisconnected
import net.minecraft.client.gui.GuiDownloadTerrain
import net.minecraft.client.gui.GuiMainMenu
import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.network.play.client.CPacketConfirmTeleport
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent
import util.PushEvent
import util.TimeVec3d
import util.Timer
import util.UpdatePlayerEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import kotlin.math.*


object ThunderPacketFLy : PluginModule(
    name = "ThunderPacketFly",
    description = "PacketFly from Thunder client for 2b2t.org.ru",
    category = Category.MOVEMENT,
    alias = arrayOf("PF", "TPF"),
    modulePriority = 10,
    pluginMain = ThunderPacketFLyPlugin
) {
    val random: Random = Random()
    var speed by setting("Speed", 1.0f, 0.1f..2f, 0.1f, { true })

    var boost by setting("Boost", false, { true })
    var jitter by setting("Jitter", false, { true })
    var constrict by setting("Constrict", false, { true })
    var noPhaseSlow by setting("NoPhaseSlow", false, { true })
    var multiAxis by setting("MultiAxis", false, { true })
    var bounds by setting("Bounds", false, { true })
    var strict by setting("Strict", true, { true })
    var speedX: Double = 0.0
    var speedY: Double = 0.0
    var speedZ: Double = 0.0
    private val type by setting("Type", Type.FAST)
    private val facrotize by setting("Snap", Bind(), { type === Type.FACTOR })
    var motion by setting("Distance", 5f, 1f..20f, 1f, { type === Type.FACTOR })
    var factor by setting("Factor", 1f, 1f..10f, 1f, { type === Type.FACTOR || type === Type.DESYNC })

    private val packetMode by setting("PacketMode", Mode.UP)
    private val phase by setting("Phase", Phase.NCP)
    private val antiKickMode by setting("AntiKick", AntiKick.NORMAL)
    private val limit by setting("Limit", Limit.NONE)
    private var teleportId = 0
    private var startingOutOfBoundsPos: CPacketPlayer.Position? = null
    private val packets = ArrayList<CPacketPlayer>()
    private val posLooks: MutableMap<Int, TimeVec3d> = ConcurrentHashMap<Int, TimeVec3d>()
    private var antiKickTicks = 0
    private var vDelay = 0
    private var hDelay = 0
    private var limitStrict = false
    private var limitTicks = 0
    private var jitterTicks = 0
    private var oddJitter = false
    private var postYaw = -400f
    private var postPitch = -400f

    private var factorCounter = 0

    private val intervalTimer: Timer = Timer()

    fun randomLimitedVertical(): Double {
        var randomValue = random.nextInt(22)
        randomValue += 70
        if (random.nextBoolean()) {
            return randomValue.toDouble()
        }
        return (-randomValue).toDouble()
    }

    fun randomLimitedHorizontal(): Double {
        val randomValue = random.nextInt(10)
        if (random.nextBoolean()) {
            return randomValue.toDouble()
        }
        return (-randomValue).toDouble()
    }

    fun directionSpeed(speed: Double): DoubleArray {
        var forward = mc.player.movementInput.moveForward
        var side = mc.player.movementInput.moveStrafe
        var yaw =
            mc.player.prevRotationYaw + (mc.player.rotationYaw - mc.player.prevRotationYaw) * mc.renderPartialTicks

        if (forward != 0.0f) {
            if (side > 0.0f) {
                yaw += (if ((forward > 0.0f)) -45 else 45).toFloat()
            } else if (side < 0.0f) {
                yaw += (if ((forward > 0.0f)) 45 else -45).toFloat()
            }
            side = 0.0f
            if (forward > 0.0f) {
                forward = 1.0f
            } else if (forward < 0.0f) {
                forward = -1.0f
            }
        }

        val sin = sin(Math.toRadians((yaw + 90.0f).toDouble()))
        val cos = cos(Math.toRadians((yaw + 90.0f).toDouble()))
        val posX = forward * speed * cos + side * speed * sin
        val posZ = forward * speed * sin - side * speed * cos
        return doubleArrayOf(posX, posZ)
    }

    private fun sendPackets(
        x: Double,
        y: Double,
        z: Double,
        mode: Mode,
        sendConfirmTeleport: Boolean,
        sendExtraCT: Boolean
    ) {
        val nextPos = Vec3d(mc.player.posX + x, mc.player.posY + y, mc.player.posZ + z)
        val bounds = getBoundsVec(x, y, z, mode)

        val nextPosPacket: CPacketPlayer =
            CPacketPlayer.Position(nextPos.x, nextPos.y, nextPos.z, mc.player.onGround)
        packets.add(nextPosPacket)
        mc.player.connection.sendPacket(nextPosPacket)

        if (limit !== Limit.NONE && limitTicks == 0) return

        val boundsPacket: CPacketPlayer =
            CPacketPlayer.Position(bounds.x, bounds.y, bounds.z, mc.player.onGround)
        packets.add(boundsPacket)
        mc.player.connection.sendPacket(boundsPacket)

        if (sendConfirmTeleport) {
            teleportId++

            if (sendExtraCT) {
                mc.player.connection.sendPacket(CPacketConfirmTeleport(teleportId - 1))
            }

            mc.player.connection.sendPacket(CPacketConfirmTeleport(teleportId))

            posLooks[teleportId] = TimeVec3d(nextPos.x, nextPos.y, nextPos.z, System.currentTimeMillis())

            if (sendExtraCT) {
                mc.player.connection.sendPacket(CPacketConfirmTeleport(teleportId + 1))
            }
        }

        /*
        if (type != Type.FACTOR && type != Type.NOJITTER && packetMode != Mode.BYPASS) {
            CPacketPlayer currentPos = new CPacketPlayer.Position(mc.player.posX, mc.player.posY, mc.player.posZ, false);
            packets.add(currentPos);
            mc.player.connection.sendPacket(currentPos);
        }
         */
    }

    private fun getBoundsVec(x: Double, y: Double, z: Double, mode: Mode): Vec3d {
        when (mode) {
            Mode.UP -> return Vec3d(
                mc.player.posX + x,
                if (bounds) (if (strict) 255 else 256).toDouble() else mc.player.posY + 420,
                mc.player.posZ + z
            )

            Mode.PRESERVE -> return Vec3d(
                if (bounds) mc.player.posX + randomHorizontal() else randomHorizontal(),
                if (strict) (max(
                    mc.player.posY, 2.0
                )) else mc.player.posY,
                if (bounds) mc.player.posZ + randomHorizontal() else randomHorizontal()
            )

            Mode.LIMITJITTER -> return Vec3d(
                mc.player.posX + (if (strict) x else randomLimitedHorizontal()),
                mc.player.posY + randomLimitedVertical(),
                mc.player.posZ + (if (strict) z else randomLimitedHorizontal())
            )

            Mode.BYPASS -> if (bounds) {
                val rawY = y * 510
                return Vec3d(
                    mc.player.posX + x,
                    mc.player.posY + (if ((rawY > (if ((mc.player.dimension == -1)) 127 else 255))) -rawY else if ((rawY < 1)) -rawY else rawY),
                    mc.player.posZ + z
                )
            } else {
                return Vec3d(
                    mc.player.posX + (if (x == 0.0) (if (random.nextBoolean()) -10 else 10).toDouble() else x * 38),
                    mc.player.posY + y,
                    mc.player.posX + (if (z == 0.0) (if (random.nextBoolean()) -10 else 10).toDouble() else z * 38)
                )
            }

            Mode.OBSCURE -> return Vec3d(
                mc.player.posX + randomHorizontal(),
                max(1.5, min(mc.player.posY + y, 253.5)),
                mc.player.posZ + randomHorizontal()
            )

            else -> return Vec3d(
                mc.player.posX + x,
                if (bounds) (if (strict) 1 else 0).toDouble() else mc.player.posY - 1337,
                mc.player.posZ + z
            )
        }
    }

    fun randomHorizontal(): Double {
        val randomValue =
            random.nextInt(if (bounds) 80 else (if (packetMode === Mode.OBSCURE) (if (mc.player.ticksExisted % 2 == 0) 480 else 100) else 29000000)) + (if (bounds) 5 else 500)
        if (random.nextBoolean()) {
            return randomValue.toDouble()
        }
        return (-randomValue).toDouble()
    }

    private fun cleanPosLooks() {
        posLooks.forEach(BiConsumer<Int, TimeVec3d> { tp: Int, timeVec3d: TimeVec3d ->
            if (System.currentTimeMillis() - timeVec3d.time > TimeUnit.SECONDS.toMillis(
                    30L
                )
            ) {
                posLooks.remove(tp)
            }
        })
    }

    fun onEnable() {
        if (mc.player == null || mc.world == null) {
            toggle()
            return
        }
        packets.clear()
        posLooks.clear()
        teleportId = 0
        vDelay = 0
        hDelay = 0
        postYaw = -400f
        postPitch = -400f
        antiKickTicks = 0
        limitTicks = 0
        jitterTicks = 0
        speedX = 0.0
        speedY = 0.0
        speedZ = 0.0
        oddJitter = false
        startingOutOfBoundsPos = null
        startingOutOfBoundsPos =
            CPacketPlayer.Position(randomHorizontal(), 1.0, randomHorizontal(), mc.player.onGround)
        packets.add(startingOutOfBoundsPos!!)
        mc.player.connection.sendPacket(startingOutOfBoundsPos)
    }

    fun onDisable() {
        if (mc.player != null) {
            mc.player.setVelocity(0.0, 0.0, 0.0)
        }
        //KonasGlobals.INSTANCE.timerManager.resetTimer(this); // хыхыхых
        modifyTimer(1f)
    }

    private fun checkCollisionBox(): Boolean {
        if (mc.world.getCollisionBoxes(
                mc.player,
                mc.player.entityBoundingBox.expand(0.0, 0.0, 0.0)
            ).isNotEmpty()
        ) {
            return true
        }
        return mc.world.getCollisionBoxes(
            mc.player,
            mc.player.entityBoundingBox.offset(0.0, 2.0, 0.0).contract(0.0, 1.99, 0.0)
        ).isNotEmpty()
    }

    init {
        onEnable {
            onEnable()
        }
        onDisable() {
            onDisable()
        }
        safeListener<PacketEvent.Send> { event ->
            if (event.packet is CPacketPlayer && event.packet !is CPacketPlayer.Position) {
                event.cancel()
            }
            if (event.packet is CPacketPlayer) {
                val packet: CPacketPlayer = event.packet as CPacketPlayer
                if (packets.contains(packet)) {
                    packets.remove(packet)
                    return@safeListener
                }
                event.cancel()
            }
        }
        safeListener<PlayerMoveEvent> { event ->
            if (type !== Type.SETBACK && teleportId <= 0) {
                return@safeListener
            }

            if (type !== Type.SLOW) {
                event.x = speedX
                event.y = speedY
                event.z = speedZ
            }

            if (phase !== Phase.NONE && phase === Phase.VANILLA || checkCollisionBox()) {
                mc.player.noClip = true
            }
        }
        safeListener<PacketEvent.Receive> { event ->
            if (event.packet is SPacketPlayerPosLook) {
                if (mc.currentScreen !is GuiDownloadTerrain) {
                    val packet: SPacketPlayerPosLook = event.packet as SPacketPlayerPosLook
                    if (mc.player.isEntityAlive) {
                        if (teleportId <= 0) {
                            teleportId = (event.packet as SPacketPlayerPosLook).teleportId
                        } else {
                            if (mc.world.isBlockLoaded(
                                    BlockPos(
                                        mc.player.posX,
                                        mc.player.posY,
                                        mc.player.posZ
                                    ), false
                                ) &&
                                type !== Type.SETBACK
                            ) {
                                if (type === Type.DESYNC) {
                                    posLooks.remove(packet.teleportId)
                                    event.cancel()
                                    if (type === Type.SLOW) {
                                        mc.player.setPosition(packet.x, packet.y, packet.z)
                                    }
                                    return@safeListener
                                } else if (posLooks.containsKey(packet.teleportId)) {
                                    val vec: TimeVec3d? = posLooks[packet.teleportId]
                                    if (vec != null) {
                                        if (vec.x === packet.x && vec.y === packet.y && vec.z === packet.z) {
                                            posLooks.remove(packet.teleportId)
                                            event.cancel()
                                            if (type === Type.SLOW) {
                                                mc.player.setPosition(packet.x, packet.y, packet.z)
                                            }
                                            return@safeListener
                                        }
                                    }
                                }
                            }
                        }
                    }
                    (packet as AccessorSPacketPosLook).setYaw(mc.player.rotationYaw) // access transformers
                    (packet as AccessorSPacketPosLook).setPitch(mc.player.rotationPitch)
                    packet.flags.remove(SPacketPlayerPosLook.EnumFlags.X_ROT)
                    packet.flags.remove(SPacketPlayerPosLook.EnumFlags.Y_ROT)
                    teleportId = packet.teleportId
                } else {
                    teleportId = 0
                }
            }
        }
        safeListener<UpdatePlayerEvent> {
            if (mc.player.ticksExisted % 20 == 0) {
                cleanPosLooks()
            }

            mc.player.setVelocity(0.0, 0.0, 0.0)

            if (teleportId <= 0 && type !== Type.SETBACK) {
                // sending this without any other packets will probs cause server to send SPacketPlayerPosLook to fix our pos
                startingOutOfBoundsPos =
                    CPacketPlayer.Position(randomHorizontal(), 1.0, randomHorizontal(), mc.player.onGround)
                packets.add(startingOutOfBoundsPos!!)
                mc.player.connection.sendPacket(startingOutOfBoundsPos)
                return@safeListener
            }

            val phasing = checkCollisionBox()

            speedX = 0.0
            speedY = 0.0
            speedZ = 0.0

            if (mc.gameSettings.keyBindJump.isKeyDown && (hDelay < 1 || (multiAxis && phasing))) {
                speedY =
                    if (mc.player.ticksExisted % (if (type === Type.SETBACK || type === Type.SLOW || limit === Limit.STRICT) 10 else 20) == 0) {
                        if ((antiKickMode !== AntiKick.NONE)) -0.032 else 0.062
                    } else {
                        0.062
                    }
                antiKickTicks = 0
                vDelay = 5
            } else if (mc.gameSettings.keyBindSneak.isKeyDown && (hDelay < 1 || (multiAxis && phasing))) {
                speedY = -0.062
                antiKickTicks = 0
                vDelay = 5
            }

            if ((multiAxis && phasing) || !(mc.gameSettings.keyBindSneak.isKeyDown && mc.gameSettings.keyBindJump.isKeyDown)) {
                if (Companion.mc.gameSettings.keyBindForward.isKeyDown || Companion.mc.gameSettings.keyBindBack.isKeyDown || Companion.mc.gameSettings.keyBindRight.isKeyDown || Companion.mc.gameSettings.keyBindLeft.isKeyDown) {
                    val dir: DoubleArray =
                        directionSpeed((if (phasing && phase === Phase.NCP) (if (noPhaseSlow) (if (multiAxis) 0.0465 else 0.062) else 0.031) else 0.26) * speed)
                    if ((dir[0] != 0.0 || dir[1] != 0.0) && (vDelay < 1 || (multiAxis && phasing))) {
                        speedX = dir[0]
                        speedZ = dir[1]
                        hDelay = 5
                    }
                }
                // WE CANNOT DO ANTIKICK AFTER FLYING UP OR DOWN!!! THIS CAN MESS UP SO MUCH STUFF
                if (antiKickMode !== AntiKick.NONE && (limit === Limit.NONE || limitTicks != 0)) {
                    if (antiKickTicks < (if (packetMode === Mode.BYPASS && !bounds) 1 else 3)) {
                        antiKickTicks++
                    } else {
                        antiKickTicks = 0
                        if (antiKickMode !== AntiKick.LIMITED || !phasing) {
                            speedY = if (antiKickMode === AntiKick.STRICT) -0.08 else -0.04
                        }
                    }
                }
            }

            if (phasing) {
                if (phase === Phase.NCP && mc.player.moveForward.toDouble() != 0.0 || mc.player.moveStrafing.toDouble() != 0.0 && speedY != 0.0) {
                    speedY /= 2.5
                }
            }

            if (limit !== Limit.NONE) {
                if (limitTicks == 0) {
                    speedX = 0.0
                    speedY = 0.0
                    speedZ = 0.0
                } else if (limitTicks == 2 && jitter) {
                    if (oddJitter) {
                        speedX = 0.0
                        speedY = 0.0
                        speedZ = 0.0
                    }
                    oddJitter = !oddJitter
                }
            } else if (jitter && jitterTicks == 7) {
                speedX = 0.0
                speedY = 0.0
                speedZ = 0.0
            }

            when (type) {
                Type.FAST -> {
                    mc.player.setVelocity(speedX, speedY, speedZ)
                    sendPackets(speedX, speedY, speedZ, packetMode, true, false)
                }

                Type.SLOW -> sendPackets(speedX, speedY, speedZ, packetMode, true, false)
                Type.SETBACK -> {
                    mc.player.setVelocity(speedX, speedY, speedZ)
                    sendPackets(speedX, speedY, speedZ, packetMode, false, false)
                }

                Type.FACTOR, Type.DESYNC -> {
                    var rawFactor: Float = factor
                    if (facrotize.isDown(facrotize.key) && intervalTimer.passedMs(3500)) {
                        intervalTimer.reset()
                        rawFactor = motion
                    }
                    var factorInt = floor(rawFactor.toDouble()).toInt()
                    factorCounter++
                    if (factorCounter > (20.0 / ((rawFactor - factorInt.toDouble()) * 20.0)).toInt()) {
                        factorInt += 1
                        factorCounter = 0
                    }
                    var i = 1
                    while (i <= factorInt) {
                        mc.player.setVelocity(speedX * i, speedY * i, speedZ * i)
                        sendPackets(speedX * i, speedY * i, speedZ * i, packetMode, true, false)
                        ++i
                    }
                    speedX = mc.player.motionX
                    speedY = mc.player.motionY
                    speedZ = mc.player.motionZ
                }
            }
            vDelay--
            hDelay--

            if (constrict && (limit === Limit.NONE || limitTicks > 1)) {
                mc.player.connection.sendPacket(
                    CPacketPlayer.Position(
                        mc.player.posX,
                        mc.player.posY,
                        mc.player.posZ,
                        false
                    )
                )
            }

            limitTicks++
            jitterTicks++

            if (limitTicks > (if ((limit === Limit.STRICT)) (if (limitStrict) 1 else 2) else 3)) {
                limitTicks = 0
                limitStrict = !limitStrict
            }

            if (jitterTicks > 7) {
                jitterTicks = 0
            }
        }
        safeListener<PushEvent> { it.isCanceled = true }
        safeListener<PushOutOfBlocksEvent> { it.cancel() }
        safeListener<ClientDisconnectionFromServerEvent> { disable() }
        safeListener<LivingEvent.LivingUpdateEvent> {
            // Prevents getting kicked from messing up your game
            if (mc.currentScreen is GuiDisconnected || mc.currentScreen is GuiMainMenu || mc.currentScreen is GuiMultiplayer ||
                mc.currentScreen is GuiDownloadTerrain
            ) {
                disable()
            }

            if (boost) {
                modifyTimer(1.088f)
            } else {
                modifyTimer(1f)
            }
        }
    }

    enum class Limit {
        NONE, STRONG, STRICT
    }

    enum class Mode {
        UP, PRESERVE, DOWN, LIMITJITTER, BYPASS, OBSCURE
    }

    enum class Type {
        FACTOR, SETBACK, FAST, SLOW, DESYNC
    }

    enum class Phase {
        NONE, VANILLA, NCP
    }

    private enum class AntiKick {
        NONE, NORMAL, LIMITED, STRICT
    }
}