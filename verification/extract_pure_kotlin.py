"""Copies the few pure (non-Android) Kotlin declarations the unit tests need into one file, straight from the real sources."""
import re, sys
root, out = sys.argv[1], sys.argv[2]
base = root + '/android/app/src/main/java/com/woundcopilot/android/'
api = open(base + 'Api.kt', newline='').read().replace('\r', '').split('\n')
cf = open(base + 'ClinicianFeedback.kt', newline='').read().replace('\r', '').split('\n')
def at(lines, prefix): return next(i for i, l in enumerate(lines) if l.startswith(prefix))
i = at(api, 'internal fun needsCsrfBootstrap'); j = at(api, 'internal const val PRODUCTION_API_ORIGIN')
parts = ['\n'.join(api[i:i + 2]), api[at(api, 'internal val AUTH_ENTRY_PATHS')], api[at(api, 'internal fun isCsrfRejection')],
         api[at(api, 'class ApiException')], '\n'.join(api[j:j + 9])]
a = at(cf, 'internal fun canRetryRequest'); b = next(k for k, l in enumerate(cf) if k > a + 3 and l.startswith('@Composable'))
open(out, 'w').write('package com.woundcopilot.android\nimport java.io.IOException\n' + '\n'.join(parts) + '\n' + '\n'.join(cf[a:b]) + '\n')
