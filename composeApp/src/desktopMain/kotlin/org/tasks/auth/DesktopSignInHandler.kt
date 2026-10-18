package org.tasks.auth

import co.touchlab.kermit.Logger
import org.tasks.analytics.AnalyticsEvents
import org.tasks.analytics.Constants
import org.tasks.analytics.Reporting
import org.tasks.caldav.CaldavClientProvider
import org.tasks.compose.accounts.Platform
import org.tasks.data.UUIDHelper
import org.tasks.data.dao.CaldavDao
import org.tasks.data.entity.CaldavAccount
import org.tasks.googleapis.ProxyAuthProvider
import org.tasks.http.HttpException
import org.tasks.resources.getString
import org.tasks.security.KeyStoreEncryption
import tasks.kmp.generated.resources.Res
import tasks.kmp.generated.resources.google_tasks_permission_not_granted

class DesktopSignInHandler(
    private val oauthFlow: OAuthFlow,
    private val caldavDao: CaldavDao,
    private val encryption: KeyStoreEncryption,
    private val serverEnvironment: TasksServerEnvironment,
    private val caldavClientProvider: CaldavClientProvider,
    private val proxyAuthProvider: ProxyAuthProvider,
    private val reporting: Reporting,
    private val decsyncStores: org.tasks.decsync.DecsyncStoreProvider,
) : SignInHandler {

    override suspend fun signIn(platform: Platform, provider: OAuthProvider?, openUrl: (String) -> Unit) {
        if (platform == Platform.DECSYNC) {
            connectDecsyncFolder()
            return
        }
        val oauthProvider = provider ?: when (platform) {
            Platform.TASKS_ORG -> OAuthProvider.GOOGLE
            Platform.GOOGLE_TASKS -> OAuthProvider.GOOGLE_TASKS
            Platform.MICROSOFT -> OAuthProvider.MICROSOFT
            else -> throw UnsupportedOperationException("$platform not supported on desktop")
        }

        val authHeader = when (platform) {
            Platform.GOOGLE_TASKS -> proxyAuthProvider.getAuthHeader()
                ?: throw Exception("Sign in to tasks.org or link your desktop to use Google Tasks")
            else -> null
        }

        val result = oauthFlow.signIn(oauthProvider, authHeader = authHeader)

        when (platform) {
            Platform.GOOGLE_TASKS -> setupGoogleTasksAccount(result)
            Platform.MICROSOFT -> setupMicrosoftAccount(result)
            else -> try {
                setupTasksAccount(
                    oauthResult = result,
                    issuer = oauthProvider.issuer,
                    caldavUrl = serverEnvironment.caldavUrl,
                    caldavDao = caldavDao,
                    encryption = encryption,
                    provider = caldavClientProvider,
                )
            } catch (e: HttpException) {
                if (e.code == 402) {
                    throw PaymentRequiredException()
                }
                throw e
            }
        }
        Logger.i(TAG) { "Account created successfully" }
        bringAppToForeground()
    }

    private suspend fun connectDecsyncFolder() {
        val selected = kotlinx.coroutines.suspendCancellableCoroutine<java.io.File?> { continuation ->
            java.awt.EventQueue.invokeLater {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = "Choose the DecSync folder shared with your phone"
                    fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                val file = if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
                if (continuation.isActive) continuation.resumeWith(Result.success(file))
            }
        } ?: return
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val root = selected.toPath().toRealPath()
            val account = CaldavAccount(
                uuid = java.util.UUID.randomUUID().toString(),
                accountType = CaldavAccount.TYPE_DECSYNC,
                name = "DecSync",
                url = root.toString(),
            )
            decsyncStores.forAccount(account).collections()
            check(caldavDao.getAccounts(CaldavAccount.TYPE_DECSYNC).none { it.url == account.url }) {
                "This DecSync folder is already connected."
            }
            caldavDao.insert(account)
        }
    }

    private suspend fun setupGoogleTasksAccount(result: OAuthResult) {
        val email = result.idToken?.email
            ?: throw Exception("No email in Google Tasks OAuth response")
        val refreshToken = result.refreshToken
            ?: throw Exception("No refresh_token — consent may not have been granted")
        val grantedScopes = result.grantedScopes
        if (grantedScopes == null) {
            Logger.w(TAG) { "No scope field in token response — cannot verify granted scopes" }
        } else if (GOOGLE_TASKS_SCOPE !in grantedScopes) {
            Logger.e(TAG) { "Tasks scope missing. Granted scopes: $grantedScopes" }
            throw Exception(getString(Res.string.google_tasks_permission_not_granted))
        } else {
            Logger.i(TAG) { "Granted scopes: $grantedScopes" }
        }

        val tokenData = result.toOAuthTokenData(refreshToken)
        val encrypted = encryption.encrypt(tokenData.serialize())

        val existing = caldavDao.getAccount(CaldavAccount.TYPE_GOOGLE_TASKS, email)
        if (existing != null) {
            caldavDao.setPassword(existing.id, encrypted)
            caldavDao.setError(existing.id, "")
        } else {
            val account = CaldavAccount(
                accountType = CaldavAccount.TYPE_GOOGLE_TASKS,
                uuid = email,
                name = email,
                username = email,
                password = encrypted,
            )
            caldavDao.insert(account)
        }
    }

    private suspend fun setupMicrosoftAccount(result: OAuthResult) {
        val email = result.idToken?.email
        val preferredUsername = result.idToken?.preferredUsername
        val identifier = preferredUsername
            ?: email
            ?: throw Exception("No username in Microsoft OAuth response")
        val subject = result.idToken?.sub
            ?: throw Exception("No subject in Microsoft OAuth response")
        val refreshToken = result.refreshToken
            ?: throw Exception("No refresh_token — consent may not have been granted")

        val tokenData = result.toOAuthTokenData(refreshToken)
        val encrypted = encryption.encrypt(tokenData.serialize())

        val existing = caldavDao.getAccount(CaldavAccount.TYPE_MICROSOFT, subject)
            ?: preferredUsername?.let { caldavDao.getAccount(CaldavAccount.TYPE_MICROSOFT, it) }
            ?: email?.let { caldavDao.getAccount(CaldavAccount.TYPE_MICROSOFT, it) }
        if (existing != null) {
            caldavDao.setMicrosoftReauth(
                id = existing.id,
                username = subject,
                name = identifier,
                password = encrypted,
            )
        } else {
            val account = CaldavAccount(
                accountType = CaldavAccount.TYPE_MICROSOFT,
                uuid = UUIDHelper.newUUID(),
                name = identifier,
                username = subject,
                password = encrypted,
            )
            caldavDao.insert(account)
            reporting.logEvent(
                AnalyticsEvents.SYNC_ADD_ACCOUNT,
                AnalyticsEvents.PARAM_TYPE to Constants.SYNC_TYPE_MICROSOFT,
            )
        }
    }

    private fun bringAppToForeground() {
        java.awt.EventQueue.invokeLater {
            java.awt.Frame.getFrames().forEach { frame ->
                frame.toFront()
                frame.requestFocus()
            }
        }
    }

    companion object {
        private const val TAG = "DesktopSignInHandler"
    }
}
