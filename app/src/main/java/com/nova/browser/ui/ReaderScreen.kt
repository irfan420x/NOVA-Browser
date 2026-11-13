package com.nova.browser.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nova.browser.reader.ArticleContent
import com.nova.browser.reader.ReaderExtractor

/**
 * Reader mode — a clean, ad-free article view. Deliberately dumb: it
 * renders the [ArticleContent] it is given (extracted by assets/reader.js
 * via ReaderExtractor) and nothing else. Close returns to the page.
 *
 * TEXT-ONLY BY DESIGN (B-6): images, links, captions, videos and embeds
 * are dropped by the extractor (reader.js STRIP_TAGS) — this screen
 * shows paragraphs only, and says so under the byline instead of
 * silently dropping them. Do not "fix" this by rendering article.html
 * without a separate review: raw HTML in a WebView re-introduces the
 * ads/trackers Reader mode exists to remove.
 *
 * Text size 50–200% (steps of 10) and a light/dark toggle live inside
 * this screen only — they do not change the global theme or text zoom.
 * Both survive rotation via rememberSaveable (B-6). [initialTextSize]
 * / [onTextSizeChange] let the coordinator persist the size across
 * articles in Settings (`reader_text_size`, default 100) — see the
 * W4 FIX report hook spec; without them the size resets per article.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    article: ArticleContent,
    onClose: () -> Unit,
    initialTextSize: Int = 100,
    onTextSizeChange: (Int) -> Unit = {}
) {
    var dark by rememberSaveable { mutableStateOf(true) }
    var textSize by rememberSaveable { mutableStateOf(initialTextSize.coerceIn(50, 200)) } // percent, 50..200
    BackHandler { onClose() }

    NovaTheme(darkTheme = dark) {
        // Glass Premium (v2.1): Reader has its own light/dark toggle, so it
        // paints its own gradient instead of relying on the app root's.
        GlassBackground(darkTheme = dark) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                GlassTopAppBar(
                    title = { Text("Reader") },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, "Close reader")
                        }
                    },
                    actions = {
                        IconButton(onClick = { dark = !dark }) {
                            Icon(
                                if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                                if (dark) "Light theme" else "Dark theme"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            textSize = (textSize - 10).coerceIn(50, 200)
                            onTextSizeChange(textSize)
                        },
                        enabled = textSize > 50
                    ) {
                        Icon(Icons.Filled.Remove, "Smaller text")
                    }
                    Text(
                        "$textSize%",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    IconButton(
                        onClick = {
                            textSize = (textSize + 10).coerceIn(50, 200)
                            onTextSizeChange(textSize)
                        },
                        enabled = textSize < 200
                    ) {
                        Icon(Icons.Filled.Add, "Larger text")
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp)
            ) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        article.title,
                        fontSize = (22 * textSize / 100f).sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (28 * textSize / 100f).sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${article.wordCount} words · ${ReaderExtractor.readingMinutes(article.wordCount)} min read",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Text only — images, links and captions are removed in Reader mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                }
                items(article.paragraphs) { paragraph ->
                    Text(
                        paragraph,
                        fontSize = (16 * textSize / 100f).sp,
                        lineHeight = (24 * textSize / 100f).sp
                    )
                    Spacer(Modifier.height(12.dp))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        }
    }
}
