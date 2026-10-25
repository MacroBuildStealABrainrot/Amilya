"""Fail-closed backup quota guard; no cloud API access or object deletion."""
import json
import sys

MAX_BYTES = 100_000_000
MAX_OBJECTS = 30


def check(inventory, additional_bytes=0):
    if inventory.get('nextPageToken'):
        raise ValueError('Incomplete backup inventory; cannot establish storage cap')
    objects = inventory.get('items', [])
    if not isinstance(objects, list) or len(objects) >= MAX_OBJECTS:
        raise ValueError('Backup object cap reached; preserve existing objects and request operator review')
    sizes = [int(item['size']) for item in objects]
    if any(size < 0 for size in sizes) or additional_bytes < 0:
        raise ValueError('Invalid backup size')
    total = sum(sizes)
    if total + additional_bytes >= MAX_BYTES:
        raise ValueError('Backup byte cap reached; preserve existing objects and request operator review')
    return {'existing_objects': len(objects), 'existing_bytes': total, 'projected_bytes': total + additional_bytes}


if __name__ == '__main__':
    try:
        result = check(json.load(sys.stdin), int(sys.argv[1]) if len(sys.argv) > 1 else 0)
    except (ValueError, KeyError, TypeError) as exc:
        raise SystemExit(str(exc)) from None
    print(json.dumps(result))
