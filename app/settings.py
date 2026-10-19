"""Django serve o estúdio web e a API nativa; a gravação é feita no dispositivo."""

import os
from pathlib import Path
from urllib.parse import urlsplit

from django.core.exceptions import ImproperlyConfigured
from dotenv import load_dotenv

BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / '.env', override=False)


DEBUG = os.getenv("DJANGO_DEBUG", "true").strip().lower() in {"1", "true", "yes"}
SECRET_KEY = os.getenv("DJANGO_SECRET_KEY")
if not SECRET_KEY:
    if not DEBUG:
        raise ImproperlyConfigured("Defina DJANGO_SECRET_KEY para executar sem DEBUG.")
    SECRET_KEY = "django-insecure-openscript-development-only"

ALLOWED_HOSTS = ["*"
    # host.strip()
    # for host in os.getenv("DJANGO_ALLOWED_HOSTS", "localhost,127.0.0.1,[::1]").split(",")
    # if host.strip()
]


INSTALLED_APPS = [
    'django.contrib.auth',
    'django.contrib.contenttypes',
    'django.contrib.sessions',
    'django.contrib.messages',
    'django.contrib.staticfiles',
    'allauth',
    'allauth.account',
    'allauth.socialaccount',
    'allauth.socialaccount.providers.google',
    'accounts.apps.AccountsConfig',
    'studio.apps.StudioConfig',
]

MIDDLEWARE = [
    'django.middleware.security.SecurityMiddleware',
    'django.contrib.sessions.middleware.SessionMiddleware',
    'django.middleware.common.CommonMiddleware',
    'django.middleware.csrf.CsrfViewMiddleware',
    'django.contrib.auth.middleware.AuthenticationMiddleware',
    'django.contrib.messages.middleware.MessageMiddleware',
    'allauth.account.middleware.AccountMiddleware',
    'django.middleware.clickjacking.XFrameOptionsMiddleware',
]

ROOT_URLCONF = 'app.urls'

TEMPLATES = [
    {
        'BACKEND': 'django.template.backends.django.DjangoTemplates',
        'DIRS': [],
        'APP_DIRS': True,
        'OPTIONS': {
            'context_processors': [
                'django.template.context_processors.request',
                'django.contrib.auth.context_processors.auth',
                'django.contrib.messages.context_processors.messages',
                'accounts.context_processors.authentication_options',
            ],
        },
    },
]

WSGI_APPLICATION = 'app.wsgi.application'
ASGI_APPLICATION = 'app.asgi.application'


# O banco guarda contas, sessões e dados enviados explicitamente pela API nativa.
# Vídeo e áudio são processados no dispositivo.
DATABASES = {
    'default': {
        'ENGINE': 'django.db.backends.sqlite3',
        'NAME': os.getenv('DJANGO_DATABASE_PATH', str(BASE_DIR / 'db.sqlite3')),
    },
}

AUTH_USER_MODEL = 'accounts.User'
AUTHENTICATION_BACKENDS = [
    'django.contrib.auth.backends.ModelBackend',
    'allauth.account.auth_backends.AuthenticationBackend',
]
LOGIN_URL = 'accounts:login'
LOGIN_REDIRECT_URL = 'studio:index'
LOGOUT_REDIRECT_URL = 'studio:index'

AUTH_PASSWORD_VALIDATORS = [
    {
        'NAME': 'django.contrib.auth.password_validation.UserAttributeSimilarityValidator',
        'OPTIONS': {'user_attributes': ('email', 'display_name', 'first_name', 'last_name')},
    },
    {'NAME': 'django.contrib.auth.password_validation.MinimumLengthValidator'},
    {'NAME': 'django.contrib.auth.password_validation.CommonPasswordValidator'},
    {'NAME': 'django.contrib.auth.password_validation.NumericPasswordValidator'},
]

SESSION_COOKIE_AGE = 30 * 24 * 60 * 60
OPENSCRIPT_API_TOKEN_DAYS = int(os.getenv('OPENSCRIPT_API_TOKEN_DAYS', '30'))
if not 1 <= OPENSCRIPT_API_TOKEN_DAYS <= 30:
    raise ImproperlyConfigured('OPENSCRIPT_API_TOKEN_DAYS deve estar entre 1 e 30.')
SESSION_COOKIE_HTTPONLY = True
SESSION_COOKIE_SAMESITE = 'Lax'
SESSION_COOKIE_SECURE = not DEBUG
CSRF_COOKIE_SAMESITE = 'Lax'
CSRF_COOKIE_SECURE = not DEBUG

# O ngrok termina HTTPS e repassa o protocolo original para o Django.
SECURE_PROXY_SSL_HEADER = ('HTTP_X_FORWARDED_PROTO', 'https')
CSRF_TRUSTED_ORIGINS = [
    origin.strip()
    for origin in os.getenv(
        'DJANGO_CSRF_TRUSTED_ORIGINS',
        'https://prefix-jalapeno-footboard.ngrok-free.dev',
    ).split(',')
    if origin.strip()
]


