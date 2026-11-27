package com.aliceincradle.launcher.data

enum class DownloadContentStatus {
    DOWNLOADED,
    DOWNLOADING,
    DISABLED
}

data class DownloadCatalogItem(
    val id: String,
    val title: String,
    val tags: List<String>,
    val sizeLabel: String,
    val status: DownloadContentStatus,
    val logoText: String = "Logo",
    val appName: String = "AIC",
    val versionChannel: String = "稳定版",
    val category: String = "武器强化",
    val isCompatibleVersion: Boolean = true,
    val isFeatured: Boolean = false,
    val author: String = "官方",
    val description: String = ""
)

data class DownloadFilterState(
    val query: String = "",
    val selectedApp: String = "AIC",
    val selectedVersion: String = "稳定版",
    val selectedCategory: String = "武器强化",
    val isVersionFilterApplied: Boolean = false,
    val isCategoryFilterApplied: Boolean = false,
    val includeCompatibleMods: Boolean = true,
    val includeFeaturedMods: Boolean = true,
    val appOptions: List<String> = listOf("AIC", "全部应用", "AIC 扩展"),
    val versionOptions: List<String> = listOf("全部版本", "稳定版", "测试版"),
    val categoryOptions: List<String> = listOf("全部类别", "武器强化", "游戏本体", "UI 优化")
)

data class DownloadPageState(
    val filter: DownloadFilterState = DownloadFilterState(),
    val statsText: String = "共 23333 个内容，耗时 0.01s",
    val allItems: List<DownloadCatalogItem> = DownloadCatalogPreviewFactory.createDefaultItems(),
    val items: List<DownloadCatalogItem> = allItems,
    val selectedDetailItem: DownloadCatalogItem? = null
)

object DownloadCatalogPreviewFactory {
    fun createDefaultItems(): List<DownloadCatalogItem> = listOf(
        DownloadCatalogItem(
            id = "aic-core-0.29d",
            title = "Alice In Cradle · 0.29d",
            tags = listOf("游戏本体", "官方", "稳定版"),
            sizeLabel = "约4.9GB",
            status = DownloadContentStatus.DOWNLOADED,
            appName = "AIC",
            versionChannel = "稳定版",
            category = "游戏本体",
            isCompatibleVersion = true,
            isFeatured = true,
            author = "官方",
            description = "Alice In Cradle 稳定发行版游戏本体，已完成本地兼容性检验。"
        ),
        DownloadCatalogItem(
            id = "mod-weapon-mastery",
            title = "Weapon Mastery Enhancer",
            tags = listOf("武器强化", "精品", "稳定版"),
            sizeLabel = "24.6MB",
            status = DownloadContentStatus.DOWNLOADING,
            appName = "AIC",
            versionChannel = "稳定版",
            category = "武器强化",
            isCompatibleVersion = true,
            isFeatured = true,
            author = "KaguyaLab",
            description = "扩展近战与法杖连段判定并附加蓄力特效，正在排队下载中。"
        ),
        DownloadCatalogItem(
            id = "mod-legacy-relic",
            title = "Legacy Relic Pack · 0.24",
            tags = listOf("武器强化", "不兼容", "旧版"),
            sizeLabel = "118MB",
            status = DownloadContentStatus.DISABLED,
            appName = "AIC",
            versionChannel = "稳定版",
            category = "武器强化",
            isCompatibleVersion = false,
            isFeatured = false,
            author = "ArchiveTeam",
            description = "依赖旧版 Harmony 接口签名，与当前 0.29d/0.3.0 运行时不兼容。"
        ),
        DownloadCatalogItem(
            id = "mod-crimson-blade",
            title = "Crimson Blade Rebalance",
            tags = listOf("武器强化", "社区", "稳定版"),
            sizeLabel = "12.8MB",
            status = DownloadContentStatus.DOWNLOADING,
            appName = "AIC",
            versionChannel = "稳定版",
            category = "武器强化",
            isCompatibleVersion = true,
            isFeatured = false,
            author = "NoelWorkshop",
            description = "微调武器耐力消耗曲线与反击窗口时间。"
        ),
        DownloadCatalogItem(
            id = "mod-hud-tweaks",
            title = "Quick Slot HUD Tweaks",
            tags = listOf("UI 优化", "精品", "稳定版"),
            sizeLabel = "4.2MB",
            status = DownloadContentStatus.DOWNLOADED,
            appName = "AIC",
            versionChannel = "稳定版",
            category = "UI 优化",
            isCompatibleVersion = true,
            isFeatured = true,
            author = "UiCraft",
            description = "触屏快捷栏重排与自适应缩放补丁。"
        ),
        DownloadCatalogItem(
            id = "aic-core-0.30-rc2",
            title = "Alice In Cradle · 0.30-rc2",
            tags = listOf("游戏本体", "官方", "测试版"),
            sizeLabel = "约5.1GB",
            status = DownloadContentStatus.DOWNLOADING,
            appName = "AIC",
            versionChannel = "测试版",
            category = "游戏本体",
            isCompatibleVersion = true,
            isFeatured = true,
            author = "官方",
            description = "预览测试分支游戏本体包，支持最新渲染管线。"
        ),
        DownloadCatalogItem(
            id = "mod-old-shader",
            title = "Old Shader Loader · 0.22",
            tags = listOf("UI 优化", "已禁用", "旧版"),
            sizeLabel = "8.5MB",
            status = DownloadContentStatus.DISABLED,
            appName = "AIC 扩展",
            versionChannel = "测试版",
            category = "UI 优化",
            isCompatibleVersion = false,
            isFeatured = false,
            author = "LegacyDev",
            description = "存在桌面 OpenGL 着色器硬编码，移动沙盒环境下已禁用。"
        )
    )

    fun filterItems(
        allItems: List<DownloadCatalogItem>,
        filter: DownloadFilterState
    ): List<DownloadCatalogItem> {
        val trimmedQuery = filter.query.trim().lowercase()
        return allItems.filter { item ->
            val matchesQuery = trimmedQuery.isEmpty() ||
                item.title.lowercase().contains(trimmedQuery) ||
                item.id.lowercase().contains(trimmedQuery) ||
                item.author.lowercase().contains(trimmedQuery) ||
                item.tags.any { it.lowercase().contains(trimmedQuery) }

            val matchesApp = filter.selectedApp == "全部应用" ||
                filter.selectedApp == "AIC" ||
                item.appName == filter.selectedApp

            val matchesVersion = !filter.isVersionFilterApplied ||
                filter.selectedVersion == "全部版本" ||
                item.versionChannel == filter.selectedVersion

            val matchesCategory = !filter.isCategoryFilterApplied ||
                filter.selectedCategory == "全部类别" ||
                item.category == filter.selectedCategory

            val matchesModFlags = when {
                item.status == DownloadContentStatus.DISABLED && !item.isCompatibleVersion && !item.isFeatured -> true
                item.isFeatured && !filter.includeFeaturedMods && !item.isCompatibleVersion -> false
                item.isFeatured && !filter.includeFeaturedMods && item.isCompatibleVersion -> filter.includeCompatibleMods
                item.isCompatibleVersion && !filter.includeCompatibleMods -> item.isFeatured && filter.includeFeaturedMods
                else -> true
            }

            matchesQuery && matchesApp && matchesVersion && matchesCategory && matchesModFlags
        }
    }
}
