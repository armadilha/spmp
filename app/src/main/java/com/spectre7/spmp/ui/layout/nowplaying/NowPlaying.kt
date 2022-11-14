package com.spectre7.spmp.ui.layout.nowplaying

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.*
import com.github.krottv.compose.sliders.DefaultThumb
import com.github.krottv.compose.sliders.SliderValueHorizontal
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.spectre7.spmp.MainActivity
import com.spectre7.spmp.PlayerHost
import com.spectre7.spmp.R
import com.spectre7.spmp.ui.component.MultiSelector
import com.spectre7.utils.getString
import com.spectre7.utils.setColourAlpha
import com.spectre7.utils.vibrate

enum class AccentColourSource { THUMBNAIL, SYSTEM }
enum class ThemeMode { BACKGROUND, ELEMENTS, NONE }

enum class NowPlayingTab { RELATED, PLAYER, QUEUE }

const val SEEK_CANCEL_THRESHOLD = 0.03f
const val EXPANDED_THRESHOLD = 0.9f
val NOW_PLAYING_MAIN_PADDING = 10.dp

@Composable
fun NowPlaying(_expansion: Float, max_height: Float, close: () -> Unit) {

    val expansion = if (_expansion < 0.08f) 0.0f else _expansion
    val expanded = expansion >= EXPANDED_THRESHOLD
    val inv_expansion = -expansion + 1.0f

    val systemui_controller = rememberSystemUiController()

    LaunchedEffect(key1 = expanded, key2 = MainActivity.theme.getBackground(true)) {
        systemui_controller.setSystemBarsColor(
            color = if (expanded) MainActivity.theme.getBackground(true) else MainActivity.theme.default_n_background
        )
    }

    if (!expanded) {
        LinearProgressIndicator(
            progress = PlayerHost.status.m_position,
            color = MainActivity.theme.getOnBackground(true),
            trackColor = setColourAlpha(MainActivity.theme.getOnBackground(true), 0.5),
            modifier = Modifier
                .requiredHeight(2.dp)
                .fillMaxWidth()
                .alpha(inv_expansion)
        )
    }

    val screen_width_dp = LocalConfiguration.current.screenWidthDp.dp
    val screen_width_px = with(LocalDensity.current) { screen_width_dp.roundToPx() }
    val NOW_PLAYING_MAIN_PADDING_px = with(LocalDensity.current) { NOW_PLAYING_MAIN_PADDING.roundToPx() }

    var thumbnail by remember { mutableStateOf<ImageBitmap?>(null) }

    Box(Modifier.padding(NOW_PLAYING_MAIN_PADDING)) {

        var current_tab by remember { mutableStateOf(NowPlayingTab.PLAYER) }

        fun getTabScrollTarget(): Int {
            return current_tab.ordinal * (screen_width_px - (NOW_PLAYING_MAIN_PADDING_px * 2))
        }
        val tab_scroll_state = rememberScrollState(getTabScrollTarget())

        LaunchedEffect(current_tab) {
            tab_scroll_state.animateScrollTo(getTabScrollTarget())
        }

        LaunchedEffect(expanded) {
            if (!expanded) {
                current_tab = NowPlayingTab.PLAYER
            }
            tab_scroll_state.scrollTo(getTabScrollTarget())
        }

        @Composable
        fun Tab(tab: NowPlayingTab, modifier: Modifier = Modifier) {
            BackHandler(tab == current_tab && expanded) {
                if (tab == NowPlayingTab.PLAYER) {
                    close()
                }
                else {
                    current_tab = NowPlayingTab.PLAYER
                }
            }

            Column(verticalArrangement = Arrangement.Top, modifier = modifier.fillMaxHeight().run {
                if (tab == NowPlayingTab.PLAYER) {
                    padding(15.dp * expansion)
                }
                else {
                    padding(top = 20.dp)
                }
            }) {
                if (tab == NowPlayingTab.PLAYER) {
                    MainTab(Modifier.weight(1f), expansion, max_height, thumbnail) { thumbnail = it }
                }
                else if (tab == NowPlayingTab.QUEUE) {
                    QueueTab(Modifier.weight(1f))
                }
            }
        }

        Column(verticalArrangement = Arrangement.Top, modifier = Modifier.fillMaxHeight()) {

            Row(
                Modifier
                    .horizontalScroll(tab_scroll_state, false)
                    .requiredWidth(screen_width_dp * 3)
                    .weight(1f)
            ) {
                for (page in 0 until NowPlayingTab.values().size) {
                    Tab(NowPlayingTab.values()[page], Modifier.requiredWidth(screen_width_dp - (NOW_PLAYING_MAIN_PADDING * 2)))
                }
            }

            if (expansion > 0.0f) {
                MultiSelector(
                    3,
                    current_tab.ordinal,
                    Modifier.requiredHeight(60.dp * 0.8f),
                    Modifier.aspectRatio(1f),
                    colour = setColourAlpha(MainActivity.theme.getOnBackground(true), 0.75),
                    background_colour = MainActivity.theme.getBackground(true),
                    on_selected = { current_tab = NowPlayingTab.values()[it] }
                ) { index ->

                    val tab = NowPlayingTab.values()[index]

                    Box(
                        contentAlignment = Alignment.Center
                    ) {

                        val colour = if (tab == current_tab) MainActivity.theme.getBackground(true) else MainActivity.theme.getOnBackground(true)

                        Image(
                            when(tab) {
                                NowPlayingTab.PLAYER -> rememberVectorPainter(Icons.Filled.PlayArrow)
                                NowPlayingTab.QUEUE -> painterResource(R.drawable.ic_music_queue)
                                NowPlayingTab.RELATED -> rememberVectorPainter(Icons.Filled.Menu)
                            }, "",
                            Modifier
                                .requiredSize(60.dp * 0.4f, 60.dp)
                                .offset(y = (-7).dp),
                            colorFilter = ColorFilter.tint(colour)
                        )
                        Text(when (tab) {
                            NowPlayingTab.PLAYER -> getString(R.string.now_playing_player)
                            NowPlayingTab.QUEUE -> getString(R.string.now_playing_queue)
                            NowPlayingTab.RELATED -> getString(R.string.now_playing_related)
                        }, color = colour, fontSize = 10.sp, modifier = Modifier.offset(y = (10).dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SeekBar(seek: (Float) -> Unit) {
    var position_override by remember { mutableStateOf<Float?>(null) }
    var old_position by remember { mutableStateOf<Float?>(null) }
    var grab_start_position by remember { mutableStateOf<Float?>(null) }

    @Composable
    fun SeekTrack(
        modifier: Modifier,
        progress: Float,
        enabled: Boolean,
        track_colour: Color = Color(0xffD3B4F7),
        progress_colour: Color = Color(0xff7000F8),
        height: Dp = 4.dp,
        highlight_colour: Color = setColourAlpha(Color.Red, 0.2)
    ) {
        Canvas(
            Modifier
                .then(modifier)
                .height(height)
        ) {

            val left = Offset(0f, center.y)
            val right = Offset(size.width, center.y)
            val start = if (layoutDirection == LayoutDirection.Rtl) right else left
            val end = if (layoutDirection == LayoutDirection.Rtl) left else right

            drawLine(
                track_colour,
                start,
                end,
                size.height,
                StrokeCap.Round,
                alpha = if (enabled) 1f else 0.6f
            )

            drawLine(
                progress_colour,
                Offset(
                    start.x,
                    center.y
                ),
                Offset(
                    start.x + (end.x - start.x) * progress,
                    center.y
                ),
                size.height,
                StrokeCap.Round,
                alpha = if (enabled) 1f else 0.6f
            )

            if (grab_start_position != null) {
                drawLine(
                    highlight_colour,
                    Offset(size.width * (grab_start_position!! - SEEK_CANCEL_THRESHOLD / 2.0f), center.y),
                    Offset(size.width * (grab_start_position!! + SEEK_CANCEL_THRESHOLD / 2.0f), center.y),
                    size.height,
                    StrokeCap.Square,
                    alpha = if (enabled) 1f else 0.6f
                )
            }
        }
    }

    var cancel_area_side: Int? by remember { mutableStateOf(null) }

    fun getSliderValue(): Float {
        if (position_override != null && old_position != null) {
            if (PlayerHost.status.m_position != old_position) {
                old_position = null
                position_override = null
            }
            else {
                return position_override!!
            }
        }
        return position_override ?: PlayerHost.status.m_position
    }

    SliderValueHorizontal(
        value = getSliderValue(),
        onValueChange = {
            if (grab_start_position == null) {
                grab_start_position = PlayerHost.status.position
            }

            position_override = it

            val side = if (it <= grab_start_position!! - SEEK_CANCEL_THRESHOLD / 2.0) -1 else if (it >= grab_start_position!! + SEEK_CANCEL_THRESHOLD / 2.0) 1 else 0
            if (side != cancel_area_side) {
                if (side == 0 || side + (cancel_area_side ?: 0) == 0) {
                    vibrate(0.01)
                }
                cancel_area_side = side
            }
        },
        onValueChangeFinished = {
            if (cancel_area_side == 0 && grab_start_position != null) {
                vibrate(0.01)
            }
            else {
                seek(position_override!!)
            }
            old_position = PlayerHost.status.position
            grab_start_position = null
            cancel_area_side = null
        },
        thumbSizeInDp = DpSize(12.dp, 12.dp),
        track = { a, b, _, _, c -> SeekTrack(a, b, c, setColourAlpha(MainActivity.theme.getOnBackground(true), 0.5), MainActivity.theme.getOnBackground(true)) },
        thumb = { a, b, c, d, e -> DefaultThumb(a, b, c, d, e, MainActivity.theme.getOnBackground(true), 1f) }
    )
}