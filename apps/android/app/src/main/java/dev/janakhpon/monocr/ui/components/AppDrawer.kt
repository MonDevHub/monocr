package dev.janakhpon.monocr.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.janakhpon.monocr.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun AppDrawer(
    drawerState: DrawerState,
    scope: CoroutineScope,
    onNavigate: (String) -> Unit,
    currentRoute: String?
) {
    val haptic = LocalHapticFeedback.current
    val showLanguagePicker = remember { mutableStateOf(false) }

    if (showLanguagePicker.value) {
        LanguagePickerDialog(onDismiss = { showLanguagePicker.value = false })
    }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.background,
        drawerContentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 28.dp, vertical = 32.dp)
        ) {
            Text(
                "MonOCR",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            val context = androidx.compose.ui.platform.LocalContext.current
            // The fallback shows no number rather than a wrong one. It read
            // "1.0.1" against a versionName of 1.0.3, so on the one path where
            // the package manager fails this drawer stated a version that was
            // never shipped.
            val versionName = remember(context) {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
                } catch (e: Exception) {
                    "—"
                }
            }
            Text(
                "Production Suite · v$versionName",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -- MAIN ACTIONS --
        DrawerItem(
            label = stringResource(R.string.nav_home),
            icon = Icons.Outlined.Home,
            selected = currentRoute == "home",
            onClick = {
                onNavigate("home")
                scope.launch { drawerState.close() }
            }
        )

        DrawerItem(
            label = stringResource(R.string.nav_language),
            icon = Icons.Outlined.Translate,
            selected = false,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                showLanguagePicker.value = true
                scope.launch { drawerState.close() }
            }
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 28.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
        )

        // -- RESOURCES & COMMUNITY --
        DrawerItem(
            label = stringResource(R.string.nav_docs),
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            selected = currentRoute == "docs",
            onClick = {
                onNavigate("docs")
                scope.launch { drawerState.close() }
            }
        )

        DrawerItem(
            label = stringResource(R.string.nav_contribute),
            icon = Icons.Outlined.Handshake,
            selected = currentRoute == "contribute",
            onClick = {
                onNavigate("contribute")
                scope.launch { drawerState.close() }
            }
        )

        DrawerItem(
            label = stringResource(R.string.nav_feedback),
            icon = Icons.Outlined.Feedback,
            selected = currentRoute == "feedback",
            onClick = {
                onNavigate("feedback")
                scope.launch { drawerState.close() }
            }
        )

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 28.dp),
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
        )

        // -- APP INFO --
        DrawerItem(
            label = stringResource(R.string.nav_intro),
            icon = Icons.Outlined.AutoAwesome,
            selected = currentRoute == "intro",
            onClick = {
                onNavigate("intro")
                scope.launch { drawerState.close() }
            }
        )

        DrawerItem(
            label = stringResource(R.string.nav_privacy),
            icon = Icons.Outlined.Lock,
            selected = currentRoute == "privacy",
            onClick = {
                onNavigate("privacy")
                scope.launch { drawerState.close() }
            }
        )

        DrawerItem(
            label = stringResource(R.string.nav_about),
            icon = Icons.Outlined.Info,
            selected = currentRoute == "about",
            onClick = {
                onNavigate("about")
                scope.launch { drawerState.close() }
            }
        )

        Spacer(modifier = Modifier.weight(1f))
        
        @Suppress("SpellCheckingInspection")
        Text(
            "© 2026 Janakhpon · MIT License",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.padding(28.dp)
        )
    }
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { 
            Text(
                label, 
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            ) 
        },
        selected = selected,
        onClick = onClick,
        icon = { 
            Icon(
                icon, 
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            ) 
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
