# CueOpen Solana Wallet — Security and Recovery Plan

## Recovery-first design

A wallet cannot guarantee recovery merely by storing its key in Android Keystore. Keystore keys are device-bound and may be lost when the phone is reset, replaced, or its secure hardware is unavailable. Therefore:

1. Generate a standards-compliant Solana recovery phrase using a vetted BIP-39 implementation and a cryptographically secure random source.
2. Derive the Solana signing key using the documented path `m/44'/501'/0'/0'`. Different wallet products can use different derivation conventions; an independent interoperability vector is required.
3. Require the user to verify an offline recovery backup before allowing transfers.
4. Never upload the recovery phrase or private key. Never put either in LLM context, prompts, chat history, crash reports, analytics, or logs.
5. Store a local encrypted copy under Android Keystore and use `noBackupFilesDir` for device-bound ciphertext.
6. On a new phone, restore from the offline recovery phrase. The new phone creates its own Android Keystore encryption key and encrypts the restored wallet locally.
7. Losing both the device and recovery phrase can permanently lose access to funds. Self-custody cannot promise zero-loss recovery.

## Agent boundary

- `AgentRunner.tools(...)` now registers only two read-only wallet tools (`wallet_get_address`, `wallet_get_balance`) when the selected agent has the matching persisted capability grant. The tool dispatcher rechecks the grant at execution time.
- The wallet screen now exposes per-agent switches for public-address and balance reads. Grants are stored locally under a hash-derived preference key and default to disabled.
- The normal chat and temporary multi-agent workers do not receive wallet tool schemas. These tools never return a seed, private key, recovery phrase, or signing material.
- **Transfer/sign/broadcast tools are not integrated with the agent runtime.** Per-agent financial transaction authorization, approval handoff, and signing-boundary identity verification remain unimplemented; the existing signer is still restricted to the manual wallet UI identity.
- The signer must check action, network, asset, amount, destination, fees, cumulative spend limits and duplicate request ID on every operation.
- Default policy is deny. Autonomous transfers require explicit opt-in, strict limits and an allowlisted destination; otherwise require user approval.
- Treat LLM output as untrusted input. The LLM may propose typed intents; deterministic local code validates and signs.
- Never sign arbitrary serialized transactions or arbitrary program instructions from an LLM.

## Transaction lifecycle

The repository now contains an initial SOL transfer path: typed SOL intent, address and amount validation, a SQLite idempotency/spending ledger, policy checks, local signing, simulation, persistence of signed bytes before broadcast, explicit broadcast confirmation and signature reconciliation. The UI has a manual transfer switch, per-transfer and daily limits, a recent ledger list and explorer links.

These are implementation features, not evidence that the entire wallet is release-ready. In particular, do not infer production readiness from the existence of these classes.

1. Build a typed intent (asset, amount in base units, recipient, allowed operation).
2. Validate network, destination, balance and fees.
3. Simulate before signing.
4. Require user approval unless a separately reviewed policy explicitly authorizes autonomous execution.
5. Sign locally and persist the exact signed bytes before any network broadcast.
6. Track signatures and blockhash expiry; never blindly rebuild or resend an ambiguous operation.
7. Show pending, confirmed/finalized and failed distinctly.
8. Persist non-secret audit records only.

## Release gates

- Run JVM and Android instrumentation tests; do not treat written tests as passing tests.
- Verify the independent cryptographic interoperability vector and restoration behavior.
- Verify Android Keystore encryption/decryption, tamper detection and recovery failure cases.
- Exercise transfer lifecycle with mocks and on Devnet; do not validate the initial implementation with real funds.
- Review concurrent spending reservations, ambiguous RPC outcomes, retries and policy revocation.
- Restore on a second physical device before mainnet funds.
- Review dependencies, Android permissions, crash/log handling and the threat model.
- Obtain an external security review before enabling autonomous mainnet transfers.
- Do not use production funds until all gates pass.

## Current implementation status (2026-10-26)

Implemented in the repository: local BIP-39/Solana key derivation and restore path; encrypted local secret vault; backup confirmation; public address and QR UI; read-only balance; mainnet/devnet selection; manual SOL transfer UI; typed transfer intent; deny-by-default policy; SQLite request ledger with spend reservations and idempotency; local transaction signing, simulation, signed-byte persistence, explicit broadcast confirmation and signature reconciliation; persisted per-agent read permissions for public address and balance; and AgentRunner tools gated by those grants. Tests have been authored for several components, including the new per-agent read grants.

Not verified yet: tests/build have not been run. The wallet must not be considered complete until the four TODO tasks pass their acceptance criteria. Agent-driven transfer request/approval integration, per-agent transaction authorization at the signing boundary, token swaps, and release validation remain incomplete. No release or mainnet readiness claim is justified until validation, review and the documented release gates are complete.
