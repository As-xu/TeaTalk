package com.teatalk.xs.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.ui.characters.CharactersScreen
import com.teatalk.xs.ui.characters.CharactersViewModel

/** 底部三 Tab：聊天 / 角色 / 我的（对齐方案 §0 的 QQ/微信式骨架）。 */
@Composable
fun HomeScreen() {
    val navController = rememberNavController()
    val app = LocalContext.current.applicationContext as TeaTalkApp
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CHARACTERS,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Routes.CHAT) {
                Placeholder("聊天", "从「角色」里选一个角色开始聊天（D4 接入模型后可用）")
            }
            composable(Routes.CHARACTERS) {
                CharactersScreen(viewModel = viewModel(factory = CharactersViewModel.factory(app)))
            }
            composable(Routes.PROFILE) {
                Placeholder("我的", "设置 · 关于（占位，后续进设置页）")
            }
        }
    }
}

@Composable
private fun Placeholder(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private object Routes {
    const val CHAT = "chat"
    const val CHARACTERS = "characters"
    const val PROFILE = "profile"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.CHAT, "聊天", Icons.Filled.Email),
    Tab(Routes.CHARACTERS, "角色", Icons.Filled.Person),
    Tab(Routes.PROFILE, "我的", Icons.Filled.Settings),
)
