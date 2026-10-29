package dev.janakhpon.monocr.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp
    )
}

@Composable
fun DashedUploadBox(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp) // 120dp matches iOS DashboardBox; grows with large text
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp), // Synchronized with iOS 16pt radius
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .border(
                    width = 0.5.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp) // Synchronized with Web p-6
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp), // Matches iOS icon container radius
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Icon(
                        Icons.Outlined.UploadFile,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp, // Raised to constitutional minimum
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

/**
 * A consent checkbox whose whole row, label included, is one toggle.
 *
 * The Checkbox carries no click handler of its own: the Row is the toggleable node, so
 * TalkBack reads the label and the checked state together and a tap on the text
 * toggles it, which a bare Checkbox beside a Column of text did neither of.
 */
@Composable
fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(modifier = Modifier.padding(start = 4.dp, top = 10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
