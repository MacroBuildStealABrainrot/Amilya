# Stand-in Android declarations (NOT the Android SDK)

These small files declare, from memory, the Android classes that the sandbox recorder code calls, with the same names, argument
lists and "must override" rules as the real SDK. They let the **Kotlin compiler on a PC** type-check the sandbox files
(`run_standin_compile.sh`). That finds mistakes such as a method that hides an inherited one, wrong argument types, missing
`override`, and misspelt names.

It is **not** an Android build: the stand-ins do nothing, can be wrong where my memory of the SDK is wrong, and the Compose
screens are not covered. A pass here is weaker evidence than a pass of `./gradlew assembleSandbox`.
