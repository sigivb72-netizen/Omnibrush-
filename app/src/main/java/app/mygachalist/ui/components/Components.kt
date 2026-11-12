package app.mygachalist.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mygachalist.data.Game
import app.mygachalist.data.GameStatus
import app.mygachalist.data.ListEntry
import app.mygachalist.data.RegionDate
import app.mygachalist.data.gameStatus
import app.mygachalist.ui.theme.color
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background == Color.White
    val container = if (light) scheme.primary else scheme.surfaceContainer
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 19.sp) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = container,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White,
        ),
    )
}

/** Blue-underlined section title, optionally with a "More" link on the right. */
@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (action != null && onAction != null) {
                Text(
                    action, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onAction).padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(thickness = 2.dp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
    }
}

@Composable
fun StatusBadge(status: GameStatus, modifier: Modifier = Modifier) {
    Text(
        status.label,
        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 1, lineHeight = 12.sp,
        modifier = modifier.background(status.color(), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/**
 * Cover thumbnail. Falls back to the title's initials when there is no image.
 * Adult covers stay blurred (or fully hidden below Android 12) until [revealed].
 */
@Composable
fun CoverImage(game: Game, revealed: Boolean, modifier: Modifier = Modifier) {
    val hide = game.adult && !revealed
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Text(
            initials(game.title), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center,
        )
        val canBlur = Build.VERSION.SDK_INT >= 31
        if (game.cover.isNotBlank() && (!hide || canBlur)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(game.cover).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().then(if (hide) Modifier.blur(24.dp) else Modifier),
            )
        }
        if (hide) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = if (canBlur) 0.35f else 0.85f)), contentAlignment = Alignment.Center) {
                Text("18+", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

private fun initials(title: String): String =
    title.split(' ', ':', '-').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

/** Dense list row: status strip, thumbnail, title, meta line, date, and the user's score on the right. */
@Composable
fun GameRow(
    game: Game,
    entry: ListEntry?,
    date: RegionDate?,
    revealed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showRegion: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(entry?.listStatus?.color() ?: Color.Transparent))
        CoverImage(game, revealed, Modifier.padding(start = 7.dp, top = 6.dp, bottom = 6.dp).size(width = 46.dp, height = 62.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(
                game.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val meta = listOf(game.developer, game.genres.take(2).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, fontSize = 11.5.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(game.gameStatus)
                if (date != null) {
                    val label = date.date.display() + (if (showRegion && !date.date.isTba) " · ${date.region.label}" else "") +
                        (if (!date.confirmed && !date.date.isTba) " (rumoured)" else "")
                    Text(label, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (entry != null) {
            Column(Modifier.padding(end = 10.dp).widthIn(min = 44.dp), horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, Modifier.size(13.dp), tint = Color(0xFFF1B434))
                    Text(if (entry.score > 0) entry.score.toString() else "-", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Text(entry.listStatus.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

/** A chip that opens a menu. `null` is the "any" choice. */
@Composable
fun <T> DropdownChip(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T?) -> Unit,
    modifier: Modifier = Modifier,
    allowAny: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilterChip(
            selected = selected != null && allowAny,
            onClick = { open = true },
            label = { Text(if (selected == null) label else if (allowAny) optionLabel(selected) else "$label: ${optionLabel(selected)}", fontSize = 12.5.sp, maxLines = 1) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(16.dp)) },
            modifier = Modifier.height(32.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowAny) {
                DropdownMenuItem(text = { Text("Any ${label.lowercase()}") }, onClick = { open = false; onSelect(null) })
            }
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(optionLabel(o), fontWeight = if (o == selected) FontWeight.Bold else FontWeight.Normal) },
                    onClick = { open = false; onSelect(o) },
                )
            }
        }
    }
}

@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Opens a link in the browser without crashing when no app can handle it. */
@Composable
fun rememberLinkOpener(): (String) -> Unit {
    val handler = LocalUriHandler.current
    return remember(handler) { { url: String -> runCatching { handler.openUri(url) } } }
}

/** Label / value row of the dense info tables on the detail page. */
@Composable
fun InfoRow(label: String, value: String, striped: Boolean, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (striped) MaterialTheme.colorScheme.surfaceContainer else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
        Text(value, fontSize = 12.5.sp, lineHeight = 16.sp, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}
