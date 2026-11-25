package com.pnprojects.tarangfx

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.pnprojects.tarangfx.audio.SelectionHolder
import com.pnprojects.tarangfx.data.media.MediaStoreRepository
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pnprojects.tarangfx.ui.intro.IntroScreen
import com.pnprojects.tarangfx.ui.navigation.AppNavHost
import com.pnprojects.tarangfx.ui.theme.AppColors
import com.pnprojects.tarangfx.ui.theme.TarangFXTheme
import androidx.compose.ui.graphics.toArgb
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var selection: SelectionHolder
    @Inject lateinit var media: MediaStoreRepository

    /** Set when the app is opened via "Open with" / share; consumed by the nav host. */
    private val incoming = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AppColors.Canvas.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(AppColors.Canvas.toArgb()),
        )
        // The animated intro plays on a fresh launch only: not after rotation / process restore,
        // and not when the app is opened to edit a file ("Open with" / share), where it would delay
        // the user's task.
        val playIntro = savedInstanceState == null &&
            intent?.action != Intent.ACTION_VIEW && intent?.action != Intent.ACTION_SEND
        setContent {
            TarangFXTheme {
                Box(Modifier.fillMaxSize()) {
                    // Home composes underneath during the intro, so it is ready the moment it fades.
                    AppNavHost(incomingFile = incoming, onIncomingConsumed = { incoming.value = null })
                    var showIntro by rememberSaveable { mutableStateOf(playIntro) }
                    AnimatedVisibility(visible = showIntro, enter = EnterTransition.None, exit = fadeOut(tween(450))) {
                        IntroScreen(onFinished = { showIntro = false })
                    }
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        lifecycleScope.launch {
            media.describe(uri)?.let {
                selection.set(listOf(it))
                incoming.value = System.nanoTime()
            }
        }
    }
}
