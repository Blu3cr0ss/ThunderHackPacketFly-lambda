package idk.bluecross.tpf;

import com.lambda.client.event.events.PacketEvent;
import com.lambda.client.event.events.PlayerMoveEvent;
import com.lambda.client.event.events.PushOutOfBlocksEvent;
import com.lambda.client.plugin.api.PluginModule;
import com.lambda.client.util.Bind;
import com.lambda.mixin.accessor.network.AccessorSPacketPosLook;
import idk.bluecross.tpf.util.Timer;
import idk.bluecross.tpf.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.network.play.client.CPacketConfirmTeleport;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.server.SPacketPlayerPosLook;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class OriginalModule {
    static private final Random random = new Random();
    static private double speedX = 0;
    static private double speedY = 0;
    static private double speedZ = 0;
    static private int teleportId;
    static private CPacketPlayer.Position startingOutOfBoundsPos;
    static private final ArrayList<CPacketPlayer> packets = new ArrayList<>();
    static private final Map<Integer, TimeVec3d> posLooks = new ConcurrentHashMap<>();
    static private int antiKickTicks = 0;
    /**
     * Не двигаться вертикально следующие N PlayerUpdateEvent'ов
     */
    static private int verticalDelay = 0;
    /**
     * Не двигаться горизонтально следующие N PlayerUpdateEvent'ов
     */
    static private int horizontalDelay = 0;
    static private boolean limitStrict = false;
    static private int limitTicks = 0;
    static private int jitterTicks = 0;
    static private boolean oddJitter = false;
    static private Minecraft mc = Minecraft.getMinecraft();

    static private int factorCounter = 0;

    static private final Timer intervalTimer = new Timer();

    public static void onDisconnect(PluginModule module) {
        module.disable();
    }

    private static void modifyTimer(float tickLength) {
        idk.bluecross.tpf.Timer.INSTANCE.setMultiplier(tickLength);
    }

    public static void onUpdate(PluginModule module, boolean boost) {
        // Prevents getting kicked from messing up your game
        if (mc.currentScreen instanceof GuiDisconnected || mc.currentScreen instanceof GuiMainMenu || mc.currentScreen instanceof GuiMultiplayer ||
            mc.currentScreen instanceof GuiDownloadTerrain) {
            module.disable();
        }

        if (boost) {
            modifyTimer(1.088f);
        } else {
            modifyTimer(1f);
        }
    }

    public static void onPlayerUpdate(PluginModule module, Type type, boolean multiAxis, Limit limit, AntiKick antiKickMode, Phase phase, boolean noPhaseSlow, Mode packetMode, boolean bounds, float speed, boolean jitter, float factor, Bind factorize, float motion, boolean constrict, boolean strict, boolean startingPacket, float upSpeed, float downSpeed, float fallSpeed, boolean confirmTPs, boolean confirmPrevTP, boolean confirmNextTP, boolean sendOutOfBounds, boolean duplicateNormalPacket) { // PlayerUpdate works way better than most other events

        if (mc.player == null || mc.world == null) {
            module.disable();
            return;
        }

        if (mc.player.ticksExisted % 20 == 0) { // every 1s if 20 tps
            cleanPosLooks();
        }

        mc.player.setVelocity(0.0D, 0.0D, 0.0D);

        if (startingPacket && teleportId <= 0 && type != Type.SETBACK) {
            // sending this without any other packets will probs cause server to send SPacketPlayerPosLook to fix our pos
            startingOutOfBoundsPos = new CPacketPlayer.Position(randomHorizontal(bounds, packetMode), 1, randomHorizontal(bounds, packetMode), mc.player.onGround);
            packets.add(startingOutOfBoundsPos);
            mc.player.connection.sendPacket(startingOutOfBoundsPos);
            return;
        }

        boolean phasing = checkCollisionBox();

        speedX = 0;
        speedY = 0;
        speedZ = 0;

        if (mc.gameSettings.keyBindJump.isKeyDown() && (horizontalDelay < 1 || (multiAxis && phasing))) {
            if (antiKickMode != AntiKick.NONE &&
                mc.player.ticksExisted % (type == Type.SETBACK || type == Type.SLOW || limit == Limit.STRICT ? 10 : 20) == 0) {
                speedY = -fallSpeed; // Если антикик включен медленно падаем каждые 10 (для SETBACK SLOW STRICT) или 20 тиков
            } else {
                speedY = upSpeed;
            }
            antiKickTicks = 0;
            verticalDelay = 5;
        } else if (mc.gameSettings.keyBindSneak.isKeyDown() && (horizontalDelay < 1 || (multiAxis && phasing))) {
            speedY = -downSpeed;
            antiKickTicks = 0;
            verticalDelay = 5;
        }

        if ((multiAxis && phasing) || !(mc.gameSettings.keyBindSneak.isKeyDown() && mc.gameSettings.keyBindJump.isKeyDown())) {
            if (PlayerUtils.isPlayerMoving()) {
                double[] dir = PlayerUtils.directionSpeed((phasing && phase == Phase.NCP ? (noPhaseSlow ? (multiAxis ? 0.0465 : 0.062) : 0.031) : 0.26) * speed);
                if ((dir[0] != 0 || dir[1] != 0) && (verticalDelay < 1 || (multiAxis && phasing))) {
                    speedX = dir[0];
                    speedZ = dir[1];
                    horizontalDelay = 5;
                }
            }
            // WE CANNOT DO ANTIKICK AFTER FLYING UP OR DOWN!!! THIS CAN MESS UP SO MUCH STUFF
            // антикик хуйня энивей
            if (antiKickMode != AntiKick.NONE && (limit == Limit.NONE || limitTicks != 0)) {
                if (antiKickTicks < (packetMode == Mode.BYPASS && !bounds ? 1 : 3)) {
                    antiKickTicks++;
                } else {
                    antiKickTicks = 0;
                    if (antiKickMode != AntiKick.LIMITED || !phasing) {
                        speedY = antiKickMode == AntiKick.STRICT ? -0.08 : -0.04;
                    }
                }
            }
        }

        if (phasing) {
            if (phase == Phase.NCP && (double) mc.player.moveForward != 0.0 || (double) mc.player.moveStrafing != 0.0 && speedY != 0) {
                speedY /= 2.5;
            }
        }

        if (limit != Limit.NONE) {
            if (limitTicks == 0) {
                speedX = 0;
                speedY = 0;
                speedZ = 0;
            } else if (limitTicks == 2 && jitter) {
                if (oddJitter) {
                    speedX = 0;
                    speedY = 0;
                    speedZ = 0;
                }
                oddJitter = !oddJitter;
            }
        } else if (jitter && jitterTicks == 7) {
            speedX = 0;
            speedY = 0;
            speedZ = 0;
        }

        switch (type) {
            case FAST:
            case SETBACK:
                mc.player.setVelocity(speedX, speedY, speedZ);
                sendPackets(speedX, speedY, speedZ, packetMode, confirmTPs, confirmPrevTP, confirmNextTP, limit, bounds, strict, sendOutOfBounds, duplicateNormalPacket);
                break;
            case SLOW:
                sendPackets(speedX, speedY, speedZ, packetMode, confirmTPs, confirmPrevTP, confirmNextTP, limit, bounds, strict, sendOutOfBounds, duplicateNormalPacket);
                break;
            case FACTOR:
            case DESYNC:
                float rawFactor = factor;
                if (PlayerUtils.isKeyDown(factorize.getKey()) && intervalTimer.passedMs(3500)) {
                    intervalTimer.reset();
                    rawFactor = motion;
                }
                int factorInt = (int) Math.floor(rawFactor);
                factorCounter++;
                if (factorCounter > (int) (20D / ((rawFactor - (double) factorInt) * 20D))) {
                    factorInt += 1;
                    factorCounter = 0;
                }
                for (int i = 1; i <= factorInt; ++i) {
                    mc.player.setVelocity(speedX * i, speedY * i, speedZ * i);
                    sendPackets(speedX * i, speedY * i, speedZ * i, packetMode, confirmTPs, confirmPrevTP, confirmNextTP, limit, bounds, limitStrict, sendOutOfBounds, duplicateNormalPacket);
                }
                speedX = mc.player.motionX;
                speedY = mc.player.motionY;
                speedZ = mc.player.motionZ;
                break;
        }

        verticalDelay--;
        horizontalDelay--;

        if (constrict && (limit == Limit.NONE || limitTicks > 1)) {
            mc.player.connection.sendPacket(new CPacketPlayer.Position(mc.player.posX, mc.player.posY, mc.player.posZ, false));
        }

        limitTicks++;
        jitterTicks++;

        if (limitTicks > ((limit == Limit.STRICT) ? (limitStrict ? 1 : 2) : 3)) {
            limitTicks = 0;
            limitStrict = !limitStrict;
        }

        if (jitterTicks > 7) {
            jitterTicks = 0;
        }
    }

    static public void onEnable(PluginModule module, boolean bounds, Mode packetMode) {
        // System.out.println("onEnable");
        if (mc.player == null || mc.world == null) {
            module.disable();
            return;
        }
        packets.clear();
        posLooks.clear();
        teleportId = 0;
        verticalDelay = 0;
        horizontalDelay = 0;
        antiKickTicks = 0;
        limitTicks = 0;
        jitterTicks = 0;
        speedX = 0;
        speedY = 0;
        speedZ = 0;
        oddJitter = false;
        startingOutOfBoundsPos = null;
        startingOutOfBoundsPos = new CPacketPlayer.Position(randomHorizontal(bounds, packetMode), 1, randomHorizontal(bounds, packetMode), mc.player.onGround);
        packets.add(startingOutOfBoundsPos);
        mc.player.connection.sendPacket(startingOutOfBoundsPos);
    }

    static public void onDisable(PluginModule module) {
        // System.out.println("onDisable");

        if (mc.player != null) {
            mc.player.setVelocity(0, 0, 0);
        }
//        TimerManager.INSTANCE.modifyTimer(module, 1f);
        modifyTimer(1f);
    }

    static public void onReceive(PacketEvent.Receive event, Type type) {

        if (fullNullCheck()) {
            return;
        }

        if (event.getPacket() instanceof SPacketPlayerPosLook) {
            if (!(mc.currentScreen instanceof GuiDownloadTerrain)) {
                // System.out.println("onReceive");
                SPacketPlayerPosLook packet = (SPacketPlayerPosLook) event.getPacket();
                if (mc.player.isEntityAlive()) {
                    if (teleportId <= 0) {
                        teleportId = ((SPacketPlayerPosLook) event.getPacket()).getTeleportId();
                    } else {
                        if (mc.world.isBlockLoaded(new BlockPos(mc.player.posX, mc.player.posY, mc.player.posZ), false) &&
                            type != Type.SETBACK) { // PHASING?
                            if (type == Type.DESYNC) {
                                posLooks.remove(packet.getTeleportId());
                                event.cancel();
                                if (type == Type.SLOW) {
                                    mc.player.setPosition(packet.getX(), packet.getY(), packet.getZ());
                                }
                                return;
                            } else if (posLooks.containsKey(packet.getTeleportId())) {
                                TimeVec3d vec = posLooks.get(packet.getTeleportId());
                                if (vec.x == packet.getX() && vec.y == packet.getY() && vec.z == packet.getZ()) {
                                    posLooks.remove(packet.getTeleportId());
                                    event.cancel();
                                    if (type == Type.SLOW) {
                                        mc.player.setPosition(packet.getX(), packet.getY(), packet.getZ());
                                    }
                                    return;
                                }
                            }
                        }
                    }
                }
                ((AccessorSPacketPosLook) packet).setYaw(mc.player.rotationYaw);
                ((AccessorSPacketPosLook) packet).setPitch(mc.player.rotationPitch);
                packet.getFlags().remove(SPacketPlayerPosLook.EnumFlags.X_ROT);
                packet.getFlags().remove(SPacketPlayerPosLook.EnumFlags.Y_ROT);
                teleportId = packet.getTeleportId();
            } else {
                teleportId = 0;
            }
        }

    }

    static public void onMove(PlayerMoveEvent event, Type type, Phase phase) {
        if (type != Type.SETBACK && teleportId <= 0) {
            return;
        }
        // System.out.println("onMove");

        if (type != Type.SLOW) {
            event.setX(speedX);
            event.setY(speedY);
            event.setZ(speedZ);
        }

        if (phase == Phase.VANILLA || checkCollisionBox()) {
            mc.player.noClip = true;
        }
    }

    static public void onSend(PacketEvent.Send event, boolean cancelCPacketPlayer) {

        if (cancelCPacketPlayer)
            if (event.getPacket() instanceof CPacketPlayer && !(event.getPacket() instanceof CPacketPlayer.Position)) {
                // System.out.println("onSend");
                event.cancel();
            }
        if (event.getPacket() instanceof CPacketPlayer) {
            // System.out.println("onSend");
            CPacketPlayer packet = (CPacketPlayer) event.getPacket();
            if (packets.contains(packet)) {
                packets.remove(packet);
                return;
            }
            event.cancel();
        }
    }

    static public void onPush(PushEvent event) {
        // System.out.println("onPush");
        event.setCanceled(true);
    }

    static public void onPushOutOfBlocks(PushOutOfBlocksEvent event) {
        // System.out.println("onPushOutOfBlocks");
        event.cancel();
    }


    static private boolean fullNullCheck() {
        return mc.player == null || mc.world == null;
    }

    static private boolean checkCollisionBox() {
        if (!mc.world.getCollisionBoxes(mc.player, mc.player.getEntityBoundingBox().expand(0.0, 0.0, 0.0)).isEmpty()) {
            return true;
        }
        return !mc.world.getCollisionBoxes(mc.player, mc.player.getEntityBoundingBox().offset(0.0, 2.0, 0.0).contract(0.0, 1.99, 0.0)).isEmpty();
    }

    static private void cleanPosLooks() {
        posLooks.forEach((tp, timeVec3d) -> {
            if (System.currentTimeMillis() - timeVec3d.getTime() > TimeUnit.SECONDS.toMillis(30L)) {
                posLooks.remove(tp);
            }
        });
    }

    static private double randomHorizontal(boolean bounds, Mode packetMode) {
        int randomValue = random.nextInt(bounds ? 80 : (packetMode == Mode.OBSCURE ? (mc.player.ticksExisted % 2 == 0 ? 480 : 100) : 29000000)) + (bounds ? 5 : 500);
        if (random.nextBoolean()) {
            return randomValue;
        }
        return -randomValue;
    }

    static private double randomLimitedVertical() {
        int randomValue = random.nextInt(22);
        randomValue += 70;
        if (random.nextBoolean()) {
            return randomValue;
        }
        return -randomValue;
    }

    static private double randomLimitedHorizontal() {
        int randomValue = random.nextInt(10);
        if (random.nextBoolean()) {
            return randomValue;
        }
        return -randomValue;
    }

    static private void sendPackets(double x, double y, double z, Mode mode, boolean sendConfirmTeleport, boolean prevCT, boolean nextCT, Limit limit, boolean _bounds, boolean strict, boolean sendOutOfBounds, boolean duplicateNormalPacket) {
        Vec3d nextPos = new Vec3d(mc.player.posX + x, mc.player.posY + y, mc.player.posZ + z);
        Vec3d bounds = getBoundsVec(x, y, z, mode, _bounds, strict);

        CPacketPlayer nextPosPacket = new CPacketPlayer.Position(nextPos.x, nextPos.y, nextPos.z, mc.player.onGround);

        packets.add(nextPosPacket);
        mc.player.connection.sendPacket(nextPosPacket);
        if (duplicateNormalPacket) {
            packets.add(nextPosPacket);
            mc.player.connection.sendPacket(nextPosPacket);
        }

        if (limit != Limit.NONE && limitTicks == 0) return;

        if (sendOutOfBounds) {
            CPacketPlayer boundsPacket = new CPacketPlayer.Position(bounds.x, bounds.y, bounds.z, mc.player.onGround);
            packets.add(boundsPacket);
            mc.player.connection.sendPacket(boundsPacket);
        }


        if (sendConfirmTeleport) {
            teleportId++;

            if (prevCT) {
                mc.player.connection.sendPacket(new CPacketConfirmTeleport(teleportId - 1));
            }

            mc.player.connection.sendPacket(new CPacketConfirmTeleport(teleportId));

            posLooks.put(teleportId, new TimeVec3d(nextPos.x, nextPos.y, nextPos.z, System.currentTimeMillis()));

            if (nextCT) {
                mc.player.connection.sendPacket(new CPacketConfirmTeleport(teleportId + 1));
            }
        }
    }

    static private Vec3d getBoundsVec(double x, double y, double z, Mode mode, boolean bounds, boolean strict) {
        switch (mode) {
            case UP:
                return new Vec3d(mc.player.posX + x, bounds ? (strict ? 255 : 256) : mc.player.posY + 420, mc.player.posZ + z);
            case PRESERVE:
                return new Vec3d(bounds ? mc.player.posX + randomHorizontal(bounds, mode) : randomHorizontal(bounds, mode), strict ? (Math.max(mc.player.posY, 2D)) : mc.player.posY, bounds ? mc.player.posZ + randomHorizontal(bounds, mode) : randomHorizontal(bounds, mode));
            case LIMITJITTER:
                return new Vec3d(mc.player.posX + (strict ? x : randomLimitedHorizontal()), mc.player.posY + randomLimitedVertical(), mc.player.posZ + (strict ? z : randomLimitedHorizontal()));
            case BYPASS:
                if (bounds) {
                    double rawY = y * 510;
                    return new Vec3d(mc.player.posX + x, mc.player.posY + ((rawY > ((mc.player.dimension == -1) ? 127 : 255)) ? -rawY : (rawY < 1) ? -rawY : rawY), mc.player.posZ + z);
                } else {
                    return new Vec3d(mc.player.posX + (x == 0D ? (random.nextBoolean() ? -10 : 10) : x * 38), mc.player.posY + y, mc.player.posX + (z == 0D ? (random.nextBoolean() ? -10 : 10) : z * 38));
                }
            case OBSCURE:
                return new Vec3d(mc.player.posX + randomHorizontal(bounds, mode), Math.max(1.5D, Math.min(mc.player.posY + y, 253.5D)), mc.player.posZ + randomHorizontal(bounds, mode));
            default: // case DOWN
                return new Vec3d(mc.player.posX + x, bounds ? (strict ? 1 : 0) : mc.player.posY - 1337, mc.player.posZ + z);
        }
    }
}