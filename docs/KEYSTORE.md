# Signing key — facts and backup

This file contains **no secrets**. The password lives only in `keystore.properties`, which is
gitignored and never committed.

## What exists, and only here

| File | Purpose | Mode |
|---|---|---|
| `echo-runner-release.jks` | the signing key | `600` |
| `keystore.properties` | store/key password, alias, path | `600` |

Both are gitignored (`*.jks`, `keystore.properties`) and exist **only on this machine**. Nothing
in the repo, the GitHub releases, or CI has a copy — CI deliberately builds debug only, so a
missing keystore cannot break it.

## Certificate

```
Alias                echorunner
Owner                CN=Echo Runner, OU=Games, O=Mu0810, C=IN
Created              11 Aug 2026
Valid until          03 Aug 2056
Key                  4096-bit RSA
Signature algorithm  SHA384withRSA
SHA-256 fingerprint  33:2F:1B:9F:42:61:2C:40:F0:FF:8D:5F:31:70:91:35:
                     D1:13:1B:6A:2F:3B:FD:A0:56:80:0A:C7:00:C5:EF:39
```

Keystore file SHA-256, to detect corruption or substitution:

```
d1e273509e2a6bc8c9748e96b555894f00a15894e382c3bde7731c8c00baad38
```

Verify at any time:

```bash
shasum -a 256 echo-runner-release.jks
keytool -list -v -keystore echo-runner-release.jks -alias echorunner   # prompts for the password
```

## Why this matters more than anything else in the repo

Every other artifact here is reproducible. Source can be rebuilt, APKs and AABs regenerated,
graphics re-rendered from their HTML sources. **This key cannot be regenerated.** Publish once
with it and it becomes the identity of the listing:

- **Lost** → you can request an upload-key reset from Google Play support. Recoverable, but slow,
  and only if the app already uses Play App Signing.
- **Leaked** → someone else can sign artifacts that Play and sideloaders accept as yours.

That asymmetry is why it is not stored anywhere automatic: an agent copying it to a convenient
location would be moving the one file whose whole value is that it lives in exactly one trusted
place.

## Backing it up

Back up **both files together** — the keystore is useless without its password, and the password
is useless without the keystore.

**Do:**
- A password manager entry with both files attached (1Password, Bitwarden, Keychain).
- An encrypted external disk or an encrypted archive on offline media.
- A printed copy of the password stored physically, separate from the disk holding the key.

**Do not:**
- A plain cloud-drive folder, a chat message, an email to yourself, or another git repo.
- The same disk as the working copy — that is not a backup, it is a second path to the same loss.

Command to gather them for an encrypted archive, run by hand:

```bash
cd /Users/hello/.kiro/crew/workspace/echo-runner
zip -e echo-runner-signing-backup.zip echo-runner-release.jks keystore.properties
# -e prompts for an archive password; use a DIFFERENT one from the keystore password
```

Then move the zip to the encrypted destination and delete it from the working directory.

## Play App Signing

On first upload, Play generates its own **app signing key** and re-signs every distributed
artifact. The key here becomes the **upload key**: it authenticates you to Play, not to devices.
That is what makes a lost upload key recoverable and a lost app signing key not — so opting into
Play App Signing is the safer path, and it is the default for new apps.
