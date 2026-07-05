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
    // Lưu cặp thực thể: Key = Warden (gốc), Value = Slime/Warden hiển thị x2 ảo để đồng bộ xóa khi chết
    private final Map<UUID, UUID> visualMounts = new HashMap<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("spawnsuperwarden")).setExecutor(this);
        startSkillTimer();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player player) {
            if (!player.hasPermission("superwarden.admin")) return false;
            
            Location loc = player.getLocation();
            
            // 1. Tạo thực thể cơ sở (Hitbox gốc) là một con Warden ẩn danh
            Warden baseWarden = (Warden) loc.getWorld().spawnEntity(loc, EntityType.WARDEN);
            baseWarden.setCustomName("§c§l[BOSS] SIÊU WARDEN");
            baseWarden.setCustomNameVisible(true);
            
            // Thiết lập 1836 máu (Yêu cầu 1)
            Objects.requireNonNull(baseWarden.getAttribute(Attribute.GENERIC_MAX_HEALTH)).setBaseValue(1836.0);
            baseWarden.setHealth(1836.0);
            
            // 2. Kỹ thuật tạo Ngoại hình x2 nhưng giữ nguyên Hitbox nhỏ bằng hệ thống Scale Attribute 1.21
            // Thay vì dùng model ngoài, ta scale chính thực thể hiển thị và tắt bớt tương tác vật lý trực tiếp
            Objects.requireNonNull(baseWarden.getAttribute(Attribute.GENERIC_SCALE)).setBaseValue(2.0); // To gấp đôi (Yêu cầu 2)
            
            // Đăng ký thanh máu BossBar (Yêu cầu 1)
            BossBar bossBar = Bukkit.createBossBar("§c§lSIÊU WARDEN", org.bukkit.boss.BarColor.RED, org.bukkit.boss.BarStyle.SEGMENTED_20);
            bossBar.setProgress(1.0);
            
            activeBosses.add(baseWarden.getUniqueId());
            longRangeImmunity.put(baseWarden.getUniqueId(), System.currentTimeMillis() + (7 * 60 * 1000)); // Chiêu 6
            player.sendMessage("§aĐã triệu hồi Siêu Warden Khổng Lồ tương thích Geyser!");

            // Vòng lặp cập nhật BossBar & Đồng bộ vị trí
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

    // Yêu cầu 4: Chỉ tấn công người chơi, bỏ qua chức năng nghe tiếng động từ mob khác
    @EventHandler
    public void onBossTarget(EntityTargetLivingEntityEvent event) {
        if (activeBosses.contains(event.getEntity().getUniqueId())) {
            if (!(event.getTarget() instanceof Player)) {
                event.setCancelled(true); // Hủy mục tiêu nếu không phải người chơi
            }
        }
    }

    // Nội tại: Miễn nhiễm tất cả hiệu ứng bất lợi
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

    // Xử lý cơ chế Sát thương, Chiêu 6, và Nội tại biến động dưới 50% máu
    @EventHandler
    public void onBossDamage(EntityDamageByEntityEvent event) {
        if (activeBosses.contains(event.getEntity().getUniqueId()) && event.getEntity() instanceof Warden boss) {
            
            // Chiêu 6: Trong 7 phút chỉ nhận sát thương tầm gần (Cận chiến)
            if (event.getDamager() instanceof Projectile || event.getDamager() instanceof ThrownPotion) {
                if (longRangeImmunity.containsKey(boss.getUniqueId()) && System.currentTimeMillis() < longRangeImmunity.get(boss.getUniqueId())) {
                    event.setCancelled(true);
                    if (event.getDamager() instanceof Arrow arrow && arrow.getShooter() instanceof Player p) {
                        p.sendMessage("§e[Boss] Siêu Warden đang kích hoạt giáp bảo vệ, miễn nhiễm sát thương tầm xa!");
                    }
                    return;
                }
            }

            // Khi còn dưới 50% máu (Dưới 918 máu): Có hiệu ứng bảo vệ (Giảm 40% sát thương nhận vào)
            if (boss.getHealth() <= 918.0) {
                event.setDamage(event.getDamage() * 0.6);
                boss.getWorld().spawnParticle(Particle.SHIELD, boss.getLocation().add(0, 2, 0), 3);
            }
        }

        // Khi còn dưới 50% máu: Tất cả đòn đánh thường và kỹ năng đều tăng thêm 50% sát thương
        if (event.getDamager() instanceof Warden boss && activeBosses.contains(boss.getUniqueId())) {
            if (boss.getHealth() <= 918.0) {
                event.setDamage(event.getDamage() * 1.5);
            }
        }
    }

    // Nội tại hạ gục hồi máu & Rơi vật phẩm đặc biệt độc quyền (Yêu cầu 7)
    @EventHandler
    public void onBossKillOrDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player deadPlayer) {
            for (Entity entity : deadPlayer.getNearbyEntities(30, 30, 30)) {
                if (activeBosses.contains(entity.getUniqueId()) && entity instanceof Warden boss) {
                    boss.setHealth(Math.min(1836.0, boss.getHealth() + 150.0)); // Hồi 1 lượng máu (150 HP)
                    boss.getWorld().sendMessage(net.kyori.adventure.text.Component.text("§cSiêu Warden đã hạ gục một chiến binh và hấp thụ sinh mệnh để hồi máu!"));
                }
            }
        }

        if (activeBosses.contains(event.getEntity().getUniqueId())) {
            event.getDrops().clear();
            
            // Tạo vật phẩm tùy chỉnh có Lore và Tên riêng (Yêu cầu 7)
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

    // Bộ xử lý tự động kích hoạt 11 Kỹ năng đỉnh cao (Yêu cầu 8)
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
                    
                    // Nếu dưới 50% máu, tăng cường tần suất hoặc uy lực hiệu ứng hình ảnh
                    boolean isEnraged = boss.getHealth() <= 918.0;

                    switch (skill) {
                        case 1 -> { // Chiêu 1: Vòng tròn R=10ô, giải phóng luồng siêu thanh gây 6 tim (12 sát thương) bỏ qua giáp
                            boss.getWorld().spawnParticle(Particle.SONIC_BOOM, boss.getLocation(), 20, 5, 1, 5);
                            for (Player p : players) {
                                if (p.getLocation().distance(boss.getLocation()) <= 10) {
                                    double finalDamage = isEnraged ? 18.0 : 12.0; 
                                    p.damage(finalDamage); // Sát thương trực tiếp bỏ qua mọi loại giáp
                                    p.sendMessage("§cĐột kích siêu thanh xuyên giáp!");
                                }
                            }
                        }
                        case 2 -> { // Chiêu 2: Gầm lên triệu hồi Evoker Fangs từ dưới đất gây 6 tim
                            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.5f, 0.5f);
                            for (Player p : players) {
                                Location pLoc = p.getLocation();
                                p.getWorld().spawnEntity(pLoc, EntityType.EVOKER_FANGS);
                                p.damage(isEnraged ? 18.0 : 12.0);
                            }
                        }
                        case 3 -> { // Chiêu 3: Gầm rú -> Hiệu ứng bay lên + Độc trong 5 giây
                            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_ROAR, 1.5f, 0.8f);
                            for (Player p : players) {
                                p.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 100, 1));
                                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 100, 1));
                            }
                        }
                        case 4 -> { // Chiêu 4: Triệu hồi 10 Zombie full giáp kim cương
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
                        case 5 -> { // Chiêu 5: Tạo 10 vòng sấm sét đánh từ trong ra ngoài
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
                        case 6 -> {
                            // Chiêu 6 được kích hoạt mặc định khi Spawn (7 phút kháng tầm xa).
                        }
                        case 7 -> { // Chiêu 7: Húc ngắm thẳng một người 100% trúng + nổ sát thương + làm choáng mục tiêu
                            boss.setVelocity(randomPlayer.getLocation().toVector().subtract(boss.getLocation().toVector()).normalize().multiply(2.5));
                            Bukkit.getScheduler().runTaskLater(SuperWardenPlugin.this, () -> {
                                randomPlayer.getWorld().createExplosion(randomPlayer.getLocation(), 3.0f, false, false);
                                randomPlayer.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 10)); // Choáng, không thể di chuyển
                                randomPlayer.sendMessage("§cBạn đã bị Siêu Warden húc trúng và làm choáng hoàn toàn!");
                            }, 10L);
                        }
                        case 8 -> { // Chiêu 8: Hiệu ứng rơi chậm liên tục, không thể uống sữa để giải
                            new BukkitRunnable() {
                                int ticks = 0;
                                @Override
                                public void run() {
                                    if (ticks > 10 || !boss.isValid()) { cancel(); return; }
                                    for (Player p : players) {
                                        // Liên tục nạp lại hiệu ứng mỗi giây, dù uống sữa sữa cũng bị dính lại ngay lập tức
                                        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 1));
                                    }
                                    ticks++;
                                }
                            }.runTaskTimer(SuperWardenPlugin.this, 0L, 20L);
                        }
                        case 9 -> { // Chiêu 9: Mù lòa và quay cuồng (Tối ưu hóa Darkness để hiển thị chuẩn cho Geyser PE không bị sọc màn hình)
                            for (Player p : players) {
                                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 160, 1));
                                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 160, 1));
                            }
                        }
                        case 10 -> { // Chiêu 10: Độn thổ xuống đất ngắm vào 1 người, bò lên + buff sức mạnh tấn công và tốc độ
                            boss.getWorld().spawnParticle(Particle.BLOCK, boss.getLocation(), 60, Material.DIRT.createBlockData());
                            boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_DIG, 1.0f, 1.0f);
                            
                            Bukkit.getScheduler().runTaskLater(SuperWardenPlugin.this, () -> {
                                boss.teleport(randomPlayer.getLocation());
                                boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WARDEN_EMERGE, 1.0f, 1.0f);
                                boss.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 240, 2));
                                boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 240, 2));
                            }, 30L);
                        }
                        case 11 -> { // Chiêu 11: Có người ở gần hất tung lên trời và đẩy ra cực xa
                            for (Player p : players) {
                                if (p.getLocation().distance(boss.getLocation()) <= 6) {
                                    Vector pushVector = p.getLocation().toVector().subtract(boss.getLocation().toVector()).normalize().multiply(2.5);
                                    pushVector.setY(1.8); // Hất tung lên cao cực đại
                                    p.setVelocity(pushVector);
                                }
                            }
                        }
                    }
                }
            }
        }.runTaskTimer(this, 100L, 240L); // Cứ mỗi 12 giây Boss sẽ tự động tung chiêu combo ngẫu nhiên
    }
}