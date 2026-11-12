package app.mygachalist

import android.app.Application
import app.mygachalist.data.Prefs
import app.mygachalist.data.Repository
import app.mygachalist.data.db.AppDatabase
import app.mygachalist.data.remote.GachaApi
import app.mygachalist.work.Notifier
import app.mygachalist.work.RefreshWorker
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class MyGachaListApp : Application(), ImageLoaderFactory, Configuration.Provider {
    val repository: Repository by lazy {
        Repository(this, AppDatabase.create(this), GachaApi.create(this), Prefs(this))
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        // Background refresh is a nice-to-have: never let a scheduling problem stop the app from opening.
        runCatching { RefreshWorker.schedule(this) }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    /** Small caches and RGB_565 bitmaps: covers are thumbnails, and the target phone has 4 GB RAM. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(40L * 1024 * 1024).build() }
        .allowRgb565(true)
        .crossfade(false)
        .respectCacheHeaders(false)
        .build()
}