LANGUAGE_CODE = 'pt-br'

TIME_ZONE = 'America/Sao_Paulo'

USE_I18N = True

USE_TZ = True


STATIC_URL = '/static/'
STATIC_ROOT = BASE_DIR / 'staticfiles'

# Dados públicos de contato; podem ser preenchidos depois sem alterar os textos.
OPENSCRIPT_LEGAL_RESPONSIBLE = os.getenv('OPENSCRIPT_LEGAL_RESPONSIBLE', '').strip()
OPENSCRIPT_PRIVACY_EMAIL = os.getenv('OPENSCRIPT_PRIVACY_EMAIL', '').strip()

# Canonical origin prevents request Host headers from changing emailed links.
OPENSCRIPT_PUBLIC_URL = os.getenv('OPENSCRIPT_PUBLIC_URL', '').strip().rstrip('/')
if OPENSCRIPT_PUBLIC_URL:
    public_origin = urlsplit(OPENSCRIPT_PUBLIC_URL)
    local_http = public_origin.scheme == 'http' and public_origin.hostname in {'localhost', '127.0.0.1', '::1'}
    if (public_origin.scheme != 'https' and not local_http) or not public_origin.hostname or public_origin.username or public_origin.password or public_origin.path or public_origin.query or public_origin.fragment:
        raise ImproperlyConfigured('OPENSCRIPT_PUBLIC_URL deve ser uma origem HTTPS, ou localhost HTTP para testes.')

EMAIL_BACKEND = os.getenv('EMAIL_BACKEND', 'django.core.mail.backends.smtp.EmailBackend')
EMAIL_HOST = os.getenv('EMAIL_HOST', '').strip()
EMAIL_PORT = int(os.getenv('EMAIL_PORT', '587'))
EMAIL_HOST_USER = os.getenv('EMAIL_HOST_USER', '').strip()
EMAIL_HOST_PASSWORD = os.getenv('EMAIL_HOST_PASSWORD', '')
EMAIL_USE_TLS = os.getenv('EMAIL_USE_TLS', 'true').strip().lower() in {'1', 'true', 'yes'}
EMAIL_USE_SSL = os.getenv('EMAIL_USE_SSL', 'false').strip().lower() in {'1', 'true', 'yes'}
EMAIL_TIMEOUT = 10
DEFAULT_FROM_EMAIL = os.getenv('DEFAULT_FROM_EMAIL', '').strip()
PASSWORD_RESET_TIMEOUT = 3600
OPENSCRIPT_PASSWORD_RESET_ENABLED = bool(OPENSCRIPT_PUBLIC_URL and DEFAULT_FROM_EMAIL and (
    EMAIL_HOST or EMAIL_BACKEND != 'django.core.mail.backends.smtp.EmailBackend'
))

GOOGLE_CLIENT_ID = os.getenv('GOOGLE_CLIENT_ID', '').strip()
GOOGLE_CLIENT_SECRET = os.getenv('GOOGLE_CLIENT_SECRET', '').strip()
OPENSCRIPT_GOOGLE_ENABLED = bool(GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET and OPENSCRIPT_PUBLIC_URL)
ACCOUNT_USER_MODEL_USERNAME_FIELD = None
ACCOUNT_LOGIN_METHODS = {'email'}
ACCOUNT_SIGNUP_FIELDS = ['email*']
ACCOUNT_EMAIL_VERIFICATION = 'none'
ACCOUNT_EMAIL_NOTIFICATIONS = False
ACCOUNT_SESSION_REMEMBER = False
ACCOUNT_ADAPTER = 'accounts.adapters.OpenScriptAccountAdapter'
SOCIALACCOUNT_ADAPTER = 'accounts.adapters.OpenScriptSocialAccountAdapter'
SOCIALACCOUNT_FORMS = {'signup': 'accounts.google.GoogleSignupForm'}
SOCIALACCOUNT_AUTO_SIGNUP = False
SOCIALACCOUNT_LOGIN_ON_GET = False
SOCIALACCOUNT_EMAIL_AUTHENTICATION = False
SOCIALACCOUNT_EMAIL_AUTHENTICATION_AUTO_CONNECT = False
SOCIALACCOUNT_STORE_TOKENS = False
SOCIALACCOUNT_REQUESTS_TIMEOUT = 10
SOCIALACCOUNT_PROVIDERS = {
    'google': {
        'SCOPE': ['openid', 'profile', 'email'],
        'AUTH_PARAMS': {'access_type': 'online', 'prompt': 'select_account'},
        'OAUTH_PKCE_ENABLED': True,
        'APP': {'client_id': GOOGLE_CLIENT_ID, 'secret': GOOGLE_CLIENT_SECRET, 'key': ''},
    },
}
