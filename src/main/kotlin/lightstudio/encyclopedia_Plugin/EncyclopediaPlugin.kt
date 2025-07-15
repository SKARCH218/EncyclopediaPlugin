package lightstudio.encyclopedia

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.command.Command
import java.io.File
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.*
import java.util.concurrent.TimeUnit

enum class GradeFilter { ALL, NORMAL, RARE, LEGENDARY }

data class Relic(val id: Int, val name: String, val lore: List<String>, val leftClickCommands: List<String>)
data class RankingEntry(val uuid: UUID, val name: String, val count: Int)

class EncyclopediaPlugin : JavaPlugin(), Listener, TabExecutor {

    val maxId = 120
    private val itemsPerPage = 36
    private val encyclopediaData = mutableMapOf<UUID, MutableSet<Int>>()
    private val cumulativeData = mutableMapOf<UUID, Int>()
    private val relicRegistry = mutableMapOf<Int, Relic>()
    private val playerFilters = mutableMapOf<UUID, GradeFilter>()
    private lateinit var conn: Connection

    private lateinit var itemsConfigFile: File
    private lateinit var itemsConfig: FileConfiguration
    private lateinit var langConfigFile: File
    private lateinit var langConfig: FileConfiguration

    private var rankingCache: List<RankingEntry>? = null
    private var lastCacheTime: Long = 0

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        server.pluginManager.registerEvents(RelicAcquisitionManager(this), this)
        getCommand("도감")?.setExecutor(this)
        getCommand("도감랭킹")?.setExecutor(this)
        getCommand("도감관리")?.setExecutor(this)

        reloadAllConfigs()

