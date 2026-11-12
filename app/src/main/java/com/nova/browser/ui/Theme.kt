package com.nova.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
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

// NOVA Nebula — Dark Premium design language (shared across all NOVA apps;
// see ~/workspace/nova-design/NOVA-NEBULA-DESIGN.md).
// Dark theme is the default. Never change a token value per app.
val NebulaBackground = Color(0xFF0A0C1C)
val NebulaSurface = Color(0xFF14172B)
val NebulaElevated = Color(0xFF1B1F38)
val NebulaOutline = Color(0x14FFFFFF) // white 8% — 1dp card borders (dark)
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
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Paint a surface with the Nebula gradient (primary buttons, FABs). */
fun Modifier.nebulaGradient(shape: Shape = NovaButtonShape): Modifier =
    this.background(NebulaGradientBrush, shape)

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
    background = NebulaBackground,
    onBackground = NebulaOnSurface,
    surface = NebulaSurface,
    onSurface = NebulaOnSurface,
    surfaceVariant = NebulaElevated,
    onSurfaceVariant = NebulaMuted,
    surfaceTint = NebulaPrimary,
    inverseSurface = NebulaOnSurface,
    inverseOnSurface = NebulaBackground,
    outline = NebulaOutline,
    outlineVariant = Color(0x0DFFFFFF),
    scrim = Color.Black,
    surfaceContainerLowest = NebulaBackground,
    surfaceContainerLow = Color(0xFF10132A),
    surfaceContainer = Color(0xFF171B31),
    surfaceContainerHigh = NebulaElevated,
    surfaceContainerHighest = Color(0xFF232845)
)

// Light theme — soft surfaces, same violet primary.
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
    background = Color(0xFFF4F5FB),
    onBackground = Color(0xFF14172B),
    surface = Color.White,
    onSurface = Color(0xFF14172B),
    surfaceVariant = Color(0xFFE9EBF5),
    onSurfaceVariant = Color(0xFF5A6076),
    surfaceTint = NebulaPrimary,
    inverseSurface = Color(0xFF1B1F38),
    inverseOnSurface = NebulaOnSurface,
    outline = Color(0xFFE3E6F2),
    outlineVariant = Color(0xFFEFF1F8),
    scrim = Color.Black,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FD),
    surfaceContainer = Color(0xFFEFF1F8),
    surfaceContainerHigh = Color(0xFFE9EBF5),
    surfaceContainerHighest = Color(0xFFDEE1F0)
)

@Composable
fun NovaTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) NovaDarkColors else NovaLightColors,
        shapes = NovaShapes,
        content = content
    )
}

/** Empty state — centered icon in a gradient-tinted circle + muted message. */
@Composable
fun NebulaEmptyState(icon: ImageVector, message: String, modifier: Modifier = Modifier) {
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
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
