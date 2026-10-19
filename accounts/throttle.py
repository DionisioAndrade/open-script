import hashlib

from django.core.cache import cache


WINDOW_SECONDS = 15 * 60
LOGIN_ATTEMPTS = 10
SIGNUP_ATTEMPTS = 5
THROTTLE_MESSAGE = 'Muitas tentativas. Aguarde 15 minutos e tente novamente.'


def attempt_key(request, action, email=''):
    # Não confia em IPs enviados pelo cliente nem guarda e-mail em texto no cache.
    identity = f"{request.META.get('REMOTE_ADDR', '')}|{email.casefold().strip()}"
    digest = hashlib.sha256(identity.encode()).hexdigest()
    return f'accounts:{action}:{digest}'


def is_blocked(key, limit):
    return cache.get(key, 0) >= limit


def record_attempt(key):
    cache.add(key, 0, timeout=WINDOW_SECONDS)
    try:
        cache.incr(key)
    except ValueError:
        # Outra requisição pode coincidir com a expiração do contador.
        cache.set(key, 1, timeout=WINDOW_SECONDS)


def reset_attempts(key):
    cache.delete(key)
