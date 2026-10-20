package app.still.gallery.backup

import android.accounts.Account
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.still.gallery.BuildConfig
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first

/** No provider, media, backup job, HTTP client, or token persistence is exposed here. */
class DriveConnectionHolder(application: Application) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = application.getSharedPreferences("still-drive-connection", Context.MODE_PRIVATE)
    private val gateway = GoogleDriveGateway(application, BuildConfig.GOOGLE_WEB_CLIENT_ID)
    private val session = DriveConnectionSession(validDriveWebClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID), readSaved(), ::saveAccount)

    // KTX edit(commit=true) discards commit's Boolean; this save must detect failure.
    @SuppressLint("UseKtx")
    private fun saveAccount(saved: SavedDriveAccount?): Boolean {
        val editor = preferences.edit().clear()
        if (saved != null) {
            editor.putString("email", saved.display.email)
            editor.putString("name", saved.display.name)
            editor.putString("epoch", saved.epoch)
        }
        return editor.commit()
    }
    val controller = DriveConnectionController(session, gateway, scope)
    fun bind(activity: ComponentActivity) { gateway.bind(activity) }

    private fun readSaved(): SavedDriveAccount? = try {
        val email = preferences.getString("email", null)
        val name = preferences.getString("name", null)
        val epoch = preferences.getString("epoch", null)
        if (email.isNullOrBlank() || email.length > 320 || email.any { it.isWhitespace() } ||
            name == null || name.length > 512 || epoch == null || UUID.fromString(epoch).toString() != epoch) null
        else SavedDriveAccount(DriveAccountDisplay(email, name), epoch)
    } catch (_: RuntimeException) { null }

    override fun onCleared() { controller.dispose(); gateway.dispose(); scope.cancel() }
}

@Composable
fun rememberDriveConnection(): DriveConnectionHolder? {
    val activity = LocalActivity.current as? ComponentActivity ?: return null
    val holder = remember(activity) {
        ViewModelProvider(activity, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(DriveConnectionHolder(activity.application))!!
        })[DriveConnectionHolder::class.java]
    }
    // Activity ownership, not gallery ownership: a Google handoff must not unlock Still.
    LaunchedEffect(holder, activity) { holder.bind(activity) }
    return holder
}

private class GoogleDriveGateway(private val app: Context, private val webClientId: String) : DriveConnectionGateway {
    private val credentials = CredentialManager.create(app)
    private var activity = WeakReference<ComponentActivity>(null)
    private var credentialContext: MutableContextWrapper? = null
    private var pending: Resolution? = null
    private val activityObserver = LifecycleEventObserver { owner, event ->
        if (event == Lifecycle.Event.ON_DESTROY && activity.get() === owner) {
            pending?.launcher?.unregister(); pending?.launcher = null
            credentialContext?.baseContext = app
            activity.clear()
        }
    }

    fun bind(next: ComponentActivity) {
        if (activity.get() === next) return
        activity.get()?.lifecycle?.removeObserver(activityObserver)
        pending?.launcher?.unregister(); pending?.launcher = null
        activity = WeakReference(next)
        next.lifecycle.addObserver(activityObserver)
        credentialContext?.baseContext = next
        pending?.let { register(next, it) }
    }

