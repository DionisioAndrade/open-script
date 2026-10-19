"""Opaque bearer tokens for clients with their own native interface."""

import hashlib
import re
import secrets
from datetime import timedelta

from django.conf import settings
from django.utils import timezone
from django.utils.crypto import constant_time_compare, salted_hmac

from api.common import ApiError

from .models import NativeAccessToken


TOKEN_PATTERN = re.compile(r'[A-Za-z0-9_-]{43}\Z')


def password_fingerprint(user):
    return salted_hmac(
        'openscript.native-access.password', user.password, algorithm='sha256',
    ).hexdigest()


def issue_access_token(user):
    secret = secrets.token_urlsafe(32)
    token = NativeAccessToken.objects.create(
        user=user,
        token_hash=hashlib.sha256(secret.encode('ascii')).hexdigest(),
        password_fingerprint=password_fingerprint(user),
        expires_at=timezone.now() + timedelta(days=settings.OPENSCRIPT_API_TOKEN_DAYS),
    )
    return secret, token


def authenticate_bearer(request):
    """Never authorize a native endpoint from a browser session cookie."""
    header = request.headers.get('Authorization', '')
    parts = header.split()
    if len(parts) != 2 or parts[0].lower() != 'bearer' or not TOKEN_PATTERN.fullmatch(parts[1]):
        raise ApiError(
            'unauthorized', 'Informe um token de acesso válido.', status=401,
            headers={'WWW-Authenticate': 'Bearer'},
        )
    digest = hashlib.sha256(parts[1].encode('ascii')).hexdigest()
    token = NativeAccessToken.objects.select_related('user').filter(token_hash=digest).first()
    if (
        token is None or token.revoked_at is not None or token.expires_at <= timezone.now()
        or not token.user.is_active
        or not constant_time_compare(token.password_fingerprint, password_fingerprint(token.user))
    ):
        raise ApiError(
            'unauthorized', 'O acesso expirou ou foi encerrado. Entre novamente.', status=401,
            headers={'WWW-Authenticate': 'Bearer'},
        )
    return token.user, token
