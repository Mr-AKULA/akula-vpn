"""Materialize Git symlink placeholders for Windows native builds only."""
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'androidApp/src/main/jni'
count = 0
for path in root.rglob('*.h'):
    if not path.is_file() or path.stat().st_size > 300:
        continue
    try:
        value = path.read_text().strip()
    except (UnicodeError, OSError):
        continue
    if not value.startswith('../') or '\n' in value or '\r' in value:
        continue
    target = (path.parent / value).resolve()
    if target.is_relative_to(root) and target.is_file():
        path.write_bytes(target.read_bytes())
        count += 1
print(f'Materialized native header placeholders: {count}')
