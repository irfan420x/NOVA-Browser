package com.nova.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nova.browser.web.Suggestion
import com.nova.browser.web.SuggestionSource
import com.nova.browser.web.displayUrl

/**
 * Address-bar suggestion dropdown — dumb UI only. The coordinator feeds it
 * the merged list from SuggestionEngine (debounced 300 ms in the caller)
 * and handles the tap (search or URL via the existing resolveInput).
 * Returns nothing when the list is empty; the caller hides it on an empty
 * query or when the bar loses focus.
 *
 * The URL line shows host + path only ([displayUrl]) — the query string
 * (which can carry tracking tokens) is elided, never shown (L-W4-2).
 */
@Composable
fun AddressSuggestionsDropdown(
    suggestions: List<Suggestion>,
    onSuggestionClick: (Suggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return
    // Smoked glass: this dropdown floats over arbitrary web content, so it
    // uses the mostly-opaque floating fill — a 6–10% white fill over a
    // bright page would wash out and near-white text would go unreadable.
    GlassCard(
        modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        LazyColumn(Modifier.heightIn(max = 320.dp)) {
            items(suggestions, key = { it.source.name + "|" + (it.url ?: it.text) }) { suggestion ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onSuggestionClick(suggestion) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (suggestion.source) {
                            SuggestionSource.HISTORY -> Icons.Filled.History
                            SuggestionSource.BOOKMARK -> Icons.Filled.Star
                            SuggestionSource.SEARCH -> Icons.Filled.Search
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            suggestion.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (suggestion.url != null) {
                            Text(
                                displayUrl(suggestion.url),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when (suggestion.source) {
                            SuggestionSource.HISTORY -> "History"
                            SuggestionSource.BOOKMARK -> "Bookmark"
                            SuggestionSource.SEARCH -> "Search"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
