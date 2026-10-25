"""Checks that need no installed packages: every Python file parses, and no async server code runs a blocking program directly.
Usage: python verification/python_static_checks.py   (run from the project root)"""
import ast, sys
from pathlib import Path
root = Path(__file__).resolve().parents[1]
files = [p for d in ('backend/server', 'backend/tests', 'backend/scripts', 'backend/migrations', 'test-tools', 'android/tools') for p in sorted((root / d).rglob('*.py'))]
bad = []
for p in files:
    try: ast.parse(p.read_text(encoding='utf-8'), str(p))
    except SyntaxError as e: bad.append((str(p), str(e)))
print(f'parsed {len(files)} Python files, syntax errors: {len(bad)}', *bad)
src = (root / 'backend/tests/test_audio_event_loop.py').read_text(encoding='utf-8')
keep = [n for n in ast.parse(src).body if (isinstance(n, ast.Assign) and any(getattr(t, 'id', '') in ('BLOCKING', 'SUBPROCESS_CALLS') for t in n.targets))
        or (isinstance(n, ast.FunctionDef) and n.name == 'direct_blocking_calls')]
ns = {'ast': ast}; exec(compile(ast.Module(keep, []), 'guard', 'exec'), ns)
offenders = {p.name: h for p in sorted((root / 'backend/server').glob('*.py')) if (h := ns['direct_blocking_calls'](p.read_text(encoding='utf-8')))}
print('async functions that run a blocking program directly:', offenders or 'none')
sys.exit(1 if bad or offenders else 0)
