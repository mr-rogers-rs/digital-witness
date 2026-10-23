# Security and limits

Durable private capture files use authenticated encryption with keys in Android Keystore. Optional recipient encryption is separate from TLS transport and from device storage encryption. Protect both the phone unlock credentials and recipient private keys. Recovery journals can reconstruct interrupted recordings; service loss does not imply uninterrupted capture or silently start a new recording.

Integrity proofs bind segments and metadata, with optional external time evidence. They do not establish the truth of a scene, the identity of a recorder, absence of staging, or court admissibility. Sensor traces support analysis; they do not prove that footage is authentic rather than synthetic. Remote timestamp verification may require network access and pending anchors may remain unconfirmed.

Anonymous mode reduces specific metadata only. Visible subjects, voices, scene details, cloud account records, network traffic and release signing certificates may identify people. The PIN is a local UI protection, not a replacement for Android device security. Duress and wipe functions are destructive; flash storage does not guarantee physical erasure of every copy. Cloud copies, recipients' copies and backups can persist.

OEM power management, low storage, thermal conditions, permissions and network capacity can interrupt capture or delay uploads. No host test establishes long-duration reliability on every phone. Review [VALIDATION](VALIDATION.md) and qualify your exact devices and providers. No deployment history or previous account test is included as a qualification claim.
