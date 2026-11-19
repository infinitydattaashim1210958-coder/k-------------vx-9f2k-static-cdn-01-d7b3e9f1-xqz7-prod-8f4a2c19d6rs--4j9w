package com.cloudshelf.reader.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cloudshelf.reader.core.common.CloudShelfConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("CloudShelf", style = MaterialTheme.typography.headlineSmall)
            Text("Version 1.0.0", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Catalogue: ${CloudShelfConfig.DEFAULT_CATALOGUE_URL}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "Offline books are encrypted with AES-256-GCM. Keys are protected by Android Keystore. " +
                    "Online cache is temporary and bounded. Removing an offline copy never deletes your " +
                    "bookmarks, highlights, or reading progress.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}
