package dev.janakhpon.monocr.ui.components

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.core.os.LocaleListCompat
import dev.janakhpon.monocr.R

/** The languages the app ships strings for, as BCP 47 tags. */
internal val APP_LANGUAGE_TAGS = listOf("en", "my", "mnw")

/**
 * Picks the app language.
 *
 * No flags: Mon has no flag of its own, and a country flag stands for a country, not a
 * language. The current language carries a selected radio button instead.
 */
@Composable
fun LanguagePickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit = { tag ->
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
) {
    val haptic = LocalHapticFeedback.current
    val labels = mapOf(
        "en" to stringResource(R.string.lang_en),
        "my" to stringResource(R.string.lang_my),
        "mnw" to stringResource(R.string.lang_mnw)
    )
    val current = ConfigurationCompat.getLocales(LocalConfiguration.current)[0]?.language

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        title = { Text(stringResource(R.string.nav_language), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.selectableGroup()) {
                APP_LANGUAGE_TAGS.forEach { tag ->
                    val selected = tag == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelect(tag)
                                onDismiss()
                            }
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Text(
                            labels.getValue(tag),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )
}
