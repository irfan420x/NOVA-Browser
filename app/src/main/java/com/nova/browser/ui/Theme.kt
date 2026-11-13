package com.nova.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// NOVA Glass — Glass Premium design language (Browser v2.1;
// see ~/workspace/nova-design/NOVA-GLASS-DESIGN.md).
// Dark-first glassmorphism: deep gradient background + violet glow,
// frosted translucent surfaces, 1dp hairline borders.
// The old Nebula token names are kept as aliases so every screen keeps
// compiling — their values now point at the glass palette.

// Glass tokens — dark.
val GlassDarkBackground = Color(0xFF0B0E1E) // gradient start / fallback solid
val GlassDarkBackgroundEnd = Color(0xFF12142A) // gradient end
val GlassSurface = Color(0x14FFFFFF) // white 8% — bars, cards
val GlassSurfaceSoft = Color(0x0FFFFFFF) // white 6%
val GlassSurfaceFaint = Color(0x0AFFFFFF) // white 4%
val GlassBorder = Color(0x1FFFFFFF) // white 12% — 1dp glass border (dark)
val GlassBorderSoft = Color(0x14FFFFFF) // white 8%
// Smoked glass — floating elements over arbitrary web content (menus,
// dropdowns, dialogs, sheets). A 6–10% white fill over a bright page
// washes out and near-white text goes unreadable, so floating elements
// use a mostly-opaque dark navy instead. Still translucent at the edges.
val GlassFloating = Color(0xE514172B) // 90% opaque #14172B
val GlassFloatingStrong = Color(0xE01B1F38) // 88% opaque #1B1F38
val GlassFloatingSoft = Color(0xD91B1F38) // 85% opaque #1B1F38
val GlassGlowDark = Color(0x297C3AED) // violet 16% — radial glow

// Glass tokens — light.
val GlassLightBackground = Color(0xFFF4F5FB)
val GlassLightBackgroundEnd = Color(0xFFE8EAF6)
val GlassLightSurface = Color(0xB3FFFFFF) // white 70%
val GlassLightSurfaceSoft = Color(0x99FFFFFF) // white 60%
val GlassLightBorder = Color(0x1A14172B) // dark 10%
val GlassLightBorderSoft = Color(0x0F14172B) // dark 6%
val GlassLightFloating = Color(0xE0FFFFFF) // white 88%
val GlassLightFloatingStrong = Color(0xE6FFFFFF) // white 90%
val GlassLightFloatingSoft = Color(0xEBFFFFFF) // white 92%
val GlassGlowLight = Color(0x1A7C3AED) // violet 10%

// Background gradient brushes (vertical; sized by the drawn area).
val GlassDarkBackgroundBrush: Brush = Brush.verticalGradient(
    listOf(GlassDarkBackground, GlassDarkBackgroundEnd)
)
val GlassLightBackgroundBrush: Brush = Brush.verticalGradient(
    listOf(GlassLightBackground, GlassLightBackgroundEnd)
)

// Legacy Nebula token names — repointed at the glass palette (Browser v2.1).
val NebulaBackground = GlassDarkBackground
val NebulaSurface = GlassSurface
val NebulaElevated = Color(0x1FFFFFFF) // white 12% — glass raised surface
val NebulaOutline = GlassBorder // white 12% — 1dp glass borders (dark)
val NebulaPrimary = Color(0xFF8B5CF6) // violet
val NebulaGradientStart = Color(0xFF7C3AED)
val NebulaGradientEnd = Color(0xFF3B82F6)
val NebulaSecondary = Color(0xFF22D3EE) // cyan
val NebulaTertiary = Color(0xFFEC4899) // pink, sparing
val NebulaOnSurface = Color(0xFFEDEFF7)
val NebulaMuted = Color(0xFF9AA3C7) // secondary text
val NebulaError = Color(0xFFF87171)
val NebulaSuccess = Color(0xFF34D399)

// Legacy aliases (short Nova* names used by the other NOVA apps).
val NovaBg = NebulaBackground
val NovaSurface = NebulaSurface
val NovaCard = NebulaElevated
val NovaOutline = NebulaOutline
val NovaAccent = NebulaPrimary
val NovaGradientStart = NebulaGradientStart
val NovaGradientEnd = NebulaGradientEnd
val NovaAccent2 = NebulaSecondary
val NovaTertiary = NebulaTertiary
val NovaText = NebulaOnSurface
val NovaMuted = NebulaMuted
val NovaMissed = NebulaError
val NovaGood = NebulaSuccess

/** Primary gradient: FABs, active tab pills, hero accents, progress. */
val NebulaGradientBrush: Brush =
    Brush.horizontalGradient(listOf(NebulaGradientStart, NebulaGradientEnd))
val NovaGradientBrush: Brush = NebulaGradientBrush

/** Same gradient at low alpha — soft gradient-tinted circles (empty states). */
val NebulaGradientTintBrush: Brush = Brush.horizontalGradient(
    listOf(
        NebulaGradientStart.copy(alpha = 0.18f),
        NebulaGradientEnd.copy(alpha = 0.18f)
    )
)

// Shapes — spec: cards 20dp, buttons 14dp, chips 12dp, FAB 16dp.
val NovaCardShape = RoundedCornerShape(20.dp)
val NovaButtonShape = RoundedCornerShape(14.dp)
val NovaRowShape = RoundedCornerShape(12.dp)
val NovaFabShape = RoundedCornerShape(16.dp)

