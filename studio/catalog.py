"""The web studio and native API consume the same export defaults."""

import json
from functools import lru_cache
from pathlib import Path


@lru_cache(maxsize=1)
def export_catalog():
    path = Path(__file__).resolve().parent / 'static' / 'studio' / 'export-catalog.json'
    return json.loads(path.read_text(encoding='utf-8'))
