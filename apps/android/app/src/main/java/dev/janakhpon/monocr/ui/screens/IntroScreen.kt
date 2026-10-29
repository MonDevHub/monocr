package dev.janakhpon.monocr.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.janakhpon.monocr.R
import dev.janakhpon.monocr.ui.components.LanguagePickerDialog
import kotlinx.coroutines.launch

@Composable
fun IntroScreen(
    onFinish: () -> Unit
) {
    val slides = listOf(
        IntroSlide(
            icon = Icons.Outlined.AutoAwesome,
            title = stringResource(R.string.intro_welcome_title),
            description = stringResource(R.string.intro_welcome_desc)
        ),
        IntroSlide(
            icon = Icons.Outlined.DocumentScanner,
            title = stringResource(R.string.intro_home_title),
            description = stringResource(R.string.intro_home_desc)
        ),
        IntroSlide(
            icon = Icons.Outlined.SettingsSuggest,
            title = stringResource(R.string.intro_docs_title),
            // No percentage here. This read "97.5%+ accuracy" until 2026-08-16;
            // that figure traces to no run, and the project's own README states
            // the model has no held-out evaluation on photographed pages.
            // AboutScreen.kt retracted the same class of claim already.
            description = stringResource(R.string.intro_docs_desc)
        ),
        IntroSlide(
            icon = Icons.Outlined.GroupWork,
            title = stringResource(R.string.intro_community_title),
            description = stringResource(R.string.intro_community_desc)
        )
    )

    val pagerState = rememberPagerState(pageCount = { slides.size })
    val scope = rememberCoroutineScope()

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch {
            pagerState.animateScrollToPage(pagerState.currentPage - 1)
        }
    }

    // The intro is the first thing a new user sees, before the drawer is reachable, so
    // the language can be changed from here.
    var showLanguagePicker by remember { mutableStateOf(false) }
    if (showLanguagePicker) {
        LanguagePickerDialog(onDismiss = { showLanguagePicker = false })
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showLanguagePicker = true }) {
                    Icon(Icons.Outlined.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.nav_language))
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Page Indicator
                Row(
                    Modifier
                        .height(32.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(slides.size) { iteration ->
                        val color = if (pagerState.currentPage == iteration) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(color)
                                .size(if (pagerState.currentPage == iteration) 12.dp else 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Button
                Box(modifier = Modifier.height(56.dp).fillMaxWidth()) {
                    Crossfade(
                        targetState = pagerState.currentPage == slides.size - 1,
                        label = "ButtonTransition"
                    ) { isLastSlide ->
                        if (isLastSlide) {
                            Button(
                                onClick = onFinish,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text(stringResource(R.string.intro_get_started), style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            Button(
                                onClick = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(stringResource(R.string.intro_continue), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) { page ->
            SlideContent(slides[page])
        }
    }
}

@Composable
private fun SlideContent(slide: IntroSlide) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = slide.icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = slide.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            letterSpacing = (-1).sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = slide.description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )
    }
}

data class IntroSlide(
    val icon: ImageVector,
    val title: String,
    val description: String
)
