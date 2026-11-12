package app.mygachalist.data.remote

import app.mygachalist.BuildConfig
import app.mygachalist.data.AppJson
import app.mygachalist.data.NewsFile
import android.content.Context
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import java.io.File
import java.util.concurrent.TimeUnit

interface GachaApi {
    /** Raw body: games.json is hand-edited, so it goes through the tolerant parser instead of a converter. */
    @GET("games.json")
    suspend fun games(): ResponseBody

    @GET("news.json")
    suspend fun news(): NewsFile

    companion object {
        fun create(context: Context): GachaApi {
            val client = OkHttpClient.Builder()
                .cache(Cache(File(context.cacheDir, "http"), 4L * 1024 * 1024))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(BuildConfig.DATA_BASE_URL)
                .client(client)
                // raw.githubusercontent.com serves JSON as text/plain; the converter does not care.
                .addConverterFactory(AppJson.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(GachaApi::class.java)
        }
    }
}
