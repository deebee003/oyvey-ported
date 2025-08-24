package me.alpha432.oyvey.features.modules.combat;

import com.google.common.eventbus.Subscribe;
import me.alpha432.oyvey.event.impl.PacketEvent;
import me.alpha432.oyvey.event.impl.Render2DEvent;
import me.alpha432.oyvey.features.modules.Module;
import me.alpha432.oyvey.features.settings.Setting;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class KillAura extends Module {
    private final Setting<Double> range = register(new Setting<>("Range", 4.5, 0.1, 6.0));
    private final Setting<Boolean> silentAim = register(new Setting<>("SilentAim", true));
    private final Setting<Boolean> blockHit = register(new Setting<>("BlockHit", true));
    private final Setting<Boolean> criticals = register(new Setting<>("Criticals", true));
    private final Setting<Boolean> playersOnly = register(new Setting<>("PlayersOnly", true));
    private final Setting<Integer> attackSpeed = register(new Setting<>("AttackSpeed", 10, 1, 20));
    private final Setting<Boolean> rotate = register(new Setting<>("Rotate", true));
    private final Setting<Boolean> swingArm = register(new Setting<>("SwingArm", true));
    private final Setting<Boolean> renderBox = register(new Setting<>("RenderBox", true));
    private final Setting<Integer> boxColor = register(new Setting<>("BoxColor", 0xFFFF0000, 0x00000000, 0xFFFFFFFF));
    
    private LivingEntity target;
    private int attackCooldown = 0;
    private float[] serverRotations = new float[2];

    public KillAura() {
        super("KillAura", "Automatically attacks nearby entities", Category.COMBAT, true, false, false);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        
        // Update attack cooldown
        if (attackCooldown > 0) {
            attackCooldown--;
        }
        
        // Find target
        target = findTarget();
        
        // Attack if cooldown is ready and we have a target
        if (target != null && attackCooldown <= 0) {
            // Calculate rotations for silent aim
            if (silentAim.getValue()) {
                serverRotations = calculateRotations(target);
                
                // Send rotation packets to server so others see you looking at target
                if (rotate.getValue()) {
                    mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                        serverRotations[0], serverRotations[1], mc.player.isOnGround(), false));
                }
            }
            
            attack(target);
            attackCooldown = 20 / attackSpeed.getValue();
        }
    }

    @Subscribe
    private void onPacketSend(PacketEvent.Send event) {
        if (event.getPacket() instanceof PlayerInteractEntityC2SPacket packet && packet.type.getType() == PlayerInteractEntityC2SPacket.InteractType.ATTACK) {
            Entity entity = mc.world.getEntityById(packet.entityId);
            if (entity == null
                    || entity instanceof EndCrystalEntity
                    || !mc.player.isOnGround()
                    || !(entity instanceof LivingEntity)) return;

            // Handle criticals if enabled
            if (criticals.getValue()) {
                boolean bl = mc.player.horizontalCollision;
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), mc.player.getY() + 0.1f, mc.player.getZ(), false, bl));
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), mc.player.getY(), mc.player.getZ(), false, bl));
                mc.player.addCritParticles(entity);
            }
            
            // Handle block hit if enabled
            if (blockHit.getValue()) {
                swingArmClientServer();
            }
        }
    }

    @Override
    public void onRender2D(Render2DEvent event) {
        if (mc.player == null || mc.world == null || !renderBox.getValue() || target == null) return;
        
        render2DBox(target);
    }

    private void swingArmClientServer() {
        // Swing arm on client and server for maximum detection prevention
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.player.networkHandler.sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    private void render2DBox(LivingEntity entity) {
        // Placeholder - check how other modules do 2D rendering in this client
        // Look at TargetHUD or other modules for examples
    }

    private float[] calculateRotations(LivingEntity entity) {
        Vec3d eyesPos = new Vec3d(mc.player.getX(), mc.player.getY() + mc.player.getEyeHeight(mc.player.getPose()), mc.player.getZ());
        Vec3d targetPos = entity.getPos().add(0, entity.getHeight() / 2, 0);
        
        double diffX = targetPos.x - eyesPos.x;
        double diffY = targetPos.y - eyesPos.y;
        double diffZ = targetPos.z - eyesPos.z;
        
        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
        
        float yaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90F;
        float pitch = (float) -Math.toDegrees(Math.atan2(diffY, diffXZ));
        
        return new float[]{
            mc.player.getYaw() + MathHelper.wrapDegrees(yaw - mc.player.getYaw()),
            mc.player.getPitch() + MathHelper.wrapDegrees(pitch - mc.player.getPitch())
        };
    }

    private LivingEntity findTarget() {
        // Get all entities in range
        List<Entity> entities = mc.world.getEntities();
        return (LivingEntity) entities.stream()
                .filter(entity -> entity instanceof LivingEntity)
                .filter(entity -> entity != mc.player)
                .filter(entity -> !(entity instanceof EndCrystalEntity))
                .filter(entity -> !playersOnly.getValue() || entity instanceof PlayerEntity)
                .filter(entity -> entity.isAlive())
                .filter(entity -> mc.player.distanceTo(entity) <= range.getValue())
                .sorted(Comparator.comparingDouble(entity -> mc.player.distanceTo(entity)))
                .findFirst()
                .orElse(null);
    }

    private void attack(LivingEntity target) {
        if (silentAim.getValue()) {
            // Silent aim - send attack packet with server-side rotations
            PlayerInteractEntityC2SPacket attackPacket = PlayerInteractEntityC2SPacket.attack(target, mc.player.isSneaking());
            mc.player.networkHandler.sendPacket(attackPacket);
            
            // Swing arm for visual feedback and detection prevention
            if (swingArm.getValue()) {
                swingArmClientServer();
            }
            
            // Add criticals if enabled
            if (criticals.getValue() && mc.player.isOnGround()) {
                boolean bl = mc.player.horizontalCollision;
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), mc.player.getY() + 0.1f, mc.player.getZ(), false, bl));
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(mc.player.getX(), mc.player.getY(), mc.player.getZ(), false, bl));
            }
        } else {
            // Regular aim - rotate player to target and attack
            mc.player.lookAt(net.minecraft.entity.EntityAnchorArgumentType.EntityAnchor.EYES, target.getPos().add(0, target.getHeight() / 2, 0));
            mc.interactionManager.attackEntity(mc.player, target);
            
            // Swing arm for visual feedback
            if (swingArm.getValue()) {
                swingArmClientServer();
            }
        }
    }

    @Override
    public void onDisable() {
        target = null;
    }

    @Override
    public String getDisplayInfo() {
        return target != null ? target.getName().getString() : "None";
    }
}
