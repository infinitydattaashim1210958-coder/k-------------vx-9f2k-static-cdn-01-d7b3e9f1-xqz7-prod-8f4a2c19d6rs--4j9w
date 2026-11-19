package com.cloudshelf.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cloudshelf.reader.feature.bookdetail.BookDetailScreen
import com.cloudshelf.reader.feature.categories.CategoriesScreen
import com.cloudshelf.reader.feature.home.HomeScreen
import com.cloudshelf.reader.feature.library.LibraryScreen
import com.cloudshelf.reader.feature.offline.OfflineScreen
import com.cloudshelf.reader.feature.reader.PdfReaderScreen
import com.cloudshelf.reader.feature.search.SearchScreen
import com.cloudshelf.reader.feature.settings.SettingsScreen
import com.cloudshelf.reader.ui.theme.CloudShelfTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CloudShelfTheme {
                CloudShelfRootScreen()
            }
        }
    }
}

private enum class TopDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    CATEGORIES("categories", "Categories", Icons.Default.Category),
    SEARCH("search", "Search", Icons.Default.Search),
    LIBRARY("library", "Library", Icons.Default.LibraryBooks),
    OFFLINE("offline", "Offline", Icons.Default.CloudOff),
    SETTINGS("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun CloudShelfRootScreen() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in TopDestination.entries.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(TopDestination.HOME.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label, maxLines = 1) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.HOME.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(TopDestination.HOME.route) {
                HomeScreen(onBookClick = { id -> navController.navigate("book/$id") })
            }
            composable(TopDestination.CATEGORIES.route) {
                CategoriesScreen()
            }
            composable(TopDestination.SEARCH.route) {
                SearchScreen(onBookClick = { id -> navController.navigate("book/$id") })
            }
            composable(TopDestination.LIBRARY.route) {
                LibraryScreen(onBookClick = { id -> navController.navigate("book/$id") })
            }
            composable(TopDestination.OFFLINE.route) {
                OfflineScreen(onBookClick = { id -> navController.navigate("reader/$id?offline=true") })
            }
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen()
            }
            composable(
                route = "book/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) {
                BookDetailScreen(
                    onRead = { id, offline ->
                        navController.navigate("reader/$id?offline=$offline")
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "reader/{bookId}?offline={offline}",
                arguments = listOf(
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument("offline") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) {
                PdfReaderScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
