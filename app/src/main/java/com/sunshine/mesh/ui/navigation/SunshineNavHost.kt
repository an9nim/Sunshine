package com.sunshine.mesh.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sunshine.mesh.AppGraph
import com.sunshine.mesh.BuildConfig
import com.sunshine.mesh.R
import com.sunshine.mesh.core.ids.PeerId
import com.sunshine.mesh.ui.chat.ChatScreen
import com.sunshine.mesh.ui.chat.ChatViewModel
import com.sunshine.mesh.ui.chatlist.ChatListScreen
import com.sunshine.mesh.ui.chatlist.ChatListViewModel
import com.sunshine.mesh.ui.common.ComingSoonScreen
import com.sunshine.mesh.ui.help.HelpScreen
import com.sunshine.mesh.ui.network.AndroidNetworkPermissions
import com.sunshine.mesh.ui.network.NetworkScreen
import com.sunshine.mesh.ui.network.NetworkViewModel
import com.sunshine.mesh.ui.profile.ProfileScreen
import com.sunshine.mesh.ui.profile.ProfileViewModel
import com.sunshine.mesh.ui.update.UpdateScreen
import com.sunshine.mesh.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.collect

@Composable
fun SunshineNavHost(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Route.Chats.path,
        modifier = modifier,
    ) {
        composable(Route.Chats.path) {
            val viewModel: ChatListViewModel = viewModel(
                factory = ChatListViewModel.factory(graph.chatRepository),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            ChatListScreen(
                state = state,
                onChatClick = { peerId -> navController.navigate(Route.Chat.of(peerId)) },
                onNetworkClick = { navController.navigate(Route.Network.path) },
                onProfileClick = { navController.navigate(Route.Profile.path) },
                onRetry = viewModel::retry,
            )
        }

        composable(
            route = Route.Chat.path,
            arguments = listOf(
                navArgument(Route.Chat.ARG_PEER_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val rawPeerId = entry.arguments?.getString(Route.Chat.ARG_PEER_ID).orEmpty()
            val peerId = remember(rawPeerId) {
                runCatching { PeerId.parse(rawPeerId) }.getOrNull()
            }
            if (peerId == null) {
                ComingSoonScreen(
                    title = stringResource(R.string.chat_not_found),
                    onBack = { navController.popBackStack() },
                )
            } else {
                val viewModel: ChatViewModel = viewModel(
                    factory = ChatViewModel.factory(
                        peerId = peerId,
                        dataSource = graph.chatRepository,
                        transport = graph.transportClient.coordinator,
                    ),
                )
                val state by viewModel.state.collectAsStateWithLifecycle()
                ChatScreen(
                    peerId = peerId,
                    state = state,
                    scrollEvents = viewModel.events,
                    onDraftChange = viewModel::onDraftChange,
                    onSend = viewModel::send,
                    onRetryConnect = viewModel::connect,
                    onBack = { navController.popBackStack() },
                    onNetworkClick = { navController.navigate(Route.Network.path) },
                )
            }
        }

        composable(Route.Network.path) {
            val context = LocalContext.current
            val permissions = remember(context) { AndroidNetworkPermissions(context) }
            val viewModel: NetworkViewModel = viewModel(
                factory = NetworkViewModel.factory(
                    control = graph.transportClient,
                    peerSource = graph.peerRepository,
                    permissions = permissions,
                ),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(viewModel) {
                viewModel.connectEvents.collect { peerId ->
                    navController.navigate(Route.Chat.of(peerId))
                }
            }
            NetworkScreen(
                state = state,
                onToggleNetwork = viewModel::onToggleNetwork,
                onRestartNetwork = viewModel::onRestartNetwork,
                onRefreshPermissions = viewModel::onRefreshPermissions,
                onPermissionsResult = viewModel::onPermissionsResult,
                onPeerClick = viewModel::onPeerClick,
                onBack = { navController.popBackStack() },
                onHelpClick = { navController.navigate(Route.Help.path) },
            )
        }

        composable(Route.Profile.path) {
            val viewModel: ProfileViewModel = viewModel(
                factory = ProfileViewModel.factory(
                    profileSource = graph.profileRepository,
                    peerActions = graph.peerRepository,
                    trustAcceptor = graph.transportClient.coordinator,
                ),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            ProfileScreen(
                state = state,
                onNicknameChange = viewModel::onNicknameChange,
                onSaveNickname = viewModel::saveNickname,
                onAcceptPeerKey = viewModel::acceptPeerKey,
                onVerifyPeer = viewModel::verifyPeer,
                onBack = { navController.popBackStack() },
                onHelpClick = { navController.navigate(Route.Help.path) },
                onUpdateClick = { navController.navigate(Route.Update.path) },
                onRetry = viewModel::retry,
            )
        }

        composable(Route.Help.path) {
            HelpScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.Update.path) {
            val viewModel: UpdateViewModel = viewModel(
                factory = UpdateViewModel.factory(
                    client = graph.updateClient,
                    currentVersion = BuildConfig.VERSION_NAME,
                ),
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            UpdateScreen(
                state = state,
                onCheckForUpdates = viewModel::checkForUpdates,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