    override suspend fun chooseAccount(): ChosenDriveAccount {
        if (!validDriveWebClientId(webClientId)) throw DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED)
        val foreground = foregroundActivity()
        val wrapper = MutableContextWrapper(foreground).also { credentialContext = it }
        try {
            // Explicit button flow: never silently choose the previously used account.
            val option = GetSignInWithGoogleOption.Builder(webClientId).setNonce(UUID.randomUUID().toString()).build()
            val response = credentials.getCredential(wrapper, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val custom = response.credential as? CustomCredential ?: throw DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED)
            if (custom.type !in setOf(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL))
                throw DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED)
            val google = GoogleIdTokenCredential.createFrom(custom.data)
            val email = google.email?.takeIf { it.isNotBlank() } ?: throw DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED)
            val subject = google.uniqueId.takeIf { it.isNotBlank() } ?: throw DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED)
            val binding = MessageDigest.getInstance("SHA-256").digest("still-google-subject-v1:$subject".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
            return ChosenDriveAccount(DriveAccountDisplay(email, google.displayName.orEmpty()), binding)
        } catch (_: NoCredentialException) { throw DriveConnectionFailure(DriveConnectionError.NO_ACCOUNT) }
        catch (_: GetCredentialCancellationException) { throw DriveConnectionFailure(DriveConnectionError.CANCELLED) }
        finally { wrapper.baseContext = app; if (credentialContext === wrapper) credentialContext = null }
    }

    override suspend fun authorize(account: ChosenDriveAccount): DriveGrant {
        val client = Identity.getAuthorizationClient(app)
        val request = driveAuthorizationRequestBuilder().setAccount(Account(account.display.email, "com.google")).build()
        var result = withTimeout(60_000) { client.authorize(request).awaitConnection() }
        if (result.hasResolution()) result = resolution(result)
        return DriveGrant(result.accessToken.orEmpty(), result.grantedScopes.toSet())
    }

    private class Resolution(val result: AuthorizationResult, val continuation: CancellableContinuation<AuthorizationResult>) {
        val key = "still-drive-${UUID.randomUUID()}"
        var launched = false
        var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    }

    private suspend fun foregroundActivity(): ComponentActivity {
        val owner = activity.get() ?: throw DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED)
        owner.lifecycle.currentStateFlow.first { it == Lifecycle.State.DESTROYED || it.isAtLeast(Lifecycle.State.STARTED) }
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED || activity.get() !== owner)
            throw DriveConnectionFailure(DriveConnectionError.CANCELLED)
        return owner
    }

    private suspend fun resolution(result: AuthorizationResult): AuthorizationResult {
        val owner = foregroundActivity()
        return suspendCancellableCoroutine { continuation ->
            val next = Resolution(result, continuation)
            pending = next
            continuation.invokeOnCancellation { if (pending === next) { pending = null; next.launcher?.unregister(); next.launcher = null } }
            register(owner, next)
        }
    }

    private fun register(owner: ComponentActivity, request: Resolution) {
        if (pending !== request || !request.continuation.isActive) return
        try {
            val launcher = owner.activityResultRegistry.register(request.key, ActivityResultContracts.StartIntentSenderForResult()) { received ->
                if (pending === request && request.continuation.isActive) {
                    pending = null; request.launcher?.unregister(); request.launcher = null
                    if (received.resultCode != Activity.RESULT_OK || received.data == null)
                        request.continuation.resumeWithException(DriveConnectionFailure(DriveConnectionError.PERMISSION_DENIED))
                    else try { request.continuation.resume(Identity.getAuthorizationClient(app).getAuthorizationResultFromIntent(received.data)) }
                    catch (_: Exception) { request.continuation.resumeWithException(DriveConnectionFailure(DriveConnectionError.PERMISSION_DENIED)) }
                }
            }
            if (pending !== request || !request.continuation.isActive) { launcher.unregister(); return }
            request.launcher = launcher
            if (!request.launched && pending === request && request.continuation.isActive) {
                request.launched = true
                try { launcher.launch(IntentSenderRequest.Builder(requireNotNull(request.result.pendingIntent).intentSender).build()) }
                catch (_: RuntimeException) {
                    pending = null; launcher.unregister(); request.launcher = null
                    request.continuation.resumeWithException(DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED))
                }
            }
        } catch (_: RuntimeException) {
            if (pending === request) pending = null
            request.launcher?.unregister(); request.launcher = null
            if (request.continuation.isActive) request.continuation.resumeWithException(DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED))
        }
    }

    fun dispose() {
        pending?.continuation?.cancel(); pending?.launcher?.unregister(); pending = null
        activity.get()?.lifecycle?.removeObserver(activityObserver); activity.clear()
        credentialContext?.baseContext = app; credentialContext = null
    }
}

private suspend fun <T> Task<T>.awaitConnection(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(DriveConnectionFailure(DriveConnectionError.SIGN_IN_FAILED)) }
    addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
}

/** Opt out of previously granted resource scopes before pinning the chosen account. */
internal fun driveAuthorizationRequestBuilder(): AuthorizationRequest.Builder = AuthorizationRequest.builder()
    .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
    .setOptOutIncludingGrantedScopes(true)
