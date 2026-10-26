# CueOpen Solana Wallet — threat model and release gates

**Status: implementation in progress; not approved for real funds.**

This document describes the minimum security boundary expected before wallet features are exposed to agents or mainnet use.

## Assets

- BIP-39 recovery phrase and derived signing seed.
- Android Keystore AES key and encrypted local secret file.
- Public address, network selection, transfer intents, approval state and transaction signatures.
- Agent permissions, recipient allowlists, spending limits and idempotency records.

## Trust boundaries

1. **Model / agent boundary:** the model is untrusted input. It may propose typed intents only. It must never receive a phrase, seed, private key, decrypted vault contents, or an API that signs arbitrary serialized transactions.
2. **Local policy boundary:** the wallet service must independently verify the authenticated agent ID, permission, selected network, recipient, amount, daily spend state and request freshness immediately before signing. UI checks alone do not count.
3. **Signing boundary:** only local code with access to the decrypted secret may sign. RPC, swap providers, analytics, crash reports and logs must never receive key material.
4. **Network boundary:** RPC and quote providers are untrusted. Validate response shape and transaction semantics; simulate before signing, show the final transaction summary, and verify the resulting signature/status.
5. **Storage boundary:** encrypted secret bytes live under `noBackupFilesDir`; the encryption key lives in Android Keystore. Missing key, corrupted ciphertext, failed authentication tag or metadata mismatch must fail closed and must never trigger automatic replacement of an existing secret.

## Threats and required controls

| Threat | Required control |
|---|---|
| Prompt injection asks an agent to transfer funds | Local per-agent policy gate; typed intent only; user confirmation unless narrowly scoped autonomous policy explicitly enabled |
| Agent spoofs another agent ID | Identity must come from trusted local execution context, not a model-supplied string |
| Amount precision/overflow changes transfer value | Parse decimal SOL with exact decimal arithmetic; convert to integer lamports; reject excess precision, nonpositive values and overflow |
| Recipient substitution | Validate 32-byte Solana public key; bind approval to exact network, recipient, amount, fees and intent digest |
| Replay/double execution | Durable idempotency key and transaction signature reconciliation; never blindly resend after ambiguous timeout |
| Daily limit bypass or race | Persist spend reservations atomically under a lock/transaction; count pending reservations; fail closed on unreadable state |
| RPC returns malicious or stale data | HTTPS, bounded timeouts, strict JSON parsing, simulation, blockhash expiry checks and confirmation polling |
| Device backup/transfer loses Keystore key | Device-bound ciphertext in no-backup storage; recovery phrase restores through an explicit flow, never by overwriting unknown data |
| Corrupted or tampered vault | AES-GCM authentication failure is fatal; preserve the file for diagnosis/recovery and do not regenerate keys silently |
| Phrase leaks through UI or diagnostics | No logging/analytics/LLM arguments; clear temporary buffers; hide sensitive UI from recents/screenshots where practical; avoid clipboard by default |
| Swap provider returns unexpected instructions | Allowlisted provider/programs; validate token mints, program IDs, destination, slippage and all instructions; reject arbitrary model-created transactions |

## Release gates

- Independent derivation vector and supported-wallet interoperability tests pass.
- Vault instrumentation tests cover round-trip, corrupted/truncated file, wrong/missing Keystore key, failed write, existing file, and metadata repair.
- Transfer/RPC tests cover exact amounts, insufficient funds/fees, simulation rejection, timeout, expired blockhash, duplicate request and confirmation reconciliation.
- Agent tests prove unauthorized tools are absent and local policy rejects forged IDs, disabled access, disallowed recipients, limits, stale approvals and replay.
- UI review confirms recovery phrase is never sent to the model or included in logs, crash reports, clipboard, saved state or navigation arguments.
- Review release artifact and dependency/security findings only after all above pass.

## Current limitations

The repository currently has local key derivation, an encrypted vault primitive, a read-only balance RPC client, a typed transfer intent and a deny-by-default policy object. It does **not** yet have a complete signing/send/confirmation lifecycle, a durable atomic spend ledger, a finished wallet UI, a validated swap flow, or executed tests. The policy object is not yet proof of enforcement unless called by the actual local signing boundary. Do not use this implementation to store or transfer real funds.
