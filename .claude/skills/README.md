# Claude skills for Amilya

These folders hold instructions that Claude loads automatically when it works in this repository. They change how Claude works on the code. They do not change the app, the server, or any build.

| Skill | Source | What it does |
|---|---|---|
| systematic-debugging | obra/superpowers (MIT) | Find the real cause of an error before changing any code |
| verification-before-completion | obra/superpowers (MIT) | No "fixed" or "works" claim without fresh proof |

The superpowers MIT license is in `LICENSE-superpowers.txt`. Small Amilya notes were added at the end of each SKILL.md, under the heading "Amilya notes".

Left out on purpose: `find-polluter.sh` and `condition-based-waiting-example.ts` from systematic-debugging. They are written for npm/TypeScript projects and do not run on Amilya's Kotlin/Gradle app or its backend.
