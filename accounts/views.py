from urllib.parse import urlencode

from django.contrib.auth import login as auth_login, logout as auth_logout
from django.db import IntegrityError, transaction
from django.http import HttpResponseRedirect, JsonResponse
from django.shortcuts import render
from django.middleware.csrf import get_token
from django.urls import reverse
from django.utils.http import url_has_allowed_host_and_scheme
from django.views.decorators.cache import never_cache
from django.views.decorators.debug import sensitive_post_parameters
from django.views.decorators.http import require_GET, require_POST, require_http_methods

from . import legal_content
from .forms import (
    DUPLICATE_EMAIL, LEGAL_READ_REQUIRED, LEGAL_VERSION_CHANGED, LoginForm, SignupForm,
)
from .models import LegalAcceptance
from .throttle import (
    LOGIN_ATTEMPTS, SIGNUP_ATTEMPTS, THROTTLE_MESSAGE, WINDOW_SECONDS,
    attempt_key, is_blocked, record_attempt, reset_attempts,
)


LEGAL_OPENED_SESSION_KEY = 'accounts.legal_opened_versions'
LEGAL_READ_SESSION_KEY = 'accounts.legal_read_versions'


def legal_versions():
    return {'terms': legal_content.TERMS_VERSION, 'privacy': legal_content.PRIVACY_VERSION}


def legal_context(request):
    versions = legal_versions()
    read = request.session.get(LEGAL_READ_SESSION_KEY, {})
    return {
        'terms_version': versions['terms'], 'privacy_version': versions['privacy'],
        'terms_read': read.get('terms') == versions['terms'],
        'privacy_read': read.get('privacy') == versions['privacy'],
        'legal_read_url': reverse('accounts:legal_read'),
    }


def safe_next(request):
    candidate = request.POST.get('next') or request.GET.get('next', '')
    if candidate and url_has_allowed_host_and_scheme(
        candidate, allowed_hosts={request.get_host()}, require_https=request.is_secure()
    ):
        # As telas de autenticação não são destinos úteis depois de entrar.
        if candidate.split('?')[0].rstrip('/') not in {
            reverse('accounts:login').rstrip('/'), reverse('accounts:signup').rstrip('/'),
            reverse('accounts:logout').rstrip('/'),
        }:
            return candidate
    return ''


def destination(request):
    return safe_next(request) or reverse('studio:index')


def auth_page(request, template, form, *, status=200):
    context = {'form': form, 'next': safe_next(request), **legal_context(request)}
    response = render(request, template, context, status=status)
    if status == 429:
        response['Retry-After'] = str(WINDOW_SECONDS)
    return response


@never_cache
@sensitive_post_parameters('password')
@require_http_methods(['GET', 'POST'])
def login(request):
    if request.user.is_authenticated:
        return HttpResponseRedirect(destination(request))
    form = LoginForm(request, data=request.POST if request.method == 'POST' else None)
    if request.method == 'POST':
        key = attempt_key(request, 'login', request.POST.get('username', ''))
        if is_blocked(key, LOGIN_ATTEMPTS):
            # Limpeza dos campos valida e-mail/obrigatórios, sem comparar a senha.
            form = LoginForm(request, data=request.POST, blocked=True)
            form.add_error(None, THROTTLE_MESSAGE)
            return auth_page(request, 'accounts/login.html', form, status=429)
        if form.is_valid():
            auth_login(request, form.get_user(), backend='django.contrib.auth.backends.ModelBackend')
            request.session.set_expiry(30 * 24 * 60 * 60 if form.cleaned_data['remember_me'] else 0)
            reset_attempts(key)
            return HttpResponseRedirect(destination(request))
        record_attempt(key)
    return auth_page(request, 'accounts/login.html', form)


