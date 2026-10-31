# Private installer: how it works

* Source code stays PUBLIC. Public builds upload only logs and test reports, never an installer, never a key.
* The installer is built in a separate PRIVATE repository from the public source. Its two recipes are in `private-installer/.github/workflows/` (no secrets in them).
* The new signing key is made once, inside the private repository, and never leaves it. The retired public key (fingerprint B7:B2:FD:ED…96:91) is rejected by the build.
* The new app has its own app id `com.woundcopilot.android.isolated2` and its own name "Amilya Private Test", so it installs BESIDE the old "Amilya Isolated Test" and does not touch its data.
* The build refuses to run unless the public build and tests passed for the exact source version.
