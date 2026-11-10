package com.zivi.avwx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zivi.avwx.wx.FlightCat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val AppColors = darkColorScheme(
    primary = Color(0xFF5CC8FF),
    onPrimary = Color(0xFF00283A),
    primaryContainer = Color(0xFF0E3A52),
    onPrimaryContainer = Color(0xFFCDEBFF),
    secondary = Color(0xFF8BD3C7),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF1C3A44),
    onSecondaryContainer = Color(0xFFD6F1FF),
    background = Color(0xFF0A0E13),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0A0E13),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF151C25),
    onSurfaceVariant = Color(0xFF9BA8B6),
    surfaceContainer = Color(0xFF10161D),
    surfaceContainerHigh = Color(0xFF151C25),
    surfaceContainerHighest = Color(0xFF1B242F),
    outline = Color(0xFF3A4656),
    outlineVariant = Color(0xFF232D39),
    error = Color(0xFFFF6B6B),
)

val CardColor = Color(0xFF131A22)
val Amber = Color(0xFFFFB84D)

fun catColor(c: FlightCat): Color = when (c) {
    FlightCat.VFR -> Color(0xFF34C759)
    FlightCat.MVFR -> Color(0xFF4A90FF)
    FlightCat.IFR -> Color(0xFFFF4D4D)
    FlightCat.LIFR -> Color(0xFFE052E0)
    FlightCat.UNKNOWN -> Color(0xFF6B7785)
}

/**
 * Dark theme always. Night mode multiplies everything on screen by red,
 * so only the red channel reaches the eye, whatever a screen draws.
 */
@Composable
fun AppTheme(night: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors) {
        val filter = if (night) {
            Modifier.drawWithContent {
                drawContent()
                drawRect(Color(0xFFFF2A14), blendMode = BlendMode.Multiply)
            }
        } else Modifier
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).then(filter)) {
            content()
        }
    }
}

@Composable
fun CatPill(cat: FlightCat, modifier: Modifier = Modifier) {
    val label = if (cat == FlightCat.UNKNOWN) "N/A" else cat.name
    Box(
        modifier
            .background(catColor(cat), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = Color(0xFF06090C), fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- time formatting

object Fmt {
    private val Z_HM = DateTimeFormatter.ofPattern("HHmm'Z'").withZone(ZoneOffset.UTC)
    private val Z_DHM = DateTimeFormatter.ofPattern("ddHHmm'Z'").withZone(ZoneOffset.UTC)
    private val Z_DH = DateTimeFormatter.ofPattern("dd/HH'Z'").withZone(ZoneOffset.UTC)

    fun zulu(t: Instant): String = Z_HM.format(t)
    fun zuluDay(t: Instant): String = Z_DHM.format(t)
    fun zuluDayHour(t: Instant): String = Z_DH.format(t)
    fun local(t: Instant): String =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(t)

    /** "just now", "12 min ago", "2 h 05 min ago". */
    fun ago(thenMs: Long, nowMs: Long): String {
        val min = ((nowMs - thenMs) / 60000L).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h %02d min ago".format(min % 60)
            else -> "${min / 1440} days ago"
        }
    }
}
