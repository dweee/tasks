package org.tasks.compose.settings

import kotlinx.coroutines.launch
import tasks.kmp.generated.resources.decsync_disconnect
import tasks.kmp.generated.resources.decsync_disconnect_message
import tasks.kmp.generated.resources.cancel
import org.tasks.themes.TasksIcons
import org.tasks.compose.components.SymbolIcon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.tasks.viewmodel.OpenTaskAccountViewModel
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.back
import tasks.kmp.generated.resources.settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenTaskAccountSettingsDetail(
    pane: OpenTaskAccountSettingsPane,
    onNavigateBack: () -> Unit,
) {
    val viewModel = koinViewModel<OpenTaskAccountViewModel>()
    val deleter = org.koin.compose.koinInject<org.tasks.service.TaskDeleter>()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmDisconnect by remember { mutableStateOf(false) }
    if (confirmDisconnect) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            text = { Text(stringResource(Res.string.decsync_disconnect_message)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDisconnect = false
                    scope.launch { deleter.delete(pane.account); onNavigateBack() }
                }) { Text(stringResource(Res.string.decsync_disconnect)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
    LaunchedEffect(pane.account.id) { viewModel.setAccount(pane.account) }
    val displayName by viewModel.displayName.collectAsState()
    val nameError by viewModel.nameError.collectAsState()
    val serverType by viewModel.serverType.collectAsState()
    val showServerType by viewModel.showServerType.collectAsState()
    val account by viewModel.account.collectAsState()
    val hasChanges by viewModel.hasChanges.collectAsState()
    var showDiscardDialog by remember { mutableStateOf(false) }
    val accountName = account?.name?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.settings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(accountName) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (hasChanges) {
                                showDiscardDialog = true
                            } else {
                                onNavigateBack()
                            }
                        }
                    ) {
                        SymbolIcon(
                            name = TasksIcons.ARROW_BACK,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OpenTaskAccountScreen(
                folder = account?.takeIf { it.isDecsync }?.url,
                onDisconnect = { confirmDisconnect = true },
                displayName = displayName,
                nameError = nameError,
                serverType = serverType,
                showServerType = showServerType,
                hasChanges = hasChanges,
                showDiscardDialog = showDiscardDialog,
                accountError = account?.error,
                onNameChange = viewModel::setDisplayName,
                onServerTypeChange = viewModel::setServerType,
                onSave = { viewModel.save(onNavigateBack) },
                onNavigateBack = onNavigateBack,
                onDiscardDialogChange = { showDiscardDialog = it },
            )
        }
    }
}
