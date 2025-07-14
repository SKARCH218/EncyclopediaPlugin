package lightstudio.encyclopedia

import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerFishEvent
import java.util.Random

class RelicAcquisitionManager(private val plugin: EncyclopediaPlugin) : Listener {

    private val random = Random()

    @EventHandler
    fun onBlockBreak(e: BlockBreakEvent) {
        val player = e.player
        val type = e.block.type
        if (type !in listOf(Material.SAND, Material.GRAVEL, Material.MUD, Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL)) return

        // 의심스러운 블록은 100% 확률로 유물 획득
        val chance = if (type in listOf(Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL)) 100 else random.nextInt(100) + 1
        tryGiveRelic(player, chance)
    }

    @EventHandler
    fun onFish(e: PlayerFishEvent) {
        if (e.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = e.player
        val chance = random.nextInt(100) + 1 // 낚시는 1% 확률
        if (chance <= 5) { // 5% 확률로 유물 획득 시도
             tryGiveRelic(player, random.nextInt(100) + 1)
        }
    }

    @EventHandler
    fun onEntityDeath(e: EntityDeathEvent) {
        val killer = e.entity.killer ?: return
        val entityType = e.entityType

        val chance = when (entityType) {
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.CREEPER -> 5 // 흔한 몹: 5% 
            EntityType.ENDERMAN, EntityType.WITCH -> 10 // 희귀 몹: 10%
            EntityType.WITHER, EntityType.ENDER_DRAGON -> 100 // 보스: 100%
            else -> return
        }

        if (random.nextInt(100) + 1 <= chance) {
            tryGiveRelic(killer, random.nextInt(100) + 1)
        }
    }

    private fun tryGiveRelic(player: Player, probability: Int) {
        val gradeRange = when {
            probability <= 40 -> 40
            probability <= 70 -> 80
            probability <= 90 -> 120
            else -> return
        }
        val id = (1..gradeRange).random()
        plugin.giveRelic(player, id)
    }
}
