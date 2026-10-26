package dev.cueopen.app.wallet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletAgentPolicyStoreTest {
    private lateinit var context: Context
    private lateinit var store: WalletAgentPolicyStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("wallet_agent_policies", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = WalletAgentPolicyStore(context)
    }

    @Test
    fun transferPermissionDefaultsToDeniedAndIsScopedPerNetwork() {
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        assertFalse(store.isManualTransferEnabled(SolanaNetwork.MAINNET))
        assertFalse(store.isManualTransferEnabled(SolanaNetwork.DEVNET))
        assertTrue(
            store.manualTransferPolicy(SolanaNetwork.MAINNET).authorize(
                WalletAction.TRANSFER, 1L, recipient, 0L
            ) is PolicyDecision.Denied
        )

        store.setManualTransferEnabled(SolanaNetwork.MAINNET, true)
        assertTrue(store.isManualTransferEnabled(SolanaNetwork.MAINNET))
        assertFalse(store.isManualTransferEnabled(SolanaNetwork.DEVNET))
        assertTrue(
            store.manualTransferPolicy(SolanaNetwork.MAINNET).authorize(
                WalletAction.TRANSFER, 1L, recipient, 0L
            ) is PolicyDecision.RequiresUserApproval
        )
    }
}
