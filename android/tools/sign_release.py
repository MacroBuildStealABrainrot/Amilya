"""Sign an already-built APK using owner-supplied local tooling and credentials.

Password comes only from WCP_SIGN_PASSWORD. No credentials are stored in source.
"""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java', type=Path, required=True)
parser.add_argument('--apksigner', type=Path, required=True, help='SDK build-tools lib/apksigner.jar')
parser.add_argument('--keystore', type=Path, required=True)
parser.add_argument('--input', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if not os.environ.get('WCP_SIGN_PASSWORD'):
    raise SystemExit('Set WCP_SIGN_PASSWORD privately before signing.')
if args.input.resolve() == args.output.resolve():
    raise SystemExit('Use a separate output path to preserve the unsigned input.')
base = [str(args.java), '-jar', str(args.apksigner)]
subprocess.run(base + ['sign', '--ks', str(args.keystore), '--ks-key-alias', 'woundcopilot-release',
    '--ks-pass', 'env:WCP_SIGN_PASSWORD', '--key-pass', 'env:WCP_SIGN_PASSWORD',
    '--out', str(args.output), str(args.input)], check=True)
subprocess.run(base + ['verify', '--verbose', str(args.output)], check=True)
