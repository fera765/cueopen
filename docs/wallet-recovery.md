# Solana wallet recovery and key derivation

## Scope

Fluwy currently derives one Solana account from an English BIP-39 recovery phrase. The implementation is not yet approved for real funds; the wallet UI, encrypted-vault verification, transaction signer, and security test suite remain incomplete.

## Derivation path

The fixed derivation path is:

`m/44'/501'/0'/0'`

This is the four-level hardened Ed25519 path commonly used by browser-based Solana wallets. The same BIP-39 phrase can produce a different address in wallets using a different path (for example, some hardware-wallet modes use `m/44'/501'/0'`). When restoring, compare the displayed public address with the address expected from the original wallet before funding or authorizing transactions.

## Local secret storage

- The recovery phrase is the backup needed to restore the wallet on another device.
- The app stores the derived 32-byte Ed25519 seed encrypted with AES-GCM using an Android Keystore key.
- Encrypted bytes are placed in the app's no-backup directory because the Keystore key is device-bound.
- Losing the device or its Keystore key therefore requires restoring from the recovery phrase.
- The app must fail closed when wallet files or metadata are inconsistent. It must not silently replace existing secret files.
- Recovery phrases and private key material must never be logged, sent to a model, analytics, crash reporting, or a remote service.

## Backup confirmation

Creation must remain a pending in-memory setup session until the user confirms the recovery phrase. After successful persistence, temporary key material is cleared. Any UI displaying the phrase must prevent accidental disclosure and clear its own copy when the screen is left.

## Verification status

An independent public test vector is present in `SolanaWalletKeyDerivationTest`:

- Phrase: the standard BIP-39 test phrase consisting of eleven `abandon` words followed by `about`.
- Path: `m/44'/501'/0'/0'`.
- Expected Solana address: `HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk`.

The test has been authored but has not yet been executed. This documentation is not a security audit and does not mean the wallet is ready for real funds.
