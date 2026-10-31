# Private installer: how it works

* Source code stays PUBLIC. Public builds upload only logs and test reports, never an installer, never a key. If the no-installer check fails, nothing is uploaded.
* The installer is built in a separate PRIVATE repository from the public source. Its two recipes are in `private-installer/.github/workflows/` (no secrets in them).
* The new signing key is made once by `make-signing-key`. It is never saved in the repository or its history. It comes as a one-day download, which you paste into ONE repository secret named `AMILYA_SIGNING_BUNDLE`, then delete.
* The retired public key (fingerprint B7:B2:FD:ED…96:91) is rejected by the build.
* The build accepts only a full commit code that is already merged into `test/isolated-safety` (reviewed), and only if the public build and tests passed for that exact commit.
* The new app has its own app id `com.woundcopilot.android.isolated2` and name "Amilya Private Test", so it installs BESIDE the old "Amilya Isolated Test" and does not touch its data.
* The build reads the server address the app is compiled to use from its settings file. Whether it really connects is proved only by signing in on the Samsung.