@never_cache
@sensitive_post_parameters('password1', 'password2')
@require_http_methods(['GET', 'POST'])
def signup(request):
    if request.user.is_authenticated:
        return HttpResponseRedirect(destination(request))
    form = SignupForm(data=request.POST if request.method == 'POST' else None)
    if request.method == 'POST':
        key = attempt_key(request, 'signup')
        if is_blocked(key, SIGNUP_ATTEMPTS):
            form.errors
            form.add_error(None, THROTTLE_MESSAGE)
            return auth_page(request, 'accounts/signup.html', form, status=429)
        record_attempt(key)
        valid = form.is_valid()
        receipts = request.session.get(LEGAL_READ_SESSION_KEY, {})
        for kind, version in legal_versions().items():
            if receipts.get(kind) != version:
                form.add_error(f'accept_{kind}', LEGAL_READ_REQUIRED)
                valid = False
        if valid:
            try:
                with transaction.atomic():
                    user = form.save()
                    LegalAcceptance.objects.create(
                        user=user,
                        terms_version=form.cleaned_data['terms_version'],
                        privacy_version=form.cleaned_data['privacy_version'],
                    )
            except IntegrityError:
                # A restrição no banco também protege dois cadastros simultâneos.
                form.add_error('email', DUPLICATE_EMAIL)
            else:
                request.session.pop(LEGAL_OPENED_SESSION_KEY, None)
                request.session.pop(LEGAL_READ_SESSION_KEY, None)
                auth_login(request, user, backend='django.contrib.auth.backends.ModelBackend')
                request.session.set_expiry(0)
                return HttpResponseRedirect(destination(request))
    return auth_page(request, 'accounts/signup.html', form)


@never_cache
@require_GET
def legal_document(request, kind):
    language = request.GET.get('lang', 'pt-BR')
    if language not in {'pt-BR', 'en', 'es'}:
        language = 'pt-BR'
    document = legal_content.get_document(kind, language)
    opened = request.session.get(LEGAL_OPENED_SESSION_KEY, {}).copy()
    opened[kind] = document['version']
    request.session[LEGAL_OPENED_SESSION_KEY] = opened
    read = request.session.get(LEGAL_READ_SESSION_KEY, {})
    return_to = 'google_signup' if request.GET.get('return_to') == 'google_signup' else 'signup'
    context = {
        'document': document, 'language': language, 'next': safe_next(request),
        'legal_read': read.get(kind) == document['version'], **legal_context(request),
        'legal_return_to': return_to, 'signup_return_url': reverse(f'accounts:{return_to}'),
    }
    template = 'accounts/legal_fragment.html' if request.GET.get('fragment') == '1' else 'accounts/legal_document.html'
    return render(request, template, context)


@never_cache
@require_POST
def legal_read(request):
    kind = request.POST.get('kind', '')
    versions = legal_versions()
    if kind not in versions:
        return JsonResponse({'error': 'Escolha um documento válido.'}, status=400)
    if request.POST.get('version') != versions[kind]:
        return JsonResponse({'error': LEGAL_VERSION_CHANGED}, status=400)
    opened = request.session.get(LEGAL_OPENED_SESSION_KEY, {})
    if opened.get(kind) != versions[kind]:
        return JsonResponse({'error': LEGAL_READ_REQUIRED}, status=400)
    read = request.session.get(LEGAL_READ_SESSION_KEY, {}).copy()
    read[kind] = versions[kind]
    request.session[LEGAL_READ_SESSION_KEY] = read
    return_to = request.POST.get('return_to')
    if return_to in {'signup', 'google_signup'} and 'application/json' not in request.headers.get('Accept', ''):
        next_url = safe_next(request)
        url = reverse(f'accounts:{return_to}')
        query = {}
        if next_url:
            query['next'] = next_url
        if request.POST.get('lang') in {'pt-BR', 'en', 'es'}:
            query['lang'] = request.POST['lang']
        if query:
            url += '?' + urlencode(query)
        return HttpResponseRedirect(url)
    return JsonResponse({'kind': kind, 'version': versions[kind], 'read': True})


@never_cache
@require_POST
def logout(request):
    auth_logout(request)
    return HttpResponseRedirect(reverse('studio:index'))


@never_cache
@require_GET
def session(request):
    data = {'authenticated': request.user.is_authenticated}
    if request.user.is_authenticated:
        data['user_key'] = str(request.user.storage_id)
        data['csrf_token'] = get_token(request)
    return JsonResponse(data)
