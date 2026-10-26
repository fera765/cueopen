package dev.cueopen.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SolanaNetworkTest {
    @Test
    fun knownRpcEndpointsIdentifyTheirCluster() {
        assertEquals(SolanaNetwork.MAINNET, SolanaNetwork.fromEndpoint(SolanaNetwork.MAINNET.rpcEndpoint))
        assertEquals(SolanaNetwork.DEVNET, SolanaNetwork.fromEndpoint(SolanaNetwork.DEVNET.rpcEndpoint))
        assertNull(SolanaNetwork.fromEndpoint("https://rpc.example.test"))
        assertEquals(SolanaNetwork.DEVNET, SolanaRpcClient(endpoint = SolanaNetwork.DEVNET.rpcEndpoint).network)
        assertNull(SolanaRpcClient(endpoint = "https://rpc.example.test").network)
    }

    @Test
    fun explorerLinksUseTheSelectedCluster() {
        val signature = "sampleSignature"
        assertEquals(
            "https://explorer.solana.com/tx/sampleSignature",
            SolanaNetwork.MAINNET.transactionExplorerUrl(signature)
        )
        assertEquals(
            "https://explorer.solana.com/tx/sampleSignature?cluster=devnet",
            SolanaNetwork.DEVNET.transactionExplorerUrl(signature)
        )
    }
}
