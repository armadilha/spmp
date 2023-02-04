@file:OptIn(ExperimentalComposeUiApi::class)

package com.spectre7.spmp.ui.component

import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.palette.graphics.Palette
import com.spectre7.spmp.MainActivity
import com.spectre7.spmp.model.*
import com.spectre7.spmp.ui.layout.PlayerViewContext
import com.spectre7.spmp.ui.layout.getScreenHeight
import com.spectre7.utils.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

const val LONG_PRESS_ICON_MENU_OPEN_ANIM_MS = 200

class LongPressMenuActionProvider(
    val content_colour: Color,
    val accent_colour: Color,
    val background_colour: Color,
    val player: PlayerViewContext
) {
    @Composable
    fun ActionButton(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
        ActionButton(icon, label, accent_colour, modifier = modifier, onClick = onClick)

    companion object {
        @Composable
        fun ActionButton(icon: ImageVector, label: String, icon_colour: Color = LocalContentColor.current, text_colour: Color = Color.Unspecified, modifier: Modifier = Modifier, onClick: () -> Unit) {
            Row(
                modifier
                    .clickable(onClick = onClick)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Icon(icon, null, tint = icon_colour)
                Text(label, fontSize = 15.sp, color = text_colour)
            }
        }
    }
}

@Composable
fun LongPressIconMenu(
    showing: Boolean,
    onDismissRequest: () -> Unit,
    media_item: MediaItem,
    player: PlayerViewContext,
    _thumb_size: Dp,
    thumb_shape: Shape,
    actions: @Composable LongPressMenuActionProvider.(MediaItem) -> Unit,
    onShown: () -> Unit = {}
) {
    var hide_thumb by remember { mutableStateOf(false) }
    var thumb_position: Offset? by remember { mutableStateOf(null) }
    var thumb_size: IntSize? by remember { mutableStateOf(null) }

    @Composable
    fun Thumb(modifier: Modifier) {
        Crossfade(media_item.getThumbnail(MediaItem.ThumbnailQuality.LOW)) { thumbnail ->
            if (thumbnail != null) {
                Image(
                    thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = modifier
                        .clip(thumb_shape)
                )
            }
        }
    }

    Thumb(Modifier
        .size(_thumb_size)
        .onGloballyPositioned {
            thumb_position = it.positionInWindow()
        }
        .onSizeChanged {
            thumb_size = it
        }
        .alpha(if (hide_thumb) 0f else 1f)
    )

    if (showing && thumb_position != null && thumb_size != null) {
        val density = LocalDensity.current
        val status_bar_height = getStatusBarHeight(MainActivity.context)

        val initial_pos = remember { with (density) { DpOffset(thumb_position!!.x.toDp(), thumb_position!!.y.toDp() - status_bar_height) } }
        val initial_size = remember { with (density) { DpSize(thumb_size!!.width.toDp(), thumb_size!!.height.toDp()) } }

        var fully_open by remember { mutableStateOf(false) }

        val pos = remember { Animatable(initial_pos, DpOffset.VectorConverter) }
        val width = remember { Animatable(initial_size.width.value) }
        val height = remember { Animatable(initial_size.height.value) }
        val panel_alpha = remember { Animatable(1f) }

        var target_position: Offset? by remember { mutableStateOf(null) }
        var target_size: IntSize? by remember { mutableStateOf(null) }

        var accent_colour by remember { mutableStateOf(Color.Unspecified) }

        fun applyPalette(palette: Palette) {
            accent_colour = MediaItem.getDefaultPaletteColour(palette, MainActivity.theme.getBackground(false)).contrastAgainst(MainActivity.theme.getBackground(false), 0.2)
        }

        LaunchedEffect(Unit) {
            if (media_item is Song && media_item.registry.theme_colour != null) {
                accent_colour = Color(media_item.registry.theme_colour!!)
                return@LaunchedEffect
            }

            if (!media_item.isThumbnailLoaded(MediaItem.ThumbnailQuality.LOW) && !media_item.isThumbnailLoaded(MediaItem.ThumbnailQuality.HIGH)) {
                media_item.getThumbnail(MediaItem.ThumbnailQuality.LOW) {
                    applyPalette(media_item.thumbnail_palette!!)
                }
            }
            else {
                applyPalette(media_item.thumbnail_palette!!)
            }
        }

        suspend fun animateValues(to_target: Boolean) {

            val pos_target: DpOffset
            val width_target: Float
            val height_target: Float

            if (to_target) {
                with (density) {
                    pos_target = DpOffset(target_position!!.x.toDp(), target_position!!.y.toDp())
                    width_target = target_size!!.width.toDp().value
                    height_target = target_size!!.height.toDp().value
                }
            }
            else {
                pos_target = initial_pos
                width_target = initial_size.width.value
                height_target = initial_size.height.value
            }

            if (!to_target) {
                fully_open = false
            }

            coroutineScope {
                launch {
                    panel_alpha.animateTo(if (to_target) 1f else 0f, tween(LONG_PRESS_ICON_MENU_OPEN_ANIM_MS))
                }

                val pos_job = launch {
                    pos.animateTo(pos_target, tween(LONG_PRESS_ICON_MENU_OPEN_ANIM_MS))
                }
                val width_job = launch {
                    width.animateTo(width_target, tween(LONG_PRESS_ICON_MENU_OPEN_ANIM_MS))
                }
                val height_job = launch {
                    height.animateTo(height_target, tween(LONG_PRESS_ICON_MENU_OPEN_ANIM_MS))
                }

                pos_job.join()
                width_job.join()
                height_job.join()

                fully_open = to_target
            }
        }

        LaunchedEffect(Unit) {
            animateValues(true)
        }

        suspend fun closePopup() {
            animateValues(false)
            hide_thumb = false
            onDismissRequest()
        }

        var close_requested by remember { mutableStateOf(false) }
        LaunchedEffect(close_requested) {
            if (close_requested) {
                closePopup()
            }
        }

        Dialog(
            onDismissRequest = { close_requested = true },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {

            val dialog = LocalView.current.parent as DialogWindowProvider
            dialog.window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            Box(
                Modifier
                    .requiredHeight(getScreenHeight().dp)
                    .offset(y = status_bar_height * -0.5f)
                    .background(Color.Black.setAlpha(0.5 * panel_alpha.value))
            ) {
                val shape = RoundedCornerShape(topStartPercent = 12, topEndPercent = 12)

                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .clickable(remember { MutableInteractionSource() }, null) {
                            close_requested = true
                        }
                    )
                    Column(
                        Modifier
                            .alpha(panel_alpha.value)
                            .background(MainActivity.theme.getBackground(false), shape)
                            .fillMaxWidth()
                            .padding(25.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Row(
                            Modifier
                                .height(80.dp)
                                .fillMaxWidth()
                        ) {
                            Thumb(Modifier
                                .alpha(if (fully_open) 1f else 0f)
                                .aspectRatio(1f)
                                .onSizeChanged {
                                    target_size = it
                                }
                                .onGloballyPositioned {
                                    target_position = it.localPositionOf(
                                        it.parentCoordinates!!.parentCoordinates!!,
                                        it.positionInRoot()
                                    )
                                }
                            )

                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                                    .padding(horizontal = 15.dp)
                                , verticalArrangement = Arrangement.Center) {

                                if (media_item is Song) {
                                    Marquee(false) {
                                        Text(
                                            media_item.title,
                                            Modifier.fillMaxWidth(),
                                            color = MainActivity.theme.getOnBackground(false),
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }

                                val artist = media_item.getAssociatedArtist()
                                if (artist != null) {
                                    Marquee(false) {
                                        artist.PreviewLong(
                                            content_colour = MainActivity.theme.getOnBackground(false),
                                            player,
                                            false,
                                            Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        Divider(thickness = Dp.Hairline, color = MainActivity.theme.getOnBackground(false))

                        actions(LongPressMenuActionProvider(MainActivity.theme.getOnBackground(false), accent_colour, MainActivity.theme.getBackground(false), player), media_item)

                        val share_intent = remember(media_item.url) {
                            Intent.createChooser(Intent().apply {
                                action = Intent.ACTION_SEND

                                if (media_item is Song) {
                                    putExtra(Intent.EXTRA_TITLE, media_item.title)
                                }

                                putExtra(Intent.EXTRA_TEXT, media_item.url)
                                type = "text/plain"
                            }, null)
                        }

                        LongPressMenuActionProvider.ActionButton(Icons.Filled.Share, "Share", accent_colour) {
                            MainActivity.context.startActivity(share_intent)
                        }

                        val open_intent: Intent? = remember(media_item.url) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(media_item.url))
                            if (intent.resolveActivity(MainActivity.context.packageManager) == null) {
                                null
                            }
                            else {
                                intent
                            }
                        }

                        if (open_intent != null) {
                            LongPressMenuActionProvider.ActionButton(Icons.Filled.OpenWith, "Open externally", accent_colour) {
                                MainActivity.context.startActivity(open_intent)
                            }
                        }
                    }
                }

                if (!fully_open) {
                    Box(
                        Modifier
                            .offset(pos.value.x, pos.value.y + status_bar_height)
                            .requiredSize(width.value.dp, height.value.dp)
                            .clip(thumb_shape)
                    ) {
                        Thumb(Modifier.fillMaxSize())
                        hide_thumb = true
                        onShown()
                    }
                }
            }
        }
    }
}