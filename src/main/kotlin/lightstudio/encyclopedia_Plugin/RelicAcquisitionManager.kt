package lightstudio.encyclopedia

import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerFishEvent
import java.util.Random

class RelicAcquisitionManager(private val plugin: EncyclopediaPlugin) : Listener {

    private val random = Random()

    @EventHandler
    fun onBlockBreak(e: BlockBreakEvent) {
        val player = e.player
        val type = e.block.type
        if (type !in listOf(Material.SAND, Material.GRAVEL, Material.MUD, Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL)) return

        val dropChanceConfig = plugin.config.getInt("acquisition-chances.block_break", 10)
        val actualDropChance = if (type in listOf(Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL)) 100 else dropChanceConfig

        if (random.nextInt(100) + 1 <= actualDropChance) {
            tryGiveRelic(player)
        }
    }

    @EventHandler
    fun onFish(e: PlayerFishEvent) {
        if (e.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = e.player

        val dropChanceConfig = plugin.config.getInt("acquisition-chances.fishing", 5)
        if (random.nextInt(100) + 1 <= dropChanceConfig) {
            tryGiveRelic(player)
        }
    }

    @EventHandler
    fun onEntityDeath(e: EntityDeathEvent) {
        val killer = e.entity.killer ?: return
        val entityType = e.entityType

        val dropChanceConfig = when (entityType) {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER -> plugin.config.getInt("acquisition-chances.mob_kill.common", 5)
            EntityType.ENDERMAN, EntityType.WITCH -> plugin.config.getInt("acquisition-chances.mob_kill.uncommon", 10)
            EntityType.WITHER, EntityType.ENDER_DRAGON -> plugin.config.getInt("acquisition-chances.mob_kill.boss", 100)
            else -> return
        }

        if (random.nextInt(100) + 1 <= dropChanceConfig) {
            tryGiveRelic(killer)
        }
    }

    @EventHandler
    fun onAdvancement(e: PlayerAdvancementDoneEvent) {
        val player = e.player
        val dropChanceConfig = plugin.config.getInt("acquisition-chances.advancement", 10) // config에 추가 필요
        if (random.nextInt(100) + 1 <= dropChanceConfig) {
            tryGiveRelic(player)
        }
    }

    private fun tryGiveRelic(player: Player) {
        val normalChance = plugin.config.getInt("relic-drop-chances.normal", 40)
        val rareChance = plugin.config.getInt("relic-drop-chances.rare", 30)
        val legendaryChance = plugin.config.getInt("relic-drop-chances.legendary", 10)

        val totalChance = normalChance + rareChance + legendaryChance
        if (totalChance <= 0) { // 확률이 0이거나 음수일 경우 기본값 사용 또는 오류 처리
            plugin.logger.warning("Relic drop chances sum to zero or less. Using default chances.")
            plugin.giveRelic(player, (1..plugin.maxId).random())
            return
        }

        val roll = random.nextInt(totalChance) + 1

        val selectedId = when {
            roll <= normalChance -> (1..40).random()
            roll <= normalChance + rareChance -> (41..80).random()
            else -> (81..120).random()
        }
        plugin.giveRelic(player, selectedId)
    }
}