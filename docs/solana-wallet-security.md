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
- The wallet screen exposes per-agent switches for public-address reads, balance reads and SOL transfer requests. All grants default to disabled and are stored locally under hash-derived preference keys. Each agent can have lower per-request/daily caps and an optional exact-match recipient allowlist; updates are validated and saved atomically, with hard caps of 0.05 SOL/request and 0.10 SOL/day.
- Authorized agent sessions can call `wallet_request_transfer` with only a recipient and decimal SOL amount. The agent identity is taken from the local session, never from model arguments; network is read from the persisted wallet setting and fails closed when missing/invalid.
- The request tool creates a typed intent in the SQLite ledger and never signs or broadcasts. Identical recent agent intents are deduplicated inside the SQLite authorization transaction to reduce duplicate requests during retries/concurrent tool calls. A wallet approval queue shows pending agent requests, lets the user reject them, or approve and sign locally. The final broadcast remains a separate explicit confirmation. Agent transfer policies are persisted, non-autonomous, limited to 0.05 SOL per request and 0.10 SOL per local day.
- The signer and broadcaster re-read the persisted policy for the request's actual agent identity and network; revoked permissions or stale/forged policy objects are rejected. Temporary multi-agent workers do not receive wallet tool schemas. No wallet tool returns a seed, private key, recovery phrase, or signing material.
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
5. Sign locally and persist the exact signed bytes before any network broadcast. Immediately before broadcast, validate the serialized format and verify that its embedded signature matches the signature recorded in the ledger; quarantine mismatches and do not submit them. This check binds the recorded signature to the submitted bytes, but is not a full semantic parser for transaction instructions.
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

Implemented in the repository: local BIP-39/Solana key derivation and restore path; encrypted local secret vault; backup confirmation; public address and QR UI; read-only balance; mainnet/devnet selection; manual SOL transfer UI; typed transfer intent; deny-by-default policy; SQLite request ledger with spend reservations and idempotency; local transaction signing, simulation, signed-byte persistence, explicit broadcast confirmation and signature reconciliation; per-agent read grants; and an agent SOL request → local approval queue → local signing → separate human broadcast confirmation path. Tests have been authored for several components, including per-agent read/transfer policies and intent validation.

Not verified yet: automated tests/build have not been run, per the current workflow decision that the user will perform installation and end-to-end manual checks. The wallet must not be considered release-ready until those checks and the documented release gates are satisfied. Token swaps are still not implemented; this phase supports SOL transfers only. Do not use real funds until the manual validation and security review are complete.


## Manual validation checklist for the current SOL/agent flow

Use **Devnet only** for the first pass and a disposable test wallet. Do not paste a recovery phrase into chat, screenshots, logs, or bug reports.

1. Create a wallet, write down the recovery phrase offline, confirm the backup, then verify the public address and QR show only the public address.
2. Switch to Devnet and verify the displayed balance is explicitly labeled Devnet. Temporarily disable network access and confirm errors do not silently fall back to Mainnet.
3. Open agent permissions. Verify all grants start disabled. Grant only address/balance reads to one test agent and confirm a different agent and normal chat cannot call those tools.
4. Enable “Solicitar transferência SOL” for the test agent. Ask that agent for a small Devnet transfer request to a valid test address. Verify the tool response says the request is pending and no transaction was signed or sent.
5. In the wallet approval queue, verify agent name, recipient, amount, and network. Cancel one request and confirm it never reaches signing.
6. Create another small Devnet request. Approve/sign it in the wallet, then cancel the final broadcast dialog and confirm it is still not submitted.
7. Repeat and explicitly confirm broadcast. Verify a signature is persisted, status can be reconciled, and the explorer link uses Devnet.
8. Revoke the agent transfer grant before approval and verify the pending request cannot be approved/signed. Revoke after signing and verify broadcast is blocked.
9. Attempt invalid addresses, zero/negative amounts, more than nine decimals, amounts above 0.05 SOL, cumulative agent spend above 0.10 SOL/day, and a recipient outside a configured allowlist; each should be rejected before signing.
10. Reopen the app with a signed-but-not-broadcast or UNKNOWN request and verify the agent request history lets you recover it without blindly rebuilding/resending.
11. Confirm neither recovery phrase nor private key appears in chat/tool output, agent history, logs, or the request ledger.

Record any failure with app version, network, request state, and public transaction signature only; never include secret material.
