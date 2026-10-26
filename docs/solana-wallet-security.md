# CueOpen Solana Wallet — Security and Recovery Plan

## Recovery-first design

A wallet cannot guarantee recovery merely by storing its key in Android Keystore. Keystore keys are device-bound and may be lost when the phone is reset, replaced, or its secure hardware is unavailable. Therefore:

1. Generate a standards-compliant Solana recovery phrase using a vetted BIP-39 implementation and a cryptographically secure random source.
2. Derive the Solana signing key using the standard derivation path supported by the selected wallet ecosystem. Test interoperability with established Solana wallets before mainnet use.
3. Require the user to verify an offline recovery backup before allowing deposits or enabling transfers.
4. Never upload the recovery phrase or private key. Never put either in LLM context, prompts, chat history, crash reports, analytics, or logs.
5. Store a local encrypted copy under Android Keystore and use noBackupFilesDir for device-bound ciphertext.
6. On a new phone, install CueOpen and restore from the offline recovery phrase. The new phone generates a new Android Keystore encryption key and encrypts the restored wallet locally.
7. Losing both the device and recovery phrase can permanently lose access to funds. Self-custody cannot promise zero-loss recovery.

## Agent boundary

- The normal chat must not register or expose wallet tools.
- Only an explicitly wallet-enabled agent can invoke wallet tools.
- The signing boundary re-checks agent identity, permission, amount, destination, token mint/program, fee budget, daily limit, and duplicate request ID.
- Default policy is deny. Autonomous transfers require explicit opt-in, limits, and an allowlisted destination or per-transfer user approval.
- Treat LLM output as untrusted input. The LLM proposes intent; deterministic local code validates and signs.
- Never sign arbitrary serialized transactions or arbitrary program instructions from an LLM.

## Transaction lifecycle

1. Build a typed intent (asset, amount in base units, recipient, allowed operation).
2. Validate network, destination type, token mint, token program, balance, and fees.
3. Simulate before signing.
4. Require user approval unless policy explicitly authorizes autonomous execution.
5. Sign locally and broadcast through a configured RPC endpoint.
6. Track signature and blockhash expiry; never blindly rebuild/re-send with a fresh blockhash without checking whether the previous transaction landed.
7. Show pending/confirmed/finalized/failed distinctly. Use finalized for high-value withdrawals.
8. Persist non-secret audit records only.

## Release gates

- Unit tests for policy denial and boundaries.
- Cryptographic interoperability tests for generated/restored wallet addresses.
- Android Keystore encryption/decryption and tamper tests.
- Restore on a second physical device before mainnet funds.
- Fuzzing and transaction simulation tests.
- External security review before enabling autonomous mainnet transfers.
- Start with a small test transfer. No production funds until all gates pass.

## Current implementation status

WalletSecretVault is only an encrypted local-storage primitive and WalletAgentPolicy is only a deny-by-default policy gate. They are not a complete wallet, do not generate Solana keys, do not connect to RPC, and must not be presented as mainnet-ready until remaining release gates are implemented and tested.