private val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    // T2 (W7): buttons render with shapes.small — the spec says buttons
    // are 14dp, so small is 14dp. Chips keep their own 12dp via
    // NovaRowShape where the app draws them itself; the 2dp difference
    // on stock M3 chips is not visible in practice.
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Paint a surface with the Nebula gradient (primary buttons, FABs). */
fun Modifier.nebulaGradient(shape: Shape = NovaButtonShape): Modifier =
    this.background(NebulaGradientBrush, shape)

/**
 * Glass background — the app's canvas. Paints the vertical gradient plus a
 * soft radial violet glow at the top, computed from the real size so the
 * glow stays centered on any screen. Every screen's Scaffold uses a
 * transparent container color so this shows through; translucent surfaces
 * floating over it are what make the glass look.
 */
@Composable
fun GlassBackground(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Box(
        Modifier.fillMaxSize()
            .background(if (darkTheme) GlassDarkBackgroundBrush else GlassLightBackgroundBrush)
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val glow = remember(widthPx, heightPx, darkTheme) {
                Brush.radialGradient(
                    colors = listOf(
                        if (darkTheme) GlassGlowDark else GlassGlowLight,
                        Color.Transparent
                    ),
                    center = Offset(widthPx / 2f, 0f),
                    radius = maxOf(widthPx, heightPx) * 0.85f
                )
            }
            Box(Modifier.fillMaxSize().background(glow))
        }
        content()
    }
}

/**
 * A glass card — M3 Card with the 1dp glass border by default
 * (`colorScheme.outline`, white 12% dark / dark 10% light). Call sites may
 * pass their own [border] (selected tab, private banner) or [colors] — both
 * are forwarded unchanged. A null [border] means "the default glass border";
 * sites that draw their own border on the modifier keep plain Card instead.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = NovaCardShape,
    colors: CardColors? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = colors ?: CardDefaults.cardColors(),
        border = border ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        content = content
    )
}

/**
 * A glass top bar — M3 TopAppBar (translucent surface) + a 1dp bottom
 * hairline, so every sub-screen gets the glass top bar without touching
 * each call site's arguments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    Column(modifier) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions
        )
        Box(
            Modifier.fillMaxWidth().height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
    }
}

private val NovaDarkColors = darkColorScheme(
    primary = NebulaPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E1F5E),
    onPrimaryContainer = Color(0xFFDCD2FF),
    secondary = NebulaSecondary,
    onSecondary = Color(0xFF062A33),
    secondaryContainer = Color(0xFF0E3A44),
    onSecondaryContainer = Color(0xFFA5F3FC),
    tertiary = NebulaTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF4A1230),
    onTertiaryContainer = Color(0xFFFBCFE8),
    error = NebulaError,
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF4A1D1D),
    onErrorContainer = Color(0xFFFECACA),
    background = GlassDarkBackground,
    onBackground = NebulaOnSurface,
    surface = GlassSurface,
    onSurface = NebulaOnSurface,
    surfaceVariant = GlassSurfaceSoft,
    onSurfaceVariant = NebulaMuted,
    surfaceTint = NebulaPrimary,
    inverseSurface = NebulaOnSurface,
    inverseOnSurface = GlassDarkBackground,
    outline = GlassBorder,
    outlineVariant = GlassBorderSoft,
    scrim = Color.Black,
    surfaceContainerLowest = GlassSurfaceFaint,
    surfaceContainerLow = GlassSurface,
    surfaceContainer = GlassFloating,
    surfaceContainerHigh = GlassFloatingStrong,
    surfaceContainerHighest = GlassFloatingSoft
)

// Light theme — light glass: white ~70% fills, soft shadows, same violet.
private val NovaLightColors = lightColorScheme(
    primary = NebulaPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E4FF),
    onPrimaryContainer = Color(0xFF2E1F5E),
    secondary = Color(0xFF0891B2),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE),
    onSecondaryContainer = Color(0xFF164E63),
    tertiary = Color(0xFFDB2777),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFBCFE8),
    onTertiaryContainer = Color(0xFF500724),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = GlassLightBackground,
    onBackground = Color(0xFF14172B),
    surface = GlassLightSurface,
    onSurface = Color(0xFF14172B),
    surfaceVariant = GlassLightSurfaceSoft,
    onSurfaceVariant = Color(0xFF5A6076),
    surfaceTint = NebulaPrimary,
    inverseSurface = Color(0xFF1B1F38),
    inverseOnSurface = NebulaOnSurface,
    outline = GlassLightBorder,
    outlineVariant = GlassLightBorderSoft,
    scrim = Color.Black,
    surfaceContainerLowest = Color(0xCCFFFFFF),
    surfaceContainerLow = GlassLightSurface,
    surfaceContainer = GlassLightFloating,
    surfaceContainerHigh = GlassLightFloatingStrong,
    surfaceContainerHighest = GlassLightFloatingSoft
)

@Composable
fun NovaTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NovaDarkColors else NovaLightColors,
        shapes = NovaShapes,
        content = content
    )
}

/** Empty state — centered icon in a gradient-tinted circle + muted message.
 *
 *  T1 (W7): [title] and an optional [actionLabel]/[onAction] button —
 *  an empty state with only a message gives the user nothing to do.
 *  Both are optional so existing call sites keep working unchanged. */
@Composable
fun NebulaEmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(72.dp).background(NebulaGradientTintBrush, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                icon, null, Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(16.dp))
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
