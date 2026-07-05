package com.yourname.superwarden;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class SuperWardenPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, Long> longRangeImmunity = new HashMap<>();
    private final Set<UUID> activeBosses = new HashSet<>();
    private final Map<UUID, Long> lastDashTime = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("spawnsuperwarden")).setExecutor(this);
        startSkillTimer();
        startPvPAITicker(); // Kích hoạt bộ não PvP thông minh cho Boss
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player player) {
            if (!player.hasPermission("superwarden.admin")) return false;
            
            Location loc = player.getLocation();
            Warden baseWarden = (Warden) loc.getWorld().spawnEntity(loc, EntityType.WARDEN);
            baseWarden.setCustomName("§c§l[BOSS] SIÊU WARDEN");
            baseWarden.setCustomNameVisible(true);
            
            Objects.requireNonNull(baseWarden.getAttribute(Attribute.GENERIC_MAX_HEALTH)).setBaseValue(1836.0);
            baseWarden.setHealth(1836.0);
            
            // Tận dụng thuộc tính Scale 1.21+ để phóng to hình ảnh x2, Geyser PE hỗ trợ cực chuẩn
            Objects.requireNonNull(baseWarden.getAttribute(Attribute.GENERIC_SCALE)).setBaseValue(2.0);
            
            BossBar bossBar = Bukkit.createBossBar("§c§lSIÊU WARDEN", org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SEGMENTED_20);
            bossBar.setProgress(1.0);
            
            activeBosses.add(baseWarden.getUniqueId());
            longRangeImmunity.put(baseWarden.getUniqueId(), System.currentTimeMillis() + (7 * 60 * 1000));
            player.sendMessage("§aĐã triệu hồi Siêu Warden Khổng Lồ với Trí Tuệ Nhân Tạo PvP!");

            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!baseWarden.isValid()) {
                        bossBar.removeAll();
                        activeBosses.remove(baseWarden.getUniqueId());
                        cancel();
                        return;
                    }
                    bossBar.setProgress(baseWarden.getHealth() / 1836.0);
                    bossBar.setTitle("§c§lSIÊU WARDEN §7[ Máu: " + (int)baseWarden.getHealth() + " / 1836 ]");
                    
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getWorld().equals(baseWarden.getWorld()) && p.getLocation().distance(baseWarden.getLocation()) < 40) {
                            bossBar.addPlayer(p);
                        } else {
                            bossBar.removePlayer(p);
                        }
                    }
                }
            }.runTaskTimer(this, 0L, 10L);
        }
        return true;
    }

    /**
     * BỘ NÃO PVP CHÍNH (Chạy liên tục mỗi 5 Ticks = 0.25 giây để tính toán bước đi thông minh)
     */
    private void startPvPAITicker() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (UUID uuid : activeBosses) {
                    Warden boss = (Warden) Bukkit.getEntity(uuid);
                    if (boss == null || !boss.isValid()) continue;

                    LivingEntity target = boss.getTarget();
                    if (!(target instanceof Player player)) continue;

                    double distance = boss.getLocation().distance(player.getLocation());
                    Location bossLoc = boss.getLocation();
                    Location targetLoc = player.getLocation();

                    // HƯỚNG DI CHUYỂN CƠ BẢN
                    Vector toTarget = targetLoc.toVector().subtract(bossLoc.toVector());
                    Vector direction = toTarget.clone().normalize();

                    // CƠ CHẾ 1: DASH (LƯỚT ÁP SÁT BẤT NGỜ)
                    // Nếu người chơi đứng quá xa (từ 8 đến 20 ô) và đang bắn cung hoặc hồi máu, Boss sẽ lướt thẳng tới
                    if (distance > 8 && distance < 20) {
                        long now = System.currentTimeMillis();
                        if (now - lastDashTime.getOrDefault(boss.getUniqueId(), 0L) > 5000) { // Cooldown lướt là 5 giây
                            boss.setVelocity(direction.multiply(1.5).setY(0.2)); // Tạo lực đẩy lướt mượt
                            boss.getWorld().spawnParticle(Particle.SONIC_BOOM, bossLoc, 5, 0.5, 0.5, 0.5);
                            boss.getWorld().playSound(bossLoc, Sound.ENTITY_WARDEN_HEARTBEAT, 1.5f, 1.5f);
                            lastDashTime.put(boss.getUniqueId(), now);
                            continue;
                        }
                    }

                    // CƠ CHẾ 2: STRAFING (DI CHUYỂN DI CHÉO / VÒNG TRÒN ĐỂ NÉ ĐÒN)
                    // Khi ở khoảng cách cận chiến (3 - 7 ô), thay vì chạy thẳng, boss sẽ đi dạt sang một bên (Left/Right)
                    if (distance >= 3 && distance <= 7) {
                        Vector leftRightStrafe = new Vector(-direction.getZ(), 0, direction.getX()); // Tính toán Vector vuông góc
                        if (new Random().nextBoolean()) {
                            leftRightStrafe.multiply(-1); // Đổi hướng ngẫu nhiên trái/phải để đánh lừa người chơi
                        }
                        
                        // Ép thực thể di chuyển đến vị trí đã tính toán lệch góc
                        Location strafeTarget = bossLoc.add(direction.multiply(0.5)).add(leftRightStrafe.multiply(1.2));
                        boss.getPathfinder().moveTo(strafeTarget, 1.3); // Tốc độ di chuyển khôn khéo hơn
                    }

                    // CƠ CHẾ 3: BACKSTEP (THỦ THẾ LÙI LẠI)
                    // Nếu Boss đang thấp máu (<30%) hoặc người chơi vừa đánh trúng đòn đau, nó sẽ chủ động lùi lại 2-3 bước để hồi bộ đếm kỹ năng
                    if (boss.getHealth() < 550 && distance < 4) {
                        Location backstepTarget = bossLoc.subtract(direction.multiply(2.0));
                        boss.getPathfinder().moveTo(backstepTarget, 1.4);
                        boss.getWorld().spawnParticle(Particle.CLOUD, bossLoc, 3, 0.2, 0.1, 0.2);
                    }
                }
            }
        }.runTaskTimer(this, 0L, 5L); // Chạy 4 lần trong 1 giây để đảm bảo phản xạ PvP cực nhanh
    }

    @EventHandler
    public void onBossTarget(EntityTargetLivingEntityEvent event) {
        if (activeBosses.contains(event.getEntity().getUniqueId())) {
            if (!(event.getTarget() instanceof Player)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPotionApply(EntityPotionEffectEvent event) {
        if (activeBosses.contains(event.getEntity().getUniqueId())) {
            PotionEffectType type = event.getModifiedType();
            if (type == PotionEffectType.POISON || type == PotionEffectType.WITHER || 
                type == PotionEffectType.SLOWNESS || type == PotionEffectType.WEAKNESS || 
                type == PotionEffectType.BLINDNESS) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBossDamage(EntityDamageByEntityEvent event) {
        if (activeBosses.contains(event.getEntity().getUniqueId()) && event.getEntity() instanceof Warden boss) {
            
            if (event.getDamager() instanceof Projectile || event.getDamager() instanceof ThrownPotion) {
                if (longRangeImmunity.containsKey(boss.getUniqueId()) && System.currentTimeMillis() < longRangeImmunity.get(boss.getUniqueId())) {
                    event.setCancelled(true);
                    if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player p) {
                        p.sendMessage("§e[Boss] Siêu Warden đang kích hoạt giáp bảo vệ, miễn nhiễm sát thương tầm xa!");
                    }
                    return;
                }
            }

            // Nội tại dưới 50% máu: Giảm 40% sát thương nhận vào + Hiệu ứng luồng gió GUST (Sửa lỗi hạt SHIELD)
            if (boss.getHealth() <= 918.0) {
                event.setDamage(event.getDamage() * 0.6);
                boss.getWorld().spawnParticle(Particle.GUST, boss.getLocation().add(0, 1, 0), 3); 
            }
        }

        if (event.getDamager() instanceof Warden boss && activeBosses.contains(boss.getUniqueId())) {
            if (boss.getHealth() <= 918.0) {
                event.setDamage(event.getDamage() * 1.5);
            }
        }
    }

    @EventHandler
    public void onBossKillOrDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player deadPlayer) {
            for (Entity entity : deadPlayer.getNearbyEntities(30, 30, 30)) {
                if (activeBosses.contains(entity.getUniqueId()) && entity instanceof Warden boss) {
                    boss.setHealth(Math.min(1836.0, boss.getHealth() + 150.0));
                    boss.getWorld().sendMessage(net.kyori.adventure.text.Component.text("§cSiêu Warden đã hạ gục một chiến binh và hấp thụ sinh mệnh để hồi máu!"));
                }
            }
        }

        if (activeBosses.contains(event.getEntity().getUniqueId())) {
            event.getDrops().clear();
            
            ItemStack customItem = new ItemStack(Material.NETHER_STAR);
            ItemMeta meta = customItem.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§d§lTrái Tim Sâu Thẳm Cổ Đại");
                meta.setLore(Arrays.asList(
                    "§7Vật phẩm độc quyền rơi ra từ Siêu Warden Thần Thoại.",
                    "§7Chứa đựng tần số âm thanh hỗn mang.",
                    "",
                    "§e[Tính năng]: Có thể dùng để ghép vào bất kỳ món đồ hiếm nào."
                ));
                customItem.setItemMeta(meta);
            }
            event.getDrops().add(customItem);
            activeBosses.remove(event.getEntity().getUniqueId());
        }
    }

    private void startSkillTimer() {
        new BukkitRunnable() {
            final Random random = new Random();
            @Override
            public void run() {
                for (UUID uuid : activeBosses) {
                    Warden boss = (Warden) Bukkit.getEntity(uuid);
                    if (boss == null || !boss.isValid()) continue;

                    List<Player> players = new ArrayList<>();
                    for (Entity e : boss.getNearbyEntities(25, 25, 25)) {
                        if (e instanceof Player p) players.add(p);
                    }
                    if (players.isEmpty()) continue;
                    Player randomPlayer = players.get(random.nextInt(players.size()));

                    int skill = random.nextInt(11) + 1;
                    boolean isEnraged = boss.getHealth() <= 918.0;

                    switch (skill) {
                        case 1 -> {
                            boss.getWorld().spawnParticle(Particle.SONIC_BOOM, boss.getLocation(), 20, 5, 1, 5);
                            for (Player p : players) {
                                if (p.getLocation().distance(boss.getLocation()) <= 10) {
                                    p.damage(isEnraged ? 18.0 : 12.0);
                                    p.sendMessage("§cĐột kích siêu thanh xuyên giáp!");
                                }
                            }
                        }
                        case 2 -> {
                            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.5f, 0.5f);
                            for (Player p : players) {
                                p.getWorld().spawnEntity(p.getLocation(), EntityType.EVOKER_FANGS);
                                p.damage(isEnraged ? 18.0 : 12.0);
                            }
                        }
                        case 3 -> {
                            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.5f, 0.8f);
                            for (Player p : players) {
                                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 100, 1));
                                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1));
                            }
                        }
                        case 4 -> {
                            boss.getWorld().spawnParticle(Particle.SOUL, boss.getLocation(), 30);
                            for (int i = 0; i < 10; i++) {
                                Zombie zombie = (Zombie) boss.getWorld().spawnEntity(boss.getLocation().add(random.nextInt(3) - 1, 0, random.nextInt(3) - 1), EntityType.ZOMBIE);
                                Objects.requireNonNull(zombie.getEquipment()).setHelmet(new ItemStack(Material.DIAMOND_HELMET));
                                zombie.getEquipment().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                                zombie.getEquipment().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
                                zombie.getEquipment().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
                                zombie.setTarget(randomPlayer);
                            }
                        }
                        case 5 -> {
                            Location origin = boss.getLocation();
                            for (int r = 2; r <= 15; r += 3) {
                                final int currentRadius = r;
                                Bukkit.getScheduler().runTaskLater(SuperWardenPlugin.this, () -> {
                                    for (Player p : players) {
                                        if (Math.abs(p.getLocation().distance(origin) - currentRadius) < 2) {
                                            p.getWorld().strikeLightningEffect(p.getLocation());
                                            p.damage(isEnraged ? 15.0 : 10.0);
                                        }
                                    }
                                }, (r / 3) * 6L);
                            }
                        }
                        case 7 -> {
                            boss.setVelocity(randomPlayer.getLocation().toVector().subtract(boss.getLocation().toVector()).normalize().multiply(2.5));
                            Bukkit.getScheduler().runTaskLater(SuperWardenPlugin.this, () -> {
                                randomPlayer.getWorld().createExplosion(randomPlayer.getLocation(), 3.0f, false, false);
                                randomPlayer.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 10));
                            }, 10L);
                        }
                        case 8 -> {
                            new BukkitRunnable() {
                                int ticks = 0;
                                @Override
                                public void run() {
                                    if (ticks > 10 || !boss.isValid()) { cancel(); return; }
                                    for (Player p : players) {
                                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 1));
                                    }
                                    ticks++;
                                }
                            }.runTaskTimer(SuperWardenPlugin.this, 0L, 20L);
                        }
                        case 9 -> {
                            for (Player p : players) {
                                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 160, 1));
                                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 160, 1));
                            }
                        }
                        case 10 -> {
                            boss.getWorld().spawnParticle(Particle.BLOCK, boss.getLocation(), 60, Material.DIRT.createBlockData());
                            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_DIG, 1.0f, 1.0f);
                            
                            Bukkit.getScheduler().runTaskLater(SuperWardenPlugin.this, () -> {
                                boss.teleport(randomPlayer.getLocation());
                                boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_EMERGE, 1.0f, 1.0f);
                                boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 240, 2));
                                boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 240, 2));
                            }, 30L);
                        }
                        case 11 -> {
                            for (Player p : players) {
                                if (p.getLocation().distance(boss.getLocation()) <= 6) {
                                    Vector pushVector = p.getLocation().toVector().subtract(boss.getLocation().toVector()).normalize().multiply(2.5);
                                    pushVector.setY(1.8);
                                    p.setVelocity(pushVector);
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 100L, 240L);
    }
}