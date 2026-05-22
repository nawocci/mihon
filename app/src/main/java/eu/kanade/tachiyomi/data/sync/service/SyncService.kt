package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SyncData(
    val deviceId: String = "",
    val backup: Backup? = null,
)

abstract class SyncService(
    val context: Context,
    val json: Json,
    val syncPreferences: SyncPreferences,
) {
    abstract suspend fun doSync(syncData: SyncData): Backup?

    protected fun mergeSyncData(localSyncData: SyncData, remoteSyncData: SyncData): SyncData {
        val mergedCategoriesList = mergeCategoriesLists(localSyncData.backup?.backupCategories, remoteSyncData.backup?.backupCategories)
        val mergedMangaList = mergeMangaLists(localSyncData.backup?.backupManga, remoteSyncData.backup?.backupManga, localSyncData.backup?.backupCategories ?: emptyList(), remoteSyncData.backup?.backupCategories ?: emptyList(), mergedCategoriesList)
        val mergedSourcesList = mergeSourcesLists(localSyncData.backup?.backupSources, remoteSyncData.backup?.backupSources)
        val mergedPreferencesList = mergePreferencesLists(localSyncData.backup?.backupPreferences, remoteSyncData.backup?.backupPreferences)
        val mergedSourcePreferencesList = mergeSourcePreferencesLists(localSyncData.backup?.backupSourcePreferences, remoteSyncData.backup?.backupSourcePreferences)

        val mergedBackup = Backup(
            backupManga = mergedMangaList,
            backupCategories = mergedCategoriesList,
            backupSources = mergedSourcesList,
            backupPreferences = mergedPreferencesList,
            backupSourcePreferences = mergedSourcePreferencesList,
        )

        return SyncData(
            deviceId = syncPreferences.uniqueDeviceID(),
            backup = mergedBackup,
        )
    }

    private fun mergeMangaLists(
        localMangaList: List<BackupManga>?,
        remoteMangaList: List<BackupManga>?,
        localCategories: List<BackupCategory>,
        remoteCategories: List<BackupCategory>,
        mergedCategories: List<BackupCategory>,
    ): List<BackupManga> {
        val localMangaListSafe = localMangaList.orEmpty()
        val remoteMangaListSafe = remoteMangaList.orEmpty()

        fun mangaCompositeKey(manga: BackupManga): String {
            return "${manga.source}|${manga.url}|${manga.title.lowercase().trim()}|${manga.author?.lowercase()?.trim()}"
        }

        val localMangaMap = localMangaListSafe.associateBy { mangaCompositeKey(it) }
        val remoteMangaMap = remoteMangaListSafe.associateBy { mangaCompositeKey(it) }

        val localCategoriesMapByOrder = localCategories.associateBy { it.order }
        val remoteCategoriesMapByOrder = remoteCategories.associateBy { it.order }
        val mergedCategoriesMapByName = mergedCategories.associateBy { it.name }

        fun updateCategories(theManga: BackupManga, theMap: Map<Long, BackupCategory>): BackupManga {
            return theManga.copy(
                categories = theManga.categories.mapNotNull {
                    theMap[it]?.let { category ->
                        mergedCategoriesMapByName[category.name]?.order
                    }
                },
            )
        }

        val syncOptions = syncPreferences.getSyncSettings()

        return (localMangaMap.keys + remoteMangaMap.keys).distinct().mapNotNull { compositeKey ->
            val local = localMangaMap[compositeKey]
            val remote = remoteMangaMap[compositeKey]

            when {
                local != null && remote == null -> updateCategories(local, localCategoriesMapByOrder)
                local == null && remote != null -> updateCategories(remote, remoteCategoriesMapByOrder)
                local != null && remote != null -> {
                    // Simple union without timestamps: keep local but merge chapters and favorite status
                    updateCategories(
                        local.copy(
                            chapters = mergeChapters(local.chapters, remote.chapters, syncOptions.chapters),
                            favorite = local.favorite || remote.favorite
                        ),
                        localCategoriesMapByOrder,
                    )
                }
                else -> null
            }
        }
    }

    private fun mergeChapters(
        localChapters: List<BackupChapter>,
        remoteChapters: List<BackupChapter>,
        syncingChapters: Boolean,
    ): List<BackupChapter> {
        if (!syncingChapters) return remoteChapters

        fun chapterCompositeKey(chapter: BackupChapter): String {
            return "${chapter.url}|${chapter.name}"
        }

        val localChapterMap = localChapters.associateBy { chapterCompositeKey(it) }
        val remoteChapterMap = remoteChapters.associateBy { chapterCompositeKey(it) }

        return (localChapterMap.keys + remoteChapterMap.keys).distinct().mapNotNull { compositeKey ->
            val localChapter = localChapterMap[compositeKey]
            val remoteChapter = remoteChapterMap[compositeKey]

            when {
                localChapter != null && remoteChapter == null -> localChapter
                localChapter == null && remoteChapter != null -> remoteChapter
                localChapter != null && remoteChapter != null -> {
                    // If both exist, keep local but merge read/bookmark status
                    localChapter.copy(
                        read = localChapter.read || remoteChapter.read,
                        bookmark = localChapter.bookmark || remoteChapter.bookmark,
                        lastPageRead = maxOf(localChapter.lastPageRead, remoteChapter.lastPageRead)
                    )
                }
                else -> null
            }
        }
    }

    private fun mergeCategoriesLists(
        localCategoriesList: List<BackupCategory>?,
        remoteCategoriesList: List<BackupCategory>?,
    ): List<BackupCategory> {
        val result = mutableListOf<BackupCategory>()
        val processedLocals = mutableSetOf<BackupCategory>()

        val localMapByName = localCategoriesList?.associateBy { it.name } ?: emptyMap()

        remoteCategoriesList?.forEach { remote ->
            val localMatch = localMapByName[remote.name]
            if (localMatch != null) {
                processedLocals.add(localMatch)
                result.add(localMatch)
            } else {
                result.add(remote)
            }
        }

        localCategoriesList?.forEach { local ->
            if (local !in processedLocals) {
                result.add(local)
            }
        }

        return result.sortedBy { it.order }
    }

    private fun mergeSourcesLists(
        localSources: List<BackupSource>?,
        remoteSources: List<BackupSource>?,
    ): List<BackupSource> {
        val localSourceMap = localSources?.associateBy { it.sourceId } ?: emptyMap()
        val remoteSourceMap = remoteSources?.associateBy { it.sourceId } ?: emptyMap()

        return (localSourceMap.keys + remoteSourceMap.keys).distinct().mapNotNull { sourceId ->
            localSourceMap[sourceId] ?: remoteSourceMap[sourceId]
        }
    }

    private fun mergePreferencesLists(
        localPreferences: List<BackupPreference>?,
        remotePreferences: List<BackupPreference>?,
    ): List<BackupPreference> {
        val localPreferencesMap = localPreferences?.associateBy { it.key } ?: emptyMap()
        val remotePreferencesMap = remotePreferences?.associateBy { it.key } ?: emptyMap()

        return (localPreferencesMap.keys + remotePreferencesMap.keys).distinct().mapNotNull { key ->
            localPreferencesMap[key] ?: remotePreferencesMap[key]
        }
    }

    private fun mergeSourcePreferencesLists(
        localPreferences: List<BackupSourcePreferences>?,
        remotePreferences: List<BackupSourcePreferences>?,
    ): List<BackupSourcePreferences> {
        val localPreferencesMap = localPreferences?.associateBy { it.sourceKey } ?: emptyMap()
        val remotePreferencesMap = remotePreferences?.associateBy { it.sourceKey } ?: emptyMap()

        return (localPreferencesMap.keys + remotePreferencesMap.keys).distinct().mapNotNull { sourceKey ->
            val localSourcePreference = localPreferencesMap[sourceKey]
            val remoteSourcePreference = remotePreferencesMap[sourceKey]

            when {
                localSourcePreference != null && remoteSourcePreference == null -> localSourcePreference
                remoteSourcePreference != null && localSourcePreference == null -> remoteSourcePreference
                localSourcePreference != null && remoteSourcePreference != null -> {
                    val mergedPrefs = mergeIndividualPreferences(localSourcePreference.prefs, remoteSourcePreference.prefs)
                    BackupSourcePreferences(sourceKey, mergedPrefs)
                }
                else -> null
            }
        }
    }

    private fun mergeIndividualPreferences(
        localPrefs: List<BackupPreference>,
        remotePrefs: List<BackupPreference>,
    ): List<BackupPreference> {
        val mergedPrefsMap = (localPrefs + remotePrefs).associateBy { it.key }
        return mergedPrefsMap.values.toList()
    }
}
