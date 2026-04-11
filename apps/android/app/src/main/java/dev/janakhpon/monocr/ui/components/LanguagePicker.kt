package dev.janakhpon.monocr.ui.components

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import dev.janakhpon.monocr.R

@Composable
fun LanguagePickerDialog(
    onDismiss: () -> Unit
) {
    val languages = listOf(
        Triple("en", stringResource(R.string.lang_en), "🇺🇸"),
        Triple("my", stringResource(R.string.lang_my), "🇲🇲"),
        Triple("mnw", stringResource(R.string.lang_mnw), "🇲🇲")
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nav_language)) },
        text = {
            Column {
                languages.forEach { (tag, label, emoji) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                val appLocale: LocaleListCompat = LocaleListCompat.forLanguageTags(tag)
                                AppCompatDelegate.setApplicationLocales(appLocale)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(emoji, modifier = Modifier.padding(end = 12.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
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
