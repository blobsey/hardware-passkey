package com.blobsey.passkeyholder

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.*

class PasskeyCredentialProviderService : CredentialProviderService() {

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse,
                CreateCredentialException>
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            callback.onError(CreateCredentialUnknownException("only passkeys supported"))
                return
        }

        val intent = Intent(this, CreatePasskeyActivity::class.java).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            CREATE_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val createEntry = CreateEntry(
            accountName = "Passkey Holder",
            pendingIntent = pendingIntent
        )

        callback.onResult(
            BeginCreateCredentialResponse.Builder()
                .addCreateEntry(createEntry)
                .build()
        )
    }

    companion object {
        private const val CREATE_REQUEST_CODE = 1
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse,
                GetCredentialException>
    ) {
        val responseBuilder = BeginGetCredentialResponse.Builder()
        val prefs = getSharedPreferences(WebAuthnCommon.SHARED_PREFS_KEY_PASSKEYS, MODE_PRIVATE)

        val credentialEntries = request.beginGetCredentialOptions
            .filterIsInstance<BeginGetPublicKeyCredentialOption>()
            .flatMap { option ->
                val rpId = try {
                    org.json.JSONObject(option.requestJson).optString("rpId")
                } catch (_: Exception) {
                    ""
                }

                if (rpId.isEmpty()) return@flatMap emptyList()

                prefs.all.entries
                    .filter { WebAuthnCommon.isCredentialId(it.key) }
                    .mapNotNull { entry ->
                        val passkeyData = try {
                            PasskeyData.fromJsonString(entry.value as String)
                        } catch (_: Exception) {
                            return@mapNotNull null // Ignore invalid JSON
                        }

                        if (passkeyData.rpId != rpId) return@mapNotNull null

                        val keyAlias = entry.key
                        val userName = passkeyData.userName
                        val userDisplayName = passkeyData.userDisplayName

                        val intent = Intent(this@PasskeyCredentialProviderService, GetPasskeyActivity::class.java).apply {
                            setPackage(packageName)
                            putExtra("keyAlias", keyAlias)
                        }
                        val pendingIntent = PendingIntent.getActivity(
                            this@PasskeyCredentialProviderService,
                            keyAlias.hashCode(),
                            intent,
                            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                        )

                        PublicKeyCredentialEntry(
                            context = this@PasskeyCredentialProviderService,
                            username = userName,
                            pendingIntent = pendingIntent,
                            beginGetPublicKeyCredentialOption = option,
                            displayName = userDisplayName
                        )
                    }
            }

        credentialEntries.forEach { responseBuilder.addCredentialEntry(it) }

        callback.onResult(responseBuilder.build())
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>
    ) {
        callback.onResult(null)
    }
}