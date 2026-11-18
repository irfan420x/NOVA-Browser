package com.nova.browser.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// NOVA Expressive Dark — Material 3 Expressive design language
// (Browser v2.3; replaces Glass Premium v2.1, which the user rejected
// on the phone, and Nebula v2.0 before it).
// Warm dark-gray flat surfaces, pill shapes, one warm-amber accent.
// No gradients, no glow, no frosted-glass effect: Compose has no real
// backdrop blur (Modifier.blur only blurs a composable's own content),
// so translucent surfaces here are flat scrims and are never described
// as blur. See ~/workspace/nova-muse-shared/THEME-DIRECTIONS.md
// (Direction A) for the research and the reference links.
// The old Glass/Nebula token names are kept as aliases so every screen
// keeps compiling — their values now point at the Expressive palette.

// Expressive tokens — dark.
val ExpressiveDarkBackground = Color(0xFF121214) // warm dark gray, not black
val ExpressiveSurface = Color(0xFF1E1E22) // bars, address pill
val ExpressiveSurfaceVariant = Color(0xFF26262B) // fields inside the pill
val ExpressiveSurfaceFaint = Color(0xFF17171A) // lowest container
val ExpressiveFloating = Color(0xE5232327) // 90% opaque #232327 — menus, sheets
val ExpressiveFloatingStrong = Color(0xE026262B) // 88% opaque #26262B
val ExpressiveFloatingSoft = Color(0xD92A2A2F) // 85% opaque #2A2A2F
val ExpressiveBorder = Color(0x14FFFFFF) // white 8% — 1dp hairline (dark)
val ExpressiveBorderSoft = Color(0x0AFFFFFF) // white 4%
val ExpressiveAccent = Color(0xFFE8A33D) // warm amber — the one accent
val ExpressiveAccentDark = Color(0xFFC78A2E) // amber, darker end of gradient
val ExpressiveOnSurface = Color(0xFFF5F1EA) // warm white text
val ExpressiveMuted = Color(0xFFA8A29A) // warm gray secondary text
val ExpressiveError = Color(0xFFF87171)
val ExpressiveSuccess = Color(0xFF34D399)
val ExpressiveSecondary = Color(0xFF4DB6AC) // teal
val ExpressiveTertiary = Color(0xFFF06292) // rose, sparing

// Expressive tokens — light (same shapes/spacing, inverted surfaces).
val ExpressiveLightBackground = Color(0xFFF7F4EF) // warm off-white
val ExpressiveLightSurface = Color(0xFFFFFFFF)
val ExpressiveLightSurfaceVariant = Color(0xFFF0ECE6)
val ExpressiveLightSurfaceFaint = Color(0xFFFFFDF8)
val ExpressiveLightFloating = Color(0xE6FFFFFF) // white 90%
val ExpressiveLightFloatingStrong = Color(0xEBFFFFFF) // white 92%
val ExpressiveLightFloatingSoft = Color(0xF0FFFFFF) // white 94%
val ExpressiveLightBorder = Color(0x1414172B) // dark 8% — 1dp hairline (light)
val ExpressiveLightBorderSoft = Color(0x0A14172B) // dark 4%
// Darker amber in light mode: #E8A33D on white is unreadable as text.
val ExpressiveLightAccent = Color(0xFFA66E14)
val ExpressiveLightOnSurface = Color(0xFF1C1A17)
val ExpressiveLightMuted = Color(0xFF5A564F)

// Glass tokens — repointed at the Expressive palette (Browser v2.3).
val GlassDarkBackground = ExpressiveDarkBackground
val GlassDarkBackgroundEnd = ExpressiveDarkBackground
val GlassSurface = ExpressiveSurface
val GlassSurfaceSoft = ExpressiveSurfaceVariant
val GlassSurfaceFaint = ExpressiveSurfaceFaint
val GlassBorder = ExpressiveBorder
val GlassBorderSoft = ExpressiveBorderSoft
val GlassFloating = ExpressiveFloating
val GlassFloatingStrong = ExpressiveFloatingStrong
val GlassFloatingSoft = ExpressiveFloatingSoft
val GlassGlowDark = Color.Transparent // v2.3: no glow — kept so old refs compile
val GlassLightBackground = ExpressiveLightBackground
val GlassLightBackgroundEnd = ExpressiveLightBackground
val GlassLightSurface = ExpressiveLightSurface
val GlassLightSurfaceSoft = ExpressiveLightSurfaceVariant
val GlassLightBorder = ExpressiveLightBorder
val GlassLightBorderSoft = ExpressiveLightBorderSoft
val GlassLightFloating = ExpressiveLightFloating
val GlassLightFloatingStrong = ExpressiveLightFloatingStrong
val GlassLightFloatingSoft = ExpressiveLightFloatingSoft
val GlassGlowLight = Color.Transparent // v2.3: no glow

// Background brushes — flat solids now (v2.1 used a vertical gradient).
val GlassDarkBackgroundBrush: Brush = Brush.verticalGradient(
    listOf(ExpressiveDarkBackground, ExpressiveDarkBackground)
)
val GlassLightBackgroundBrush: Brush = Brush.verticalGradient(
    listOf(ExpressiveLightBackground, ExpressiveLightBackground)
)

