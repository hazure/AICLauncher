package com.aliceincradle.launcher.data

enum class DownloadContentStatus {
    AVAILABLE,
    QUEUED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
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
    val description: String = "",
    val downloadUrl: String = "",
    val fileName: String = "",
    val sizeBytes: Long = 0L,
    val progressPercent: Int = 0,
    val downloadedBytes: Long = 0L,
    val localFilePath: String? = null,
    val errorMessage: String? = null
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
    val statsText: String = "正在拉取远端资源列表...",
    val allItems: List<DownloadCatalogItem> = emptyList(),
    val items: List<DownloadCatalogItem> = emptyList(),
    val selectedDetailItem: DownloadCatalogItem? = null,
    val isLoadingCatalog: Boolean = false,
    val catalogError: String? = null
) {
    val activeDownloadCount: Int
        get() = allItems.count {
            it.status == DownloadContentStatus.QUEUED || it.status == DownloadContentStatus.DOWNLOADING
        }
}

object DownloadCatalogFilter {
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
