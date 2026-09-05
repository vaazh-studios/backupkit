# Publishing (maintainers)

One-time setup, done by the account owner:

1. **Namespace.** https://central.sonatype.com → Namespaces → add `com.vocabloot` → copy the verification key → Porkbun → vocabloot.com → add a TXT record with that key (edit records, never delete) → Verify.
2. **Token.** Central Portal → View Account → Generate User Token. Two values.
3. **GitHub secrets** (repo → Settings → Secrets and variables → Actions):
   `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` (the token pair),
   `SIGNING_KEY_ID` (last 8 hex chars of the key fingerprint), `SIGNING_PASSWORD` (contents of `~/.backupkit-gpg-passphrase`),
   `GPG_KEY_CONTENTS` (contents of `~/.backupkit-signing-key.gpg`).

Each release:

1. Bump `version` in `backupkit/build.gradle.kts`, move the CHANGELOG "unreleased" block under the version, commit.
2. `git tag v0.1.0 && git push --tags`, then GitHub → Releases → Draft from the tag → Publish.
3. The Publish workflow uploads to the Central Portal. Deployments → wait for validation → **Publish**. Public in about 30 minutes; searchable in a few hours.
4. klibs.io lists it automatically within a month; to skip the wait, open an indexing request at https://github.com/JetBrains/klibs-io/issues/new/choose.
