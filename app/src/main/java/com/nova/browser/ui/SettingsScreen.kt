package com.nova.browser.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nova.browser.data.SearchEngine

/**
 * Settings: search engine, ad blocker, HTTPS-only, desktop mode, text size,
 * theme, custom filter rules, clear browsing data, default browser, about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: BrowserState,
    onRequestDefaultBrowser: () -> Unit,
    isDefaultBrowser: () -> Boolean
) {
    val settings = state.settings
    var darkTheme by remember { mutableStateOf(settings.darkTheme) }
    var adBlock by remember { mutableStateOf(settings.adBlockEnabled) }
    var ytSkipper by remember { mutableStateOf(settings.youtubeAdSkipper) }
    var httpsOnly by remember { mutableStateOf(settings.httpsOnly) }
    var desktopDefault by remember { mutableStateOf(settings.desktopModeDefault) }
    var textZoom by remember { mutableStateOf(settings.textZoom.toFloat()) }
    var engine by remember { mutableStateOf(settings.searchEngine) }
    var customRules by remember { mutableStateOf(settings.customFilterRules) }
    var rulesExpanded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // history|cookies|cache
    var isDefault by remember { mutableStateOf(isDefaultBrowser()) }

    // Version from the installed package — never hardcoded (the About
    // text still said "1.0 (versionCode 1)" after the bump to 1.1).
    val context = LocalContext.current
    val versionText = remember {
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
            else info.versionCode.toLong()
            "NOVA Browser ${info.versionName} (versionCode $code)"
        } catch (_: Exception) {
            "NOVA Browser"
        }
    }

    fun applyTextZoom() {
        val zoom = textZoom.toInt()
        settings.textZoom = zoom
        // Apply to every open tab immediately (no reload needed).
        state.tabs.forEach { tab ->
            try { tab.webView?.settings?.textZoom = zoom } catch (_: Exception) { }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { state.screen = BrowserScreen.BROWSER }) {
                        Icon(Icons.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            if (status != null) {
                Text(status!!, color = MaterialTheme.colorScheme.primary)
            }

            SettingsSection("Search") {
                Text("Default search engine", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                SearchEngine.values().forEach { candidate ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = engine == candidate,
                            onClick = {
                                engine = candidate
                                settings.searchEngine = candidate
                            }
                        )
                        Text(candidate.label)
                    }
                }
            }

            SettingsSection("Privacy") {
                SettingsSwitch(
                    title = "Block ads & trackers",
                    subtitle = "Built-in ad blocker (${state.adBlock.ruleCount} rules loaded)",
                    checked = adBlock,
                    onChange = {
                        adBlock = it
                        settings.adBlockEnabled = it
                    }
                )
                SettingsSwitch(
                    title = "Skip YouTube video ads",
                    subtitle = "Auto-skips pre-roll & mid-roll ads on YouTube (part of ad blocking)",
                    checked = ytSkipper,
                    onChange = {
                        ytSkipper = it
                        settings.youtubeAdSkipper = it
                    }
                )
                SettingsSwitch(
                    title = "HTTPS-only mode",
                    subtitle = "Upgrade http to https, except localhost and your local network",
                    checked = httpsOnly,
                    onChange = {
                        httpsOnly = it
                        settings.httpsOnly = it
                    }
                )
                SettingsSwitch(
                    title = "Desktop mode by default",
                    subtitle = "New tabs open as a desktop browser",
                    checked = desktopDefault,
                    onChange = {
                        desktopDefault = it
                        settings.desktopModeDefault = it
                    }
                )
            }

            SettingsSection("Appearance") {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Text size", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${textZoom.toInt()}% — applies to open tabs too",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = textZoom,
                        onValueChange = { textZoom = it },
                        onValueChangeFinished = { applyTextZoom() },
                        valueRange = 50f..200f,
                        steps = 14,
                        modifier = Modifier.weight(1f)
                    )
                }
                SettingsSwitch(
                    title = "Dark theme",
                    subtitle = "Nebula Dark Premium (light theme available)",
                    checked = darkTheme,
                    onChange = {
                        darkTheme = it
                        settings.darkTheme = it
                    }
                )
            }

            SettingsSection("Ad blocker") {
                Text(
                    "Custom rules — one per line, same syntax as the bundled list " +
                        "(||domain^ to block, @@||domain^ to allow).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (rulesExpanded) {
                    OutlinedTextField(
                        value = customRules,
                        onValueChange = { customRules = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        placeholder = { Text("||ads.example.com^\n@@||good.example.com^") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            settings.customFilterRules = customRules
                            state.reloadFilterLists()
                            rulesExpanded = false
                            status = "Custom rules saved — ${state.adBlock.ruleCount} rules loaded"
                        }) { Text("Save rules") }
                        OutlinedButton(onClick = {
                            customRules = settings.customFilterRules
                            rulesExpanded = false
                        }) { Text("Cancel") }
                    }
                } else {
                    OutlinedButton(onClick = { rulesExpanded = true }) {
                        Text(
                            if (customRules.isBlank()) "Add custom rules"
                            else "Edit custom rules (${customRules.lines().count { it.isNotBlank() }})"
                        )
                    }
                }
            }

            SettingsSection("Browsing data") {
                // In private mode there is no private history to clear,
                // and "Clear history" must never wipe the NORMAL history
                // from a private session (clearBrowsingData guards it too).
                if (!state.privateMode) {
                    SettingsAction("Clear history", "Remove every visited page") {
                        confirm = "history"
                    }
                }
                SettingsAction("Clear cookies & site data", "Signs you out of websites") {
                    confirm = "cookies"
                }
                SettingsAction("Clear cache", "Remove cached files and images") {
                    confirm = "cache"
                }
            }

            SettingsSection("Browser") {
                if (isDefault) {
                    Text(
                        "NOVA Browser is your default browser.",
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Button(onClick = {
                        onRequestDefaultBrowser()
                        isDefault = isDefaultBrowser()
                    }) { Text("Set as default browser") }
                }
                Spacer(Modifier.height(12.dp))
                SettingsAction("Error log", "Crashes and failed operations, with the exact error") {
                    state.screen = BrowserScreen.ERROR_LOG
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "$versionText\nBuilt-in ad blocker · No account · Your data stays on your phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    val confirmKind = confirm
    if (confirmKind != null) {
        val (title, text) = when (confirmKind) {
            "history" -> "Clear all history?" to
                "Every visited page will be removed from history. Bookmarks are not affected."
            "cookies" -> "Clear cookies & site data?" to
                "You will be signed out of websites, and saved site data will be removed."
            else -> "Clear cache?" to
                "Cached files and images will be removed. Pages will load slower the first time after this."
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    when (confirmKind) {
                        "history" -> state.clearBrowsingData(true, false, false)
                        "cookies" -> state.clearBrowsingData(false, true, false)
                        else -> state.clearBrowsingData(false, false, true)
                    }
                    status = "Done"
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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

@Composable
private fun SettingsAction(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
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
        IconButton(onClick = onClick) {
            Icon(Icons.Filled.ChevronRight, "Open")
        }
    }
}
