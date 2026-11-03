package com.teatalk.xs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Snackbar
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
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.teatalk.core.log.TeaTalkLog
import com.teatalk.xs.log.PerfLog
import com.teatalk.xs.R
import com.teatalk.xs.TeaTalkApp
import com.teatalk.xs.data.UiText
import com.teatalk.xs.ui.character.CharacterDetailScreen
import com.teatalk.xs.ui.character.CharacterDetailViewModel
import com.teatalk.xs.ui.characters.CharactersScreen
import com.teatalk.xs.ui.characters.CharactersViewModel
import com.teatalk.xs.ui.chat.ChatScreen
import com.teatalk.xs.ui.chat.ChatViewModel
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import com.teatalk.xs.ui.quick.QuickRepliesScreen
import com.teatalk.xs.ui.quick.QuickRepliesViewModel
import com.teatalk.xs.ui.regex.RegexRulesScreen
import com.teatalk.xs.ui.regex.RegexRulesViewModel

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
    // 启动埋点：进程启动 → 首页首帧可组合（真机冷启动回归口径；am start -W 在该 ROM 不可用）。
    LaunchedEffect(Unit) {
        PerfLog.event(
            "app.home_ready",
            "ms" to SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime(),
        )
    }
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == null || currentRoute in TAB_ROUTES
    val snackbarHostState = remember { SnackbarHostState() }

    // UiText 在 UI 层解析成当前语言的字符串
    val externalText = externalMessage?.let { msg ->
        val raw = if (msg.args.isEmpty()) stringResource(msg.res)
        else stringResource(msg.res, *msg.args.toTypedArray())
        // 截断上限放宽：导入失败的原因需要完整可读，Snackbar 已支持 6 行
        if (raw.length > 240) raw.take(237) + "…" else raw
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
        snackbarHost = {
            // 自定义 Snackbar：导入失败的原因往往需要三行以上，Material3 默认样式只留两行
            SnackbarHost(snackbarHostState) { data ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = data.visuals.message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                        data.visuals.actionLabel?.let { label ->
                            TextButton(onClick = { data.performAction() }) {
                                Text(label)
                            }
                        }
                    }
                }
            }
        },
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
            NavHost(
                navController = navController,
                startDestination = start,
                // D1：路由切换用短淡入淡出，避免默认的整屏「跳」一下。
                enterTransition = { fadeIn(animationSpec = tween(180)) },
                exitTransition = { fadeOut(animationSpec = tween(120)) },
                popEnterTransition = { fadeIn(animationSpec = tween(180)) },
                popExitTransition = { fadeOut(animationSpec = tween(120)) },
            ) {
                composable(Routes.CHATS) {
                    ChatListScreen(
                        viewModel = viewModel(factory = ChatListViewModel.factory(app)),
                        onOpenChat = { id, messageId ->
                            val extra = if (messageId != null) "?messageId=$messageId" else ""
                            navController.navigate("chat/$id$extra") { launchSingleTop = true }
                        },
                        onGoCharacters = { switchTab(Routes.CHARACTERS) },
                    )
                }
                composable(Routes.CHARACTERS) {
                    CharactersScreen(
                        viewModel = viewModel(factory = CharactersViewModel.factory(app)),
                        onOpen = { id -> navController.navigate("character/$id") },
                        onOpenGroupChat = { id ->
                            navController.navigate("chat/$id") { launchSingleTop = true }
                        },
                    )
                }
                composable(Routes.PROFILE) {
                    ProfileScreen(
                        currentLanguageTag = languageTag,
                        onLanguageChange = onChangeLanguage,
                        onOpenModelSettings = { navController.navigate(Routes.PROVIDERS) },
                        onOpenWorldBooks = { navController.navigate(Routes.WORLD_BOOKS) },
                        onOpenRegex = { navController.navigate(Routes.REGEX_RULES) },
                        onOpenQuickReplies = { navController.navigate(Routes.QUICK_REPLIES) },
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
                composable(Routes.REGEX_RULES) {
                    RegexRulesScreen(
                        viewModel = viewModel(factory = RegexRulesViewModel.factory(app)),
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Routes.QUICK_REPLIES) {
                    QuickRepliesScreen(
                        viewModel = viewModel(factory = QuickRepliesViewModel.factory(app)),
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
                        onStartChat = {
                            scope.launch {
                                val conversationId = repository.ensureConversation(id) ?: return@launch
                                navController.navigate("chat/$conversationId") { launchSingleTop = true }
                            }
                        },
                        onOpenWorldBooks = { navController.navigate(Routes.WORLD_BOOKS) },
                    )
                }
                composable(
                    route = Routes.CHAT,
                    arguments = listOf(
                        navArgument(ARG_CONVERSATION_ID) { type = NavType.LongType },
                        navArgument(ARG_MESSAGE_ID) {
                            type = NavType.LongType
                            defaultValue = -1L
                        },
                    ),
                ) { entry ->
                    val conversationId = entry.arguments?.getLong(ARG_CONVERSATION_ID) ?: 0L
                    val focusMessageId = entry.arguments?.getLong(ARG_MESSAGE_ID)
                        ?.takeIf { it > 0L }
                    ChatScreen(
                        viewModel = viewModel(
                            key = "chat-$conversationId",
                            factory = ChatViewModel.factory(app, conversationId),
                        ),
                        onBack = { navController.popBackStack() },
                        focusMessageId = focusMessageId,
                        onOpenConversation = { other ->
                            navController.navigate("chat/$other") {
                                launchSingleTop = true
                                popUpTo(Routes.CHAT) { inclusive = true }
                            }
                        },
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
    const val CHAT = "chat/{conversationId}?messageId={messageId}"
    const val WORLD_BOOKS = "worldbooks"
    const val REGEX_RULES = "regexrules"
    const val QUICK_REPLIES = "quickreplies"
    const val PROVIDERS = "providers"
        const val ABOUT = "about"
}

private const val ARG_ID = "id"
private const val ARG_CONVERSATION_ID = "conversationId"
private const val ARG_MESSAGE_ID = "messageId"

private val TAB_ROUTES = setOf(Routes.CHATS, Routes.CHARACTERS, Routes.PROFILE)

private val TABS = listOf(
    TabItem(Routes.CHATS, R.string.tab_chats, R.drawable.ic_chat_bubble),
    TabItem(Routes.CHARACTERS, R.string.tab_characters, R.drawable.ic_people),
    TabItem(Routes.PROFILE, R.string.tab_profile, R.drawable.ic_person),
)
