"""Account endpoints for Android/iOS clients, independent of web sessions."""

from django import forms
from django.conf import settings
from django.core import signing
from django.db import IntegrityError, transaction
from django.utils import timezone

from api.common import ApiError, api_endpoint, check_fields, form_errors, response

from . import legal_content
from .api_auth import issue_access_token
from .forms import (
    DUPLICATE_EMAIL, LEGAL_READ_REQUIRED, LEGAL_VERSION_CHANGED, LoginForm, SignupForm,
)
from .models import LegalAcceptance, User
from .password_reset import (
    LANGUAGES, RESET_REQUEST_ATTEMPTS, RESET_UNAVAILABLE,
    RecoveryEmailForm, public_reset_origin, reset_email_enabled,
)
from .throttle import (
    LOGIN_ATTEMPTS, SIGNUP_ATTEMPTS, THROTTLE_MESSAGE, WINDOW_SECONDS,
    attempt_key, is_blocked, record_attempt, reset_attempts,
)


RECEIPT_MAX_AGE = 30 * 60
OPENED_RECEIPT_SALT = 'openscript.native.legal.opened'
READ_RECEIPT_SALT = 'openscript.native.legal.read'


def user_data(user):
    return {'id': str(user.storage_id), 'display_name': user.display_name, 'email': user.email}


def token_data(user, secret, token):
    return {
        'access_token': secret, 'token_type': 'Bearer',
        'expires_at': token.expires_at.isoformat(), 'user': user_data(user),
    }


def validation_error(fields):
    return ApiError('validation_error', 'Revise os campos informados.', fields=fields)


def require_strings(data, names):
    errors = {
        name: [{'message': 'Informe um texto.', 'code': 'invalid_type'}]
        for name in names if name in data and not isinstance(data[name], str)
    }
    if errors:
        raise validation_error(errors)


def require_booleans(data, names):
    errors = {
        name: [{'message': 'Informe verdadeiro ou falso.', 'code': 'invalid_type'}]
        for name in names if name in data and type(data[name]) is not bool
    }
    if errors:
        raise validation_error(errors)


def check_throttle(key, limit):
    if is_blocked(key, limit):
        raise ApiError(
            'rate_limited', THROTTLE_MESSAGE, status=429,
            headers={'Retry-After': str(WINDOW_SECONDS)},
        )


def mapped_form_errors(form):
    names = {'username': 'email', 'password1': 'password', 'password2': 'password_confirm'}
    return {names.get(name, name): errors for name, errors in form_errors(form).items()}


def legal_versions():
    return {'terms': legal_content.TERMS_VERSION, 'privacy': legal_content.PRIVACY_VERSION}


def valid_receipt(value, kind, version, salt):
    try:
        payload = signing.loads(value, salt=salt, max_age=RECEIPT_MAX_AGE)
    except (signing.BadSignature, TypeError, ValueError):
        return False
    return payload == {'kind': kind, 'version': version}


@api_endpoint(['POST'])
def login(request):
    data = request.api_data
    check_fields(data, {'email', 'password'})
    require_strings(data, {'email', 'password'})
    key = attempt_key(request, 'login', data.get('email', ''))
    check_throttle(key, LOGIN_ATTEMPTS)
    form = LoginForm(request, data={'username': data.get('email', ''), 'password': data.get('password', '')})
    if not form.is_valid():
        record_attempt(key)
        fields = mapped_form_errors(form)
        if any(error['code'] in {'invalid_login', 'inactive'} for error in fields.get('__all__', [])):
            raise ApiError('invalid_credentials', 'E-mail ou senha incorretos.', status=401)
        raise validation_error(fields)
    user = form.get_user()
    secret, token = issue_access_token(user)
    User.objects.filter(pk=user.pk).update(last_login=timezone.now())
    reset_attempts(key)
    return response(token_data(user, secret, token))


@api_endpoint(['POST'])
def signup(request):
    data = request.api_data
    allowed = {
        'display_name', 'email', 'password', 'password_confirm', 'accept_terms', 'accept_privacy',
        'terms_version', 'privacy_version', 'terms_receipt', 'privacy_receipt',
    }
    check_fields(data, allowed)
    require_strings(data, allowed - {'accept_terms', 'accept_privacy'})
    require_booleans(data, {'accept_terms', 'accept_privacy'})
    key = attempt_key(request, 'signup')
    check_throttle(key, SIGNUP_ATTEMPTS)
    record_attempt(key)
    form_data = {name: data[name] for name in allowed - {'password', 'password_confirm', 'terms_receipt', 'privacy_receipt'} if name in data}
    form_data.update(password1=data.get('password', ''), password2=data.get('password_confirm', ''))
    form = SignupForm(data=form_data)
    valid = form.is_valid()
    for kind, version in legal_versions().items():
        if not valid_receipt(data.get(f'{kind}_receipt', ''), kind, version, READ_RECEIPT_SALT):
            form.add_error(f'accept_{kind}', forms.ValidationError(LEGAL_READ_REQUIRED, code='read_required'))
            valid = False
    if not valid:
        raise validation_error(mapped_form_errors(form))
    try:
        with transaction.atomic():
            user = form.save()
            LegalAcceptance.objects.create(
                user=user, terms_version=form.cleaned_data['terms_version'],
                privacy_version=form.cleaned_data['privacy_version'],
            )
            secret, token = issue_access_token(user)
            User.objects.filter(pk=user.pk).update(last_login=timezone.now())
    except IntegrityError:
        form.add_error('email', forms.ValidationError(DUPLICATE_EMAIL, code='duplicate_email'))
        raise validation_error(mapped_form_errors(form))
    return response(token_data(user, secret, token), status=201)


