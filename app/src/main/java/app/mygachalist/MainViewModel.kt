package app.mygachalist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mygachalist.data.Game
import app.mygachalist.data.ListEntry
import app.mygachalist.data.NewsItem
import app.mygachalist.data.RefreshResult
import app.mygachalist.data.Region
import app.mygachalist.data.Settings
import app.mygachalist.data.ThemeMode
import app.mygachalist.work.Notifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import app.mygachalist.ui.components.thumbUrl
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One shared ViewModel: the whole data set is a few hundred small objects, so screens filter it in memory. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as MyGachaListApp).repository
    private val eager = SharingStarted.Eagerly

    /** null until DataStore has loaded, so the theme does not flash. */
    val settings: StateFlow<Settings?> = repo.prefs.settings.stateIn(viewModelScope, eager, null)

    /** Every cached game, including adult ones (needed so list entries keep resolving). */
    private val allGames: StateFlow<List<Game>> = repo.games.stateIn(viewModelScope, eager, emptyList())

    /** Games the user may browse: adult titles only when the 18+ toggle is on. */
    val games: StateFlow<List<Game>> = combine(allGames, settings) { list, s ->
        if (s?.showAdult == true) list else list.filter { !it.adult }
    }.stateIn(viewModelScope, eager, emptyList())

    val gamesById: StateFlow<Map<String, Game>> =
        games.map { list -> list.associateBy { it.id } }.stateIn(viewModelScope, eager, emptyMap())

    val news: StateFlow<List<NewsItem>> = combine(repo.news, gamesById, allGames) { items, visible, all ->
        val hidden = all.asSequence().filter { it.id !in visible }.map { it.id }.toSet()
        items.filter { n -> n.gameIds.none { it in hidden } }
    }.stateIn(viewModelScope, eager, emptyList())

    val entries: StateFlow<Map<String, ListEntry>> =
        repo.entries.map { list -> list.associateBy { it.gameId } }.stateIn(viewModelScope, eager, emptyMap())

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    /** Adult covers the user tapped to un-blur. Kept for this app session only. */
    private val _revealed = MutableStateFlow<Set<String>>(emptySet())
    val revealed: StateFlow<Set<String>> = _revealed

    init {
        viewModelScope.launch {
            repo.seedIfEmpty()
            _loading.value = false
            refresh(announce = false)
        }
        // Whenever the visible game list changes, make sure every cover is on disk.
        viewModelScope.launch { games.collect { prefetchCovers(it) } }
    }

    private var prefetchJob: Job? = null
    private val prefetched = HashSet<String>()

    /**
     * Downloads covers into the disk cache ahead of time so lists show images immediately while
     * scrolling. Three at a time, decoded at a tiny size, so it stays cheap on a low-end phone.
     */
    private fun prefetchCovers(list: List<Game>) {
        val app = getApplication<Application>()
        val urls = list.asSequence().map { it.cover }.filter { it.isNotBlank() }.map(::thumbUrl).filter { it !in prefetched }.toList()
        if (urls.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(3)
            urls.forEach { url ->
                launch {
                    gate.withPermit {
                        val request = ImageRequest.Builder(app).data(url).size(16)
                            .memoryCachePolicy(CachePolicy.DISABLED).build()
                        val result = runCatching { app.imageLoader.execute(request) }.getOrNull()
                        if (result?.drawable != null) synchronized(prefetched) { prefetched += url }
                    }
                }
            }
        }
    }

    fun reveal(gameId: String) = _revealed.update { it + gameId }

    fun refresh(announce: Boolean = true) {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            val result = repo.refresh()
            _refreshing.value = false
            if (announce) {
                _messages.tryEmit(
                    when (result) {
                        is RefreshResult.Ok ->
                            "Updated: ${result.games} games" + if (result.skipped > 0) " (${result.skipped} broken entries skipped)" else ""
                        is RefreshResult.Failed -> result.message
                    }
                )
            }
            checkAlerts()
        }
    }

    fun checkAlerts() = viewModelScope.launch {
        runCatching { Notifier.checkAndNotify(getApplication(), repo, repo.prefs.settings.first()) }
    }

    fun saveEntry(entry: ListEntry) = viewModelScope.launch {
        repo.saveEntry(entry, allGames.value.firstOrNull { it.id == entry.gameId })
    }

    fun deleteEntry(gameId: String) = viewModelScope.launch { repo.deleteEntry(gameId) }

    fun setRegion(r: Region) = viewModelScope.launch { repo.prefs.setRegion(r) }
    fun setTheme(t: ThemeMode) = viewModelScope.launch { repo.prefs.setTheme(t) }
    fun setNotifyRelease(v: Boolean) = viewModelScope.launch { repo.prefs.setNotifyRelease(v) }
    fun setNotifyPrereg(v: Boolean) = viewModelScope.launch { repo.prefs.setNotifyPrereg(v) }
    fun setShowAdult(v: Boolean) = viewModelScope.launch { repo.prefs.setShowAdult(v) }

    suspend fun exportJson(): String = repo.exportJson()
    suspend fun importJson(text: String, replace: Boolean): Int? = repo.importJson(text, replace)
    fun say(message: String) { _messages.tryEmit(message) }
}
