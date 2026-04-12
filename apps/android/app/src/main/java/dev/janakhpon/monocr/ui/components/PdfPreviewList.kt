package dev.janakhpon.monocr.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.janakhpon.monocr.util.PdfUtil

@Composable
fun PdfPreviewList(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pageCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(uri) {
        pageCount = PdfUtil.getPageCount(context, uri)
    }

    if (pageCount <= 0) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { pageCount })

    Box(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            pageSpacing = 16.dp,
            contentPadding = PaddingValues(horizontal = 0.dp),
            verticalAlignment = Alignment.Top
        ) { index ->
            PdfPageItem(uri = uri, pageIndex = index)
        }

        // Floating Page Indicator (LinkedIn style)
        if (pageCount > 1) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 2.dp
            ) {
                Text(
                    text = "${pagerState.currentPage + 1} / $pageCount",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PdfPageItem(uri: Uri, pageIndex: Int) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(uri, pageIndex) {
        isLoading = true
        bitmap = PdfUtil.renderPdfPageToBitmap(context, uri, pageIndex, scale = 1.5f)
        isLoading = false
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let { b ->
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth
                )
            } ?: if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }
        }
    }
}
