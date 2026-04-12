package dev.janakhpon.monocr.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.janakhpon.monocr.ui.UiState
import dev.janakhpon.monocr.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon

@Composable
fun HeroHeader(uiState: UiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "MonOCR",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1).sp
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.hero_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))

        // Status chip
        EngineStatusChip(uiState)
    }
}

@Composable
fun EngineStatusChip(uiState: UiState) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.35f, label = "dot_alpha",
        animationSpec = infiniteRepeatable(tween(750, easing = LinearEasing), RepeatMode.Reverse)
    )

    val text: String
    val dotColor: androidx.compose.ui.graphics.Color
    val chipBg: androidx.compose.ui.graphics.Color
    val textColor: androidx.compose.ui.graphics.Color

    when (uiState) {
        is UiState.Initializing -> {
            text = stringResource(R.string.engine_initializing)
            dotColor = MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
            chipBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            textColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
        is UiState.InitError -> {
            text = stringResource(R.string.error_engine_load)
            dotColor = MaterialTheme.colorScheme.error
            chipBg = MaterialTheme.colorScheme.errorContainer
            textColor = MaterialTheme.colorScheme.onErrorContainer
        }
        is UiState.Processing -> {
            text = stringResource(R.string.scanning)
            dotColor = MaterialTheme.colorScheme.tertiary.copy(alpha = pulseAlpha)
            chipBg = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
            textColor = MaterialTheme.colorScheme.onTertiaryContainer
        }
        else -> {
            text = stringResource(R.string.engine_ready)
            dotColor = MaterialTheme.colorScheme.primary
            chipBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            textColor = MaterialTheme.colorScheme.onPrimaryContainer
        }
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = chipBg,
        modifier = Modifier.height(30.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1,
                fontSize = 12.sp
            )
        }
    }
}
