package com.nova.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.browser.web.PerSiteSettings
import com.nova.browser.web.defaultPerSiteSettings
import com.nova.browser.web.isSupportedUrl

/**
 * Per-site settings bottom sheet for the CURRENT site.
 *
 * Dumb by design: it renders the [PerSiteSettings] it is given and reports
 * every change through [onSettingsChange] — the coordinator applies the
 * change (Settings per-site sets, live WebView settings, reload) and hands
 * back the new state. The current host is shown at the top.
 *
 * NO cookies row (B-4 / M-W4-7): CookieManager.setAcceptCookie is global,
 * WebView has no per-host cookie API — a per-site cookies toggle could
 * never do what it promised, so it was removed from the model and the
 * sheet. Cookies are a global Settings item.
 *
 * Reload behaviour (B-3 / L-W4-1): the row subtitles do NOT promise a
 * reload. The note under the rows comes from
 * [PerSiteSettings.reloadNote] applied to [previousSettings] (the state
 * before the latest change — the coordinator keeps it): it appears only
 * when JavaScript/desktop actually changed. The coordinator reloads
 * ONCE (debounced) when needsReloadAfter is true; the "Reload page"
 * button stays manual-only and never fires automatically.
 *
 * Guard (B-11): the sheet refuses non-http(s) pages — pass the current
 * [pageUrl]; for about:blank / the home page it shows an unavailable
 * note instead of toggles. The coordinator must ALSO guard the trigger
 * with [isSupportedUrl] (the sheet is the second line of defence).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteSettingsSheet(
    host: String,
    settings: PerSiteSettings,
    onSettingsChange: (PerSiteSettings) -> Unit,
    onDismiss: () -> Unit,
    onReloadPage: () -> Unit,
    previousSettings: PerSiteSettings = settings,
    pageUrl: String = ""
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 24.dp)
        ) {
            Text(
                "Site settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                host,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            if (pageUrl.isNotBlank() && !isSupportedUrl(pageUrl)) {
                Text(
                    "Site settings are only available for web pages (http/https).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Done") }
                return@Column
            }

            SiteSettingRow(
                title = "JavaScript",
                subtitle = "Turning it off may break this site.",
                checked = settings.javaScriptEnabled,
                onChange = { onSettingsChange(settings.withJavaScript(it)) }
            )
            SiteSettingRow(
                title = "Desktop site",
                subtitle = "Show the desktop version of this site.",
                checked = settings.desktopMode,
                onChange = { onSettingsChange(settings.withDesktopMode(it)) }
            )
            SiteSettingRow(
                title = "Block ads on this site",
                subtitle = "When off, ads and trackers load on this site only. Applies on next load.",
                checked = settings.adBlockEnabled,
                onChange = { onSettingsChange(settings.withAdBlock(it)) }
            )

            settings.reloadNote(previousSettings)?.let { note ->
                Spacer(Modifier.height(8.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))
            Row {
                TextButton(
                    onClick = { onSettingsChange(defaultPerSiteSettings(settings.host)) },
                    modifier = Modifier.weight(1f)
                ) { Text("Reset to defaults") }
            }

            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) { Text("Done") }
                Button(
                    onClick = onReloadPage,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) { Text("Reload page") }
            }
        }
    }
}

@Composable
private fun SiteSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