// Legacy Nebula token names — repointed at the Expressive palette.
val NebulaBackground = ExpressiveDarkBackground
val NebulaSurface = ExpressiveSurface
val NebulaElevated = ExpressiveSurfaceVariant
val NebulaOutline = ExpressiveBorder
val NebulaPrimary = ExpressiveAccent
val NebulaGradientStart = ExpressiveAccent
val NebulaGradientEnd = ExpressiveAccentDark
val NebulaSecondary = ExpressiveSecondary
val NebulaTertiary = ExpressiveTertiary
val NebulaOnSurface = ExpressiveOnSurface
val NebulaMuted = ExpressiveMuted
val NebulaError = ExpressiveError
val NebulaSuccess = ExpressiveSuccess

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

/** Primary gradient: FABs, active tab pills, hero accents, progress.
 *  v2.3: amber → dark amber (was violet → blue). */
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

// Shapes — M3 Expressive: pill address bar, 16dp cards, 12dp squircle
// new-tab button, 16dp buttons. (v2.1 was cards 20dp, buttons 14dp.)
val ExpressivePillShape = RoundedCornerShape(28.dp)
val ExpressiveNewTabShape = RoundedCornerShape(12.dp)
val NovaCardShape = RoundedCornerShape(16.dp)
val NovaButtonShape = RoundedCornerShape(16.dp)
val NovaRowShape = RoundedCornerShape(12.dp)
val NovaFabShape = RoundedCornerShape(16.dp)

private val NovaShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Paint a surface with the accent gradient (primary buttons, FABs). */
fun Modifier.nebulaGradient(shape: Shape = NovaButtonShape): Modifier =
    this.background(NebulaGradientBrush, shape)

/**
 * Expressive background — the app's canvas. A flat warm dark-gray
 * (light: warm off-white). No gradient, no glow: M3 Expressive is
 * shape + spacing + color, not an effect. Every screen's Scaffold uses
 * a transparent container color so this shows through.
 */
@Composable
fun ExpressiveBackground(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .background(if (darkTheme) ExpressiveDarkBackground else ExpressiveLightBackground)
    ) {
        content()
    }
}

/**
 * GlassBackground — legacy name, kept so old call sites compile.
 * It now paints the same flat Expressive canvas (no gradient, no glow).
 */
@Composable
fun GlassBackground(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    ExpressiveBackground(darkTheme, content)
}

/**
 * A glass card — M3 Card with the 1dp hairline border by default
 * (`colorScheme.outline`, white 8% dark / dark 8% light). Call sites may
 * pass their own [border] (selected tab, private banner) or [colors] — both
 * are forwarded unchanged. A null [border] means "the default hairline";
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
 * hairline, so every sub-screen gets the top bar without touching
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
    primary = ExpressiveAccent,
    onPrimary = Color(0xFF2A1D05),
    primaryContainer = Color(0xFF4A3200),
    onPrimaryContainer = Color(0xFFF5D9A8),
    secondary = ExpressiveSecondary,
    onSecondary = Color(0xFF062A28),
    secondaryContainer = Color(0xFF0E3A36),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary = ExpressiveTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF4A1230),
    onTertiaryContainer = Color(0xFFF8BBD0),
    error = ExpressiveError,
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF4A1D1D),
    onErrorContainer = Color(0xFFFECACA),
    background = ExpressiveDarkBackground,
    onBackground = ExpressiveOnSurface,
    surface = ExpressiveSurface,
    onSurface = ExpressiveOnSurface,
    surfaceVariant = ExpressiveSurfaceVariant,
    onSurfaceVariant = ExpressiveMuted,
    surfaceTint = ExpressiveAccent,
    inverseSurface = ExpressiveOnSurface,
    inverseOnSurface = ExpressiveDarkBackground,
    outline = ExpressiveBorder,
    outlineVariant = ExpressiveBorderSoft,
    scrim = Color.Black,
    surfaceContainerLowest = ExpressiveSurfaceFaint,
    surfaceContainerLow = ExpressiveSurface,
    surfaceContainer = ExpressiveFloating,
    surfaceContainerHigh = ExpressiveFloatingStrong,
    surfaceContainerHighest = ExpressiveFloatingSoft
)

// Light theme — same shapes/spacing, inverted warm surfaces.
private val NovaLightColors = lightColorScheme(
    primary = ExpressiveLightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5D9A8),
    onPrimaryContainer = Color(0xFF4A3200),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF0E3A36),
    tertiary = Color(0xFFBE185D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF8BBD0),
    onTertiaryContainer = Color(0xFF4A1230),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = ExpressiveLightBackground,
    onBackground = ExpressiveLightOnSurface,
    surface = ExpressiveLightSurface,
    onSurface = ExpressiveLightOnSurface,
    surfaceVariant = ExpressiveLightSurfaceVariant,
    onSurfaceVariant = ExpressiveLightMuted,
    surfaceTint = ExpressiveLightAccent,
    inverseSurface = Color(0xFF232327),
    inverseOnSurface = ExpressiveOnSurface,
    outline = ExpressiveLightBorder,
    outlineVariant = ExpressiveLightBorderSoft,
    scrim = Color.Black,
    surfaceContainerLowest = ExpressiveLightSurfaceFaint,
    surfaceContainerLow = ExpressiveLightSurface,
    surfaceContainer = ExpressiveLightFloating,
    surfaceContainerHigh = ExpressiveLightFloatingStrong,
    surfaceContainerHighest = ExpressiveLightFloatingSoft
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
