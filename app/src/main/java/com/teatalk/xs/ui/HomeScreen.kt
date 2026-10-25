package com.teatalk.xs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.UiText
import com.teatalk.xs.ui.character.CharacterDetailScreen
import com.teatalk.xs.ui.character.CharacterDetailViewModel
import com.teatalk.xs.ui.characters.CharactersScreen
import com.teatalk.xs.ui.characters.CharactersViewModel
import com.teatalk.xs.ui.chat.ChatScreen
import com.teatalk.xs.ui.chat.ChatViewModel
import com.teatalk.xs.ui.chats.ChatListScreen
import com.teatalk.xs.ui.chats.ChatListViewModel
import com.teatalk.xs.ui.components.AppBottomBar
import com.teatalk.xs.ui.components.TabItem
import com.teatalk.xs.ui.profile.ProfileScreen
import com.teatalk.xs.ui.providers.ProvidersScreen
import com.teatalk.xs.ui.providers.ProvidersViewModel
import com.teatalk.xs.ui.about.AboutScreen
import com.teatalk.xs.ui.worldbook.WorldBooksScreen
import com.teatalk.xs.ui.worldbook.WorldBooksViewModel

/**
 * 导航骨架：底部三 Tab（聊天/角色/我的）+ 两级全屏页面（角色详情 / 聊天）。
 *
 * 详情页与聊天页不显示底部栏（对齐 QQ/微信的"进入对话即全屏"手感）。
 * 首屏目的地动态：有过会话直接进「聊天」，新用户进「角色」。
 */
@Composable
fun HomeScreen(
    externalMessage: UiText? = null,
    onExternalMessageShown: () -> Unit = {},
    languageTag: String? = null,
    onChangeLanguage: (String?) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as TeaTalkApp
    val repository = app.container.characterRepository

    var startRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        startRoute = if (repository.hasAnyChat()) Routes.CHATS else Routes.CHARACTERS
    }

    val start = startRoute ?: return
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == null || currentRoute in TAB_ROUTES
    val snackbarHostState = remember { SnackbarHostState() }

    // UiText 在 UI 层解析成当前语言的字符串
    val externalText = externalMessage?.let { msg ->
        val raw = if (msg.args.isEmpty()) stringResource(msg.res)
        else stringResource(msg.res, *msg.args.toTypedArray())
        if (raw.length > 60) raw.take(57) + "…" else raw
    }
    LaunchedEffect(externalText) {
        val text = externalText ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        onExternalMessageShown()
    }

    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                AppBottomBar(
                    tabs = TABS,
                    currentKey = currentRoute ?: start,
                    onSelect = { key -> if (key != currentRoute) switchTab(key) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            NavHost(navController = navController, startDestination = start) {
                composable(Routes.CHATS) {
                    ChatListScreen(
                        viewModel = viewModel(factory = ChatListViewModel.factory(app)),
                        onOpenChat = { id -> navController.navigate("chat/$id") { launchSingleTop = true } },
                        onGoCharacters = { switchTab(Routes.CHARACTERS) },
                    )
                }
                composable(Routes.CHARACTERS) {
                    CharactersScreen(
                        viewModel = viewModel(factory = CharactersViewModel.factory(app)),
                        onOpen = { id -> navController.navigate("character/$id") },
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        currentLanguageTag = languageTag,
                        onLanguageChange = onChangeLanguage,
                        onOpenModelSettings = { navController.navigate(Routes.PROVIDERS) },
                        onOpenWorldBooks = { navController.navigate(Routes.WORLD_BOOKS) },
                        onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    )
                }
                composable(Routes.ABOUT) {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.PROVIDERS) {
                    ProvidersScreen(
                        viewModel = viewModel(factory = ProvidersViewModel.factory(app)),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.WORLD_BOOKS) {
                    WorldBooksScreen(
                        viewModel = viewModel(factory = WorldBooksViewModel.factory(app)),
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    route = Routes.CHARACTER_DETAIL,
                    arguments = listOf(navArgument(ARG_ID) { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong(ARG_ID) ?: 0L
                    CharacterDetailScreen(
                        viewModel = viewModel(
                            key = "detail-$id",
                            factory = CharacterDetailViewModel.factory(app, id),
                        ),
                        onBack = { navController.popBackStack() },
                        onStartChat = { navController.navigate("chat/$id") { launchSingleTop = true } },
                    )
                }
                composable(
                    route = Routes.CHAT,
                    arguments = listOf(navArgument(ARG_ID) { type = NavType.LongType }),
                ) { entry ->
                    val id = entry.arguments?.getLong(ARG_ID) ?: 0L
                    ChatScreen(
                        viewModel = viewModel(
                            key = "chat-$id",
                            factory = ChatViewModel.factory(app, id),
                        ),
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

private object Routes {
    const val CHATS = "chats"
    const val CHARACTERS = "characters"
    const val PROFILE = "profile"
    const val CHARACTER_DETAIL = "character/{id}"
    const val CHAT = "chat/{id}"
    const val WORLD_BOOKS = "worldbooks"
    const val PROVIDERS = "providers"
        const val ABOUT = "about"
}

private const val ARG_ID = "id"

private val TAB_ROUTES = setOf(Routes.CHATS, Routes.CHARACTERS, Routes.PROFILE)

private val TABS = listOf(
    TabItem(Routes.CHATS, R.string.tab_chats, R.drawable.ic_chat_bubble),
    TabItem(Routes.CHARACTERS, R.string.tab_characters, R.drawable.ic_people),
    TabItem(Routes.PROFILE, R.string.tab_profile, R.drawable.ic_person),
)