        connectDatabase()
        loadPlayerData()
        loadCumulativeData()
    }

    override fun onDisable() {
        savePlayerData()
        saveCumulativeData()
        if (::conn.isInitialized) conn.close()
    }

    private fun reloadAllConfigs() {
        saveDefaultConfig()
        createItemsConfig()
        createLangConfig()
        loadRelics()
        invalidateRankingCache() // 설정 리로드 시 캐시 무효화
    }

    private fun connectDatabase() {
        val dbPath = "jdbc:sqlite:${dataFolder}/encyclopedia.db"
        conn = DriverManager.getConnection(dbPath)
        conn.createStatement().use {
            it.execute("""
                CREATE TABLE IF NOT EXISTS encyclopedia (
                    uuid TEXT NOT NULL,
                    relic_id INTEGER NOT NULL,
                    PRIMARY KEY (uuid, relic_id)
                )
            """)
            it.execute("""
                CREATE TABLE IF NOT EXISTS cumulative_count (
                    uuid TEXT PRIMARY KEY,
                    count INTEGER NOT NULL
                )
            """)
        }
    }

    private fun loadPlayerData() {
        val stmt = conn.prepareStatement("SELECT uuid, relic_id FROM encyclopedia")
        val rs: ResultSet = stmt.executeQuery()
        while (rs.next()) {
            val uuid = UUID.fromString(rs.getString("uuid"))
            val id = rs.getInt("relic_id")
            encyclopediaData.computeIfAbsent(uuid) { mutableSetOf() }.add(id)
        }
        rs.close()
        stmt.close()
    }

    private fun savePlayerData() {
        if (!::conn.isInitialized || conn.isClosed) return
        conn.createStatement().execute("DELETE FROM encyclopedia")
        val ps = conn.prepareStatement("INSERT INTO encyclopedia (uuid, relic_id) VALUES (?, ?)")
        encyclopediaData.forEach { (uuid, ids) ->
            ids.forEach { id ->
                ps.setString(1, uuid.toString())
                ps.setInt(2, id)
                ps.addBatch()
            }
        }
        ps.executeBatch()
        ps.close()
    }

    private fun loadCumulativeData() {
        val stmt = conn.prepareStatement("SELECT uuid, count FROM cumulative_count")
        val rs = stmt.executeQuery()
        while (rs.next()) {
            val uuid = UUID.fromString(rs.getString("uuid"))
            cumulativeData[uuid] = rs.getInt("count")
        }
        rs.close()
        stmt.close()
    }

    private fun saveCumulativeData() {
        if (!::conn.isInitialized || conn.isClosed) return
        val ps = conn.prepareStatement("INSERT OR REPLACE INTO cumulative_count (uuid, count) VALUES (?, ?)")
        cumulativeData.forEach { (uuid, count) ->
            ps.setString(1, uuid.toString())
            ps.setInt(2, count)
            ps.addBatch()
        }
        ps.executeBatch()
        ps.close()
    }

    private fun createItemsConfig() {
        itemsConfigFile = File(dataFolder, "items.yml")
        if (!itemsConfigFile.exists()) {
            itemsConfigFile.parentFile.mkdirs()
            saveResource("items.yml", false)
        }
        itemsConfig = YamlConfiguration.loadConfiguration(itemsConfigFile)
    }

    private fun createLangConfig() {
        langConfigFile = File(dataFolder, "lang.yml")
        if (!langConfigFile.exists()) {
            langConfigFile.parentFile.mkdirs()
            saveResource("lang.yml", false)
        }
        langConfig = YamlConfiguration.loadConfiguration(langConfigFile)
    }

    private fun getMessage(path: String, replacements: Map<String, String> = emptyMap()): String {
        var message = langConfig.getString(path, "&cMessage not found: $path") ?: "&cMessage not found: $path"
        replacements.forEach { (key, value) ->
            message = message.replace("{$key}", value)
        }
        return message.replace("&", "§")
    }

    private fun loadRelics() {
        relicRegistry.clear()
        for (id in 1..maxId) { // 1부터 maxId까지 모든 유물 ID를 순회
            val key = id.toString()
            if (itemsConfig.isConfigurationSection(key)) {
                val section = itemsConfig.getConfigurationSection(key)!!
                val name = section.getString("name", "${id}번 유물")!!
                val lore = section.getStringList("lore")
                val leftClick = section.getStringList("left-click-commands")
                relicRegistry[id] = Relic(id, name, lore, leftClick)
            } else {
                // items.yml에 없는 유물은 기본값으로 등록
                relicRegistry[id] = Relic(id, "${id}번 유물", emptyList(), emptyList())
            }
        }
    }

    fun getItem(id: Int, isCollected: Boolean): ItemStack {
        val relic = relicRegistry[id]
        val name = relic?.name?.replace("&", "§") ?: "${id}번 유물"
        val lore = relic?.lore?.map { it.replace("&", "§") } ?: emptyList()

        return if (isCollected) {
            val item = ItemStack(Material.PAPER)
            val meta = item.itemMeta ?: return item
            meta.setDisplayName("§f$name")
            meta.lore = lore + listOf("", getMessage("gui.relic_id", mapOf("id" to id.toString())), getGrade(id))
            meta.setCustomModelData(id)
            item.itemMeta = meta
            item
        } else {
            ItemStack(Material.BARRIER).apply {
                val meta = itemMeta ?: return@apply
                meta.setDisplayName(getMessage("gui.relic_uncollected"))
                meta.lore = listOf(getMessage("gui.relic_id", mapOf("id" to id.toString())), getGrade(id))
                itemMeta = meta
            }
        }
    }

    private fun getGrade(id: Int): String {
        return when {
            id in 1..40 -> getMessage("gui.filter_normal")
            id in 41..80 -> getMessage("gui.filter_rare")
            else -> getMessage("gui.filter_legendary")
        }
    }

    private fun getGradeFilter(id: Int): GradeFilter {
        return when {
            id in 1..40 -> GradeFilter.NORMAL
            id in 41..80 -> GradeFilter.RARE
            else -> GradeFilter.LEGENDARY
        }
    }

    private fun openGUI(player: Player, page: Int, targetUUID: UUID) {
        val targetName = Bukkit.getOfflinePlayer(targetUUID).name ?: "알 수 없음"
        val currentFilter = playerFilters.getOrDefault(player.uniqueId, GradeFilter.ALL)
        
        val filteredRelics = (1..maxId).filter { id ->
            currentFilter == GradeFilter.ALL || getGradeFilter(id) == currentFilter
        }
        val maxPage = if (filteredRelics.isEmpty()) 1 else (filteredRelics.size - 1) / itemsPerPage + 1
        val currentPage = page.coerceIn(1, maxPage)
        
        val titleKey = when (currentFilter) {
            GradeFilter.ALL -> "gui-titles.all"
            GradeFilter.NORMAL -> "gui-titles.normal"
            GradeFilter.RARE -> "gui-titles.rare"
            GradeFilter.LEGENDARY -> "gui-titles.legendary"
        }
        val baseTitle = config.getString(titleKey, ":offset_-13::encyclopedia:")
        val title = "$baseTitle §8- ${targetName} (§e${currentPage}§8/§e${maxPage}§8)"
        val inv: Inventory = Bukkit.createInventory(null, 54, title)

        val startIdx = (currentPage - 1) * itemsPerPage
        val endIdx = (startIdx + itemsPerPage - 1).coerceAtMost(filteredRelics.size - 1)

        if (startIdx <= endIdx) {
            for (i in startIdx..endIdx) {
                val relicId = filteredRelics[i]
                val isCollected = encyclopediaData[targetUUID]?.contains(relicId) == true
                inv.setItem(i - startIdx, getItem(relicId, isCollected))
            }
        }

        if (currentPage > 1) inv.setItem(45, createPageItem(getMessage("gui.page_prev")))
        if (currentPage < maxPage) inv.setItem(53, createPageItem(getMessage("gui.page_next")))

        val collectedCount = encyclopediaData[targetUUID]?.size ?: 0
        inv.setItem(49, createInfoItem(getMessage("gui.progress", mapOf("collected" to collectedCount.toString(), "max" to maxId.toString()))))

        inv.setItem(47, createFilterItem(getMessage("gui.filter_all"), GradeFilter.ALL, currentFilter))
        inv.setItem(48, createFilterItem(getMessage("gui.filter_normal"), GradeFilter.NORMAL, currentFilter))
        inv.setItem(50, createFilterItem(getMessage("gui.filter_rare"), GradeFilter.RARE, currentFilter))
        inv.setItem(51, createFilterItem(getMessage("gui.filter_legendary"), GradeFilter.LEGENDARY, currentFilter))

        player.openInventory(inv)
    }

    private fun createPageItem(name: String): ItemStack {
        val item = ItemStack(Material.STRUCTURE_VOID)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        item.itemMeta = meta
        return item
    }

    private fun createInfoItem(name: String): ItemStack {
        val item = ItemStack(Material.BOOK)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        item.itemMeta = meta
        return item
    }

    private fun createFilterItem(name: String, filter: GradeFilter, currentFilter: GradeFilter): ItemStack {
        val material = if (filter == currentFilter) Material.GLOWSTONE_DUST else Material.GUNPOWDER
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta?.setDisplayName(name)
        if (filter == currentFilter) {
            meta?.lore = listOf(getMessage("gui.filter_selected"))
        }
        item.itemMeta = meta
        return item
    }

    @EventHandler
    fun onInventoryClick(e: InventoryClickEvent) {
        val player = e.whoClicked as? Player ?: return
        val invTitle = e.view.title
        val allTitle = config.getString("gui-titles.all", ":offset_-13::encyclopedia_all:") ?: ""
        val normalTitle = config.getString("gui-titles.normal", ":offset_-13::encyclopedia_normal:") ?: ""
        val rareTitle = config.getString("gui-titles.rare", ":offset_-13::encyclopedia_rare:") ?: ""
        val legendaryTitle = config.getString("gui-titles.legendary", ":offset_-13::encyclopedia_legendary:") ?: ""

        if (!invTitle.startsWith(allTitle) && !invTitle.startsWith(normalTitle) && !invTitle.startsWith(rareTitle) && !invTitle.startsWith(legendaryTitle)) return

        e.isCancelled = true
        val clickedItem = e.currentItem ?: return
        if (clickedItem.type == Material.AIR) return

        val targetName = invTitle.substringAfter("§8- ").substringBefore(" (")
        val targetUUID = Bukkit.getOfflinePlayer(targetName).uniqueId
        
        val pageRegex = "§e(\\d+)§8/§e(\\d+)".toRegex()
        val matchResult = pageRegex.find(invTitle)
        val currentPage = matchResult?.groups?.get(1)?.value?.toIntOrNull() ?: 1

        when (e.rawSlot) {
            in 0 until itemsPerPage -> { // 유물 아이템 슬롯 클릭
                val relicId = clickedItem.itemMeta?.customModelData ?: return
                val relic = relicRegistry[relicId] ?: return

                // 자신의 도감을 보고 있을 때
                if (player.uniqueId == targetUUID) {
                    if (encyclopediaData[player.uniqueId]?.contains(relicId) == true) { // 수집된 유물 클릭 (꺼내기)
                        // 우클릭 시에만 꺼내기
                        if (e.click == ClickType.RIGHT) {
                            val itemToGive = getItem(relicId, true) // 꺼낼 아이템 생성
                            if (player.inventory.firstEmpty() != -1) {
                                player.inventory.addItem(itemToGive)
                                player.sendMessage(getMessage("system.relic_removed_from_book", mapOf("relic_name" to relic.name)))
                                encyclopediaData[player.uniqueId]?.remove(relicId) // 도감에서 제거
                                openGUI(player, currentPage, targetUUID) // GUI 새로고침
                            } 
                        } 
                    } 
                } 
            }
            45 -> openGUI(player, currentPage - 1, targetUUID)
            53 -> openGUI(player, currentPage + 1, targetUUID)
            47 -> { playerFilters[player.uniqueId] = GradeFilter.ALL; openGUI(player, 1, targetUUID) }
            48 -> { playerFilters[player.uniqueId] = GradeFilter.NORMAL; openGUI(player, 1, targetUUID) }
            50 -> { playerFilters[player.uniqueId] = GradeFilter.RARE; openGUI(player, 1, targetUUID) }
            51 -> { playerFilters[player.uniqueId] = GradeFilter.LEGENDARY; openGUI(player, 1, targetUUID) }
        }
    }

    @EventHandler
    fun onPlayerInteract(e: PlayerInteractEvent) {
        val player = e.player
        val item = e.item ?: return
        if (item.type == Material.PAPER) {
            val relicId = item.itemMeta?.customModelData ?: return
            if (relicId in 1..maxId) {
                val relic = relicRegistry[relicId] ?: return
                val isCollected = encyclopediaData[player.uniqueId]?.contains(relicId) == true

                when (e.action) {
                    // 우클릭: 등록
                    org.bukkit.event.block.Action.RIGHT_CLICK_AIR, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK -> {
                        if (!isCollected) { // 아직 등록되지 않았다면 등록
                            if (encyclopediaData.computeIfAbsent(player.uniqueId) { mutableSetOf() }.add(relicId)) {
                                val currentCount = cumulativeData.getOrDefault(player.uniqueId, 0)
                                cumulativeData[player.uniqueId] = currentCount + 1
                                invalidateRankingCache()

                                val relicName = relicRegistry[relicId]?.name ?: "${relicId}번 유물"
                                player.sendMessage(getMessage("system.relic_registered", mapOf("relic_name" to relicName)))
                                item.amount-- // 아이템 소모
                                checkFull(player)
                                checkMilestone(player)
                            } else {
                                player.sendMessage(getMessage("system.relic_already_registered"))
                            }
                        }
                        e.isCancelled = true // 이벤트 취소하여 아이템 사용 방지
                    }
                    // 좌클릭: 명령어 실행
                    org.bukkit.event.block.Action.LEFT_CLICK_AIR, org.bukkit.event.block.Action.LEFT_CLICK_BLOCK -> {
                        executeCommands(player, relic.leftClickCommands)
                        e.isCancelled = true // 이벤트 취소
                    }
                    else -> {}
                }
            }
        }
    }

    private fun checkFull(player: Player) {
        val collected = encyclopediaData[player.uniqueId]?.size ?: 0
        if (collected >= maxId) {
            Bukkit.broadcastMessage(getMessage("system.collection_complete_broadcast", mapOf("player" to player.name)))
            player.sendTitle(getMessage("system.collection_complete_title"), getMessage("system.collection_complete_subtitle"), 10, 70, 20)
            
            executeCommands(player, config.getStringList("completion-commands"))
        }
    }

    private fun checkMilestone(player: Player) {
        val count = cumulativeData[player.uniqueId] ?: 0
        if (count > 0 && config.isConfigurationSection("milestone-rewards")) {
            val key = count.toString()
            if (config.getConfigurationSection("milestone-rewards")?.contains(key) == true) {
                val commands = config.getStringList("milestone-rewards.${key}")
                if (commands.isNotEmpty()) {
                    executeCommands(player, commands)
                }
            }
        }
    }

    private fun executeCommands(player: Player, commands: List<String>) {
        if (commands.isEmpty()) return
        val randomCommand = commands.random()
        randomCommand.split(";").forEach { cmd ->
            val command = cmd.trim().replace("{player}", player.name)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
        }
    }

    fun giveRelic(player: Player, id: Int) {
        val uuid = player.uniqueId
        if (encyclopediaData.computeIfAbsent(uuid) { mutableSetOf() }.add(id)) {
            val currentCount = cumulativeData.getOrDefault(uuid, 0)
            cumulativeData[uuid] = currentCount + 1
            invalidateRankingCache()

            val relicName = relicRegistry[id]?.name ?: "${id}번 유물"
            player.sendMessage(getMessage("system.relic_acquired", mapOf("relic_name" to relicName)))
            
            checkFull(player)
            checkMilestone(player)
        } 
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (command.name.lowercase()) {
            "도감" -> {
                if (sender !is Player) {
                    sender.sendMessage(getMessage("command.player_only"))
                    return false
                }
                openGUI(sender, 1, sender.uniqueId)
                return true
            }
            "도감랭킹" -> {
                val page = if (args.isNotEmpty()) args[0].toIntOrNull() ?: 1 else 1
                showRanking(sender, page)
                return true
            }
            "도감관리" -> {
                if (!sender.isOp) {
                    sender.sendMessage(getMessage("command.no_permission"))
                    return true
                }
                handleAdminCommand(sender, args)
                return true
            }
        }
        return false
    }

    private fun invalidateRankingCache() {
        rankingCache = null
    }

    private fun getRanking(): List<RankingEntry> {
        val now = System.currentTimeMillis()
        if (rankingCache != null && (now - lastCacheTime) < TimeUnit.MINUTES.toMillis(5)) {
            return rankingCache!!
        }

        val rankingData = mutableListOf<RankingEntry>()
        val stmt = conn.prepareStatement("SELECT uuid, count FROM cumulative_count ORDER BY count DESC")
        val rs = stmt.executeQuery()
        while(rs.next()) {
            val uuid = UUID.fromString(rs.getString("uuid"))
            val playerName = Bukkit.getOfflinePlayer(uuid).name ?: "알 수 없음"
            rankingData.add(RankingEntry(uuid, playerName, rs.getInt("count")))
        }
        rs.close()
        stmt.close()

        rankingCache = rankingData
        lastCacheTime = now
        return rankingData
    }

    private fun showRanking(sender: CommandSender, page: Int) {
        val rankingData = getRanking()
        val itemsPerPage = 10
        val maxPage = if (rankingData.isEmpty()) 1 else (rankingData.size - 1) / itemsPerPage + 1
        val currentPage = page.coerceIn(1, maxPage)

        sender.sendMessage(getMessage("command.ranking_header", mapOf("page" to currentPage.toString(), "max_page" to maxPage.toString())))
        val startIdx = (currentPage - 1) * itemsPerPage
        for (i in 0 until itemsPerPage) {
            val rank = startIdx + i
            if (rank >= rankingData.size) break
            val entry = rankingData[rank]
            sender.sendMessage(getMessage("command.ranking_format", mapOf("rank" to (rank + 1).toString(), "player" to entry.name, "count" to entry.count.toString())))
        }
    }
    
    private fun handleAdminCommand(sender: CommandSender, args: Array<out String>) {
        if (args.isEmpty()) {
            sender.sendMessage(getMessage("admin.usage"))
            return
        }

        val subCommand = args[0].lowercase()

        when (subCommand) {
            "확인" -> {
                if (sender !is Player) {
                    sender.sendMessage(getMessage("command.player_only"))
                    return
                }
                if (args.size < 2) {
                     sender.sendMessage(getMessage("admin.check_usage"))
                     return
                }
                val targetPlayer = Bukkit.getOfflinePlayer(args[1])
                val page = if (args.size > 2) args[2].toIntOrNull() ?: 1 else 1
                openGUI(sender, page, targetPlayer.uniqueId)
            }
            "초기화" -> {
                if (args.size < 2) {
                     sender.sendMessage(getMessage("admin.reset_usage"))
                     return
                }
                val targetPlayer = Bukkit.getOfflinePlayer(args[1])
                encyclopediaData.remove(targetPlayer.uniqueId)
                cumulativeData.remove(targetPlayer.uniqueId)
                invalidateRankingCache()
                sender.sendMessage(getMessage("admin.reset_success", mapOf("player" to (targetPlayer.name ?: args[1]))))
            }
            "유물" -> {
                if (sender !is Player) {
                    sender.sendMessage(getMessage("command.player_only"))
                    return
                }
                if (args.size < 2) {
                    sender.sendMessage(getMessage("admin.give_relic_usage"))
                    return
                }
                val id = args[1].toIntOrNull()
                if (id == null || id !in 1..maxId) {
                    sender.sendMessage(getMessage("admin.invalid_relic_id", mapOf("max_id" to maxId.toString())))
                    return
                }
                sender.inventory.addItem(getItem(id, true))
                sender.sendMessage(getMessage("admin.relic_given", mapOf("id" to id.toString())))
            }
            "리로드" -> {
                reloadAllConfigs()
                sender.sendMessage(getMessage("command.reload_success"))
            }
            else -> sender.sendMessage(getMessage("command.unknown_command"))
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): MutableList<String> {
        if (command.name.equals("도감관리", ignoreCase = true)) {
            if (args.size == 1) {
                return mutableListOf("확인", "초기화", "유물", "리로드")
                    .filter { it.startsWith(args[0], ignoreCase = true) }.toMutableList()
            }
            if (args.size == 2) {
                when (args[0].lowercase()) {
                    "확인", "초기화" -> {
                        return Bukkit.getOnlinePlayers().map { it.name }
                            .filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
                    }
                    "유물" -> {
                        return (1..maxId).map { it.toString() }
                            .filter { it.startsWith(args[1], ignoreCase = true) }.toMutableList()
                    }
                }
            }
        }
        return mutableListOf()
    }
}