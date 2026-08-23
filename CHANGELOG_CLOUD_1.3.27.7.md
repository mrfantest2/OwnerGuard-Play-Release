# OwnerGuard Cloud 1.3.27.7

- Adds an Administrator-only media gallery across all user vaults.
- Requires the Administrator escrow passphrase once per 30-minute idle session.
- Protects the session copy with AES-256-GCM bound to the PHP session and Cloud app secret.
- Supports image previews, video playback, owner/type filters, pagination, HTTP range responses, and audit events.
- Never stores decrypted media on the Cloud host.