@api_endpoint(['POST'], authenticated=True)
def logout(request):
    check_fields(request.api_data, set())
    request.api_token.revoked_at = timezone.now()
    request.api_token.save(update_fields=['revoked_at'])
    return response({'detail': 'Acesso encerrado.'})


@api_endpoint(['GET', 'PATCH'], authenticated=True)
def me(request):
    if request.method == 'PATCH':
        data = request.api_data
        check_fields(data, {'display_name'})
        require_strings(data, {'display_name'})
        if 'display_name' in data:
            form = forms.Form(data=data)
            form.fields['display_name'] = forms.CharField(max_length=100)
            if not form.is_valid():
                raise validation_error(form_errors(form))
            request.api_user.display_name = form.cleaned_data['display_name']
            request.api_user.save(update_fields=['display_name'])
    return response({'user': user_data(request.api_user)})


@api_endpoint(['GET'])
def legal_document(request, kind):
    versions = legal_versions()
    if kind not in versions:
        raise ApiError('not_found', 'Documento não encontrado.', status=404)
    language = request.GET.get('lang', 'pt-BR')
    document = legal_content.get_document(kind, language)
    opened = signing.dumps({'kind': kind, 'version': versions[kind]}, salt=OPENED_RECEIPT_SALT)
    return response({'document': document, 'opened_receipt': opened, 'receipt_expires_in': RECEIPT_MAX_AGE})


@api_endpoint(['POST'])
def legal_read(request):
    data = request.api_data
    check_fields(data, {'kind', 'version', 'opened_receipt', 'confirmed'})
    require_strings(data, {'kind', 'version', 'opened_receipt'})
    require_booleans(data, {'confirmed'})
    versions = legal_versions()
    kind = data.get('kind', '')
    if kind not in versions:
        raise validation_error({'kind': [{'message': 'Escolha um documento válido.', 'code': 'invalid'}]})
    version = versions[kind]
    if data.get('version') != version:
        raise validation_error({'version': [{'message': LEGAL_VERSION_CHANGED, 'code': 'stale_version'}]})
    if data.get('confirmed') is not True:
        raise validation_error({'confirmed': [{'message': 'Confirme a leitura do documento.', 'code': 'required'}]})
    if not valid_receipt(data.get('opened_receipt', ''), kind, version, OPENED_RECEIPT_SALT):
        raise validation_error({'opened_receipt': [{'message': LEGAL_READ_REQUIRED, 'code': 'read_required'}]})
    receipt = signing.dumps({'kind': kind, 'version': version}, salt=READ_RECEIPT_SALT)
    return response({'kind': kind, 'version': version, 'read_receipt': receipt, 'receipt_expires_in': RECEIPT_MAX_AGE})


@api_endpoint(['POST'])
def password_reset(request):
    data = request.api_data
    check_fields(data, {'email', 'lang'})
    require_strings(data, {'email', 'lang'})
    key = attempt_key(request, 'password-reset-request')
    check_throttle(key, RESET_REQUEST_ATTEMPTS)
    record_attempt(key)
    if not reset_email_enabled():
        raise ApiError('unavailable', RESET_UNAVAILABLE, status=503)
    form = RecoveryEmailForm(data={'email': data.get('email', '')})
    if not form.is_valid():
        raise validation_error(form_errors(form))
    public_url, parts = public_reset_origin()
    language = data.get('lang', 'pt-BR')
    language = language if language in LANGUAGES else 'pt-BR'
    form.save(
        domain_override=parts.netloc, use_https=parts.scheme == 'https',
        from_email=settings.DEFAULT_FROM_EMAIL, request=request,
        email_template_name='accounts/password_reset_email.txt',
        subject_template_name='accounts/password_reset_subject.txt',
        extra_email_context={'public_url': public_url, 'language': language},
    )
    return response({'detail': 'Se houver uma conta elegível, enviaremos as instruções por e-mail.'}, status=202)
