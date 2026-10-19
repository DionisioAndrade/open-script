r"""Prepare a disposable authentication database for browser regressions.

Run: .\venv\Scripts\python.exe tests\prepare-auth.py
Then start Django on port 8001 with DJANGO_DATABASE_PATH set to the path printed.
This helper cannot migrate or seed the application's normal db.sqlite3.
"""
import os
from pathlib import Path
import sys


project = Path(__file__).resolve().parent.parent
database = project / "tests" / "screenshots" / "login-test.sqlite3"
configured = os.environ.get("DJANGO_DATABASE_PATH")
if configured and Path(configured).resolve() != database.resolve():
    raise SystemExit("Use the disposable tests/screenshots/login-test.sqlite3 database.")
database.parent.mkdir(parents=True, exist_ok=True)
os.environ["DJANGO_DATABASE_PATH"] = str(database)
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "app.settings")
sys.path.insert(0, str(project))

import django  # noqa: E402

django.setup()
from django.contrib.auth import get_user_model  # noqa: E402
from django.core.management import call_command  # noqa: E402

call_command("migrate", verbosity=0)
email = os.environ.get("TEST_EMAIL", "regression@open-script.test")
password = os.environ.get("TEST_PASSWORD", "Test-only-Camera!2026-Regression")
user, _ = get_user_model().objects.get_or_create(email=email, defaults={"display_name": "Browser regression"})
user.set_password(password)
user.save(update_fields=["password"])
print(f"Prepared isolated browser-test database: {database}")
