package com.tensei.ytxtube

import android.content.Context
import com.tensei.ytxtube.potoken.PoTokenGenerator
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor

class YTXTubeApplication private constructor() {

    companion object {
        lateinit var appContext: Context
            private set

        @Volatile private var initialized = false

        @JvmStatic
        @Synchronized
        fun initialize(context: Context) {
            if (initialized) return
            initialized = true
            appContext = context.applicationContext
            TvMode.init(appContext)
            LanguageSettings.init(appContext)
            GlassQuality.init(appContext)
            GlassSettings.init(appContext)
            NetworkSpeed.init(appContext)
            PlaybackBandwidth.init(appContext)
            NewPipe.init(NewPipeDownloader.instance)

            val poTokenGenerator = PoTokenGenerator()
            poTokenGenerator.initialize()
            YoutubeStreamExtractor.setPoTokenProvider(YtxPoTokenProvider(poTokenGenerator))

            YoutubeStreamExtractor.setFetchIosClient(true)
            HdrItags.install()
            ThemeSettings.init(appContext)
            HdrSupport.init(appContext)
            QualityPreference.init(appContext)
            PlaybackSpeed.init(appContext)
            AudioOnly.init(appContext)
            AudioTrackPreference.init(appContext)
            CaptionPreference.init(appContext)
            VideoGestureSettings.init(appContext)
            PlayerControlSettings.init(appContext)
            PipSettings.init(appContext)
            MiniPlayerSettings.init(appContext)
            ResumeStore.init(appContext)
            Account.init(appContext)
            SponsorBlock.init(appContext)
            SearchHistory.init(appContext)
            Downloads.init(appContext)

            appContext.getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    private var last: android.net.Network? = null
                    override fun onAvailable(network: android.net.Network) {
                        if (last != null && last != network) StreamCache.invalidateAll()
                        last = network
                    }
                },
            )
        }
    }
}
