package com.blobsey.hardwarepasskey

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import org.json.JSONObject

/**
 * A credential provider to be registered with the Android Credential Manager that handles
 * WebAuthn (passkey) requests
 */
class PasskeyCredentialProviderService : CredentialProviderService() {
    /**
     * Invoked when a calling app requests passkey creation via the Credential Manager, so the
     * Android system's bottom sheet can show this app as a save-target. Advertises a single
     * [CreateEntry] that launches [CreatePasskeyActivity] to perform the actual key generation
     */
    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<
            BeginCreateCredentialResponse,
            CreateCredentialException
            >
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialUnknownException("only passkeys supported"))
            return
        }

        val intent =
            Intent(this, CreatePasskeyActivity::class.java).apply {
                setPackage(packageName)
            }
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                CREATE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val createEntry =
            CreateEntry(
                accountName = "Hardware Passkey",
                pendingIntent = pendingIntent
            )

        callback.onResult(
            BeginCreateCredentialResponse
                .Builder()
                .addCreateEntry(createEntry)
                .build()
        )
    }

    companion object {
        private const val CREATE_REQUEST_CODE = 1
    }

    /**
     * Invoked when a calling app requests sign-in via the Credential Manager, so the Android
     * system's bottom sheet can list passkeys this app holds for the requesting rpId. Advertises
     * a [PublicKeyCredentialEntry] per match, which launch [GetPasskeyActivity] to produce the
     * assertion
     */
    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<
            BeginGetCredentialResponse,
            GetCredentialException
            >
    ) {
        val prefs = getSharedPreferences(WebAuthnCommon.SHARED_PREFS_KEY_PASSKEYS, MODE_PRIVATE)

        val passkeys: List<Pair<String, PasskeyData>> =
            prefs.all.entries
                .filter { WebAuthnCommon.isCredentialId(it.key) }
                .mapNotNull { entry ->
                    runCatching { PasskeyData.fromJsonString(entry.value as String) }
                        .getOrNull()
                        ?.let { entry.key to it }
                }

        val credentialEntries =
            request.beginGetCredentialOptions
                .filterIsInstance<BeginGetPublicKeyCredentialOption>()
                .flatMap { option ->
                    val rpId =
                        runCatching { JSONObject(option.requestJson).optString("rpId") }
                            .getOrNull()
                            ?.takeIf { it.isNotEmpty() }
                            ?: return@flatMap emptyList()

                    passkeys
                        .filter { (_, data) -> data.rpId == rpId }
                        .map { (keyAlias, data) ->
                            val intent =
                                Intent(this, GetPasskeyActivity::class.java).apply {
                                    setPackage(packageName)
                                    putExtra("keyAlias", keyAlias)
                                }
                            val pendingIntent =
                                PendingIntent.getActivity(
                                    this,
                                    keyAlias.hashCode(),
                                    intent,
                                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )

                            PublicKeyCredentialEntry(
                                context = this,
                                username = data.userName,
                                pendingIntent = pendingIntent,
                                beginGetPublicKeyCredentialOption = option,
                                displayName = data.userDisplayName
                            )
                        }
                }

        val responseBuilder = BeginGetCredentialResponse.Builder()
        credentialEntries.forEach { responseBuilder.addCredentialEntry(it) }
        callback.onResult(responseBuilder.build())
    }

    /**
     * Invoked when a calling app asks the Credential Manager to clear any cached credential state
     * (e.g. on user sign-out). No-op here because passkeys live only in the Android Keystore and
     * [android.content.SharedPreferences], i.e. nothing per-caller to forget
     */
    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        callback.onResult(null)
    }
}
