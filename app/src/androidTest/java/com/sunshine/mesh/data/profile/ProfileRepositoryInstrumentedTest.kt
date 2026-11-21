package com.sunshine.mesh.data.profile

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sunshine.mesh.core.crypto.KeystoreSecret
import com.sunshine.mesh.core.ids.PeerId
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryInstrumentedTest {

    @Test
    @Ignore("requires real device Keystore")
    fun sealedIdentityOpensAndMatchesStoredPeerId() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(context.cacheDir, "profile-${UUID.randomUUID()}.preferences_pb")
        val alias = "sunshine.test.identity.${UUID.randomUUID()}"
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
        val secret = KeystoreSecret(
            alias = alias,
            aadLabel = "sunshine/test/identity/v1",
        )
        val store = ProfileStore(dataStore)
        val repository = ProfileRepository(store = store, secret = secret)

        try {
            val created = repository.createProfile("Тест")
            assertTrue(created is CreateProfileResult.Created)
            val profile = (created as CreateProfileResult.Created).profile

            val stored = requireNotNull(store.readStoredProfile())
            assertEquals(profile.peerId, stored.profile.peerId)
            assertEquals(PeerId.fromPublicKey(stored.publicKey), stored.profile.peerId)

            val privateKey = secret.open(stored.sealedPrivateKey)
            try {
                assertEquals(32, privateKey.size)
                val derivedPublicKey = X25519PrivateKeyParameters(privateKey, 0)
                    .generatePublicKey()
                    .encoded
                assertArrayEquals(stored.publicKey, derivedPublicKey)
            } finally {
                privateKey.fill(0)
            }
        } finally {
            scope.cancel()
            file.delete()
            deleteKeystoreAlias(alias)
        }
    }

    private fun deleteKeystoreAlias(alias: String) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
