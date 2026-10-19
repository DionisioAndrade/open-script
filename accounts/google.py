"""Restricted Google OAuth endpoints and the explicit social signup form."""

from django import forms
from django.conf import settings
from django.contrib.auth.decorators import login_not_required
from django.core.exceptions import ImproperlyConfigured, MultipleObjectsReturned, PermissionDenied, ValidationError
from django.core.validators import validate_email
from django.db import IntegrityError
from django.http import HttpResponseRedirect
from django.shortcuts import render
from django.urls import reverse
from django.utils import timezone
from django.utils.decorators import method_decorator
from django.utils.http import url_has_allowed_host_and_scheme
from django.views.decorators.cache import never_cache
from django.views.decorators.csrf import csrf_protect
from django.views.decorators.http import require_GET, require_POST
from django.views.generic.edit import FormView

from allauth.socialaccount.adapter import get_adapter
from allauth.socialaccount.forms import SignupForm as SocialSignupForm
from allauth.socialaccount.internal.flows.signup import (
    clear_pending_signup, complete_social_signup, get_pending_signup,
)
from allauth.socialaccount.models import SocialApp
from allauth.socialaccount.providers.google.views import GoogleOAuth2Adapter
from allauth.socialaccount.providers.oauth2.client import OAuth2Error
from allauth.socialaccount.providers.oauth2.views import OAuth2CallbackView

from . import legal_content
from .forms import LEGAL_READ_REQUIRED, LEGAL_VERSION_CHANGED, PRIVACY_REQUIRED, TERMS_REQUIRED
from .models import User
from .throttle import SIGNUP_ATTEMPTS, THROTTLE_MESSAGE, WINDOW_SECONDS, attempt_key, is_blocked, record_attempt
from .views import LEGAL_OPENED_SESSION_KEY, LEGAL_READ_SESSION_KEY, legal_context, legal_versions


GOOGLE_UNAVAILABLE = 'A entrada com Google estará disponível em breve.'
GOOGLE_ERROR = 'Não foi possível entrar com Google. Tente novamente.'
GOOGLE_CANCELLED = 'Você cancelou a entrada com Google.'
GOOGLE_EMAIL_REQUIRED = 'O Google precisa confirmar seu e-mail para continuar.'
GOOGLE_DUPLICATE_EMAIL = 'Este e-mail já tem uma conta. Entre com sua senha e conecte o Google nas Configurações.'
GOOGLE_CONNECT_EMAIL = 'Use uma conta Google com o mesmo e-mail da sua conta OpenScript.'
GOOGLE_CONNECT_REQUIRED = 'Entre na sua conta antes de conectar o Google.'
GOOGLE_CONNECTED_OTHER = 'Esta conta Google já está conectada a outra conta.'
GOOGLE_INACTIVE = 'Não foi possível entrar com esta conta.'
GOOGLE_EXPIRED = 'Sua entrada com Google expirou. Tente novamente.'
GOOGLE_CREATE_FAILED = 'Não foi possível criar sua conta. Tente novamente.'
PENDING_GOOGLE_TIME_KEY = 'accounts.google_signup_issued_at'
PENDING_GOOGLE_MAX_AGE = 15 * 60


def google_enabled():
    return bool(
        getattr(settings, 'GOOGLE_CLIENT_ID', '').strip()
        and getattr(settings, 'GOOGLE_CLIENT_SECRET', '').strip()
        and getattr(settings, 'OPENSCRIPT_PUBLIC_URL', '').strip()
    )


def google_language(request, sociallogin=None):
    state_data = (sociallogin.state.get('data') or {}) if sociallogin else {}
    language = request.POST.get('lang') or request.GET.get('lang') or state_data.get('lang', 'pt-BR')
    return language if language in {'pt-BR', 'en', 'es'} else 'pt-BR'


def google_error(request, message=GOOGLE_ERROR, *, status=400, language=None):
    return render(request, 'accounts/google_error.html', {
        'google_error_message': message,
        'language': language or google_language(request),
    }, status=status)


def safe_google_next(request, *, connect=False):
    candidate = request.POST.get('next') or request.GET.get('next', '')
    # OAuth state carries only local destinations, independently of Host headers.
    if (
        candidate.startswith('/') and not candidate.startswith('//')
        and url_has_allowed_host_and_scheme(candidate, allowed_hosts=set())
        and not candidate.split('?')[0].startswith('/accounts/')
    ):
        return candidate
    return reverse('studio:index') + ('?settings=1' if connect else '')


def verified_google_email(sociallogin):
    if sociallogin.account.provider != 'google':
        raise PermissionDenied(GOOGLE_EMAIL_REQUIRED)
    uid = sociallogin.account.uid
    if not isinstance(uid, str) or not uid or uid != uid.strip() or len(uid) > 191:
        raise PermissionDenied(GOOGLE_EMAIL_REQUIRED)
    for address in sociallogin.email_addresses:
        if address.verified is True:
            email = User.objects.normalize_email(address.email)
            try:
                validate_email(email)
            except ValidationError:
                continue
            if len(email) <= 254:
                return email
    raise PermissionDenied(GOOGLE_EMAIL_REQUIRED)


def minimize_google_data(sociallogin):
    email = verified_google_email(sociallogin)
    data = sociallogin.account.extra_data
    name = str(data.get('name') or sociallogin.user.display_name or '').strip()[:100]
    sociallogin.account.extra_data = {'sub': sociallogin.account.uid, 'email': email, 'name': name}
    # Access tokens are neither persisted nor placed in the pending signup session.
    sociallogin.token = None
    return email


class OpenScriptGoogleOAuth2Adapter(GoogleOAuth2Adapter):
    def get_callback_url(self, request, app):
        return settings.OPENSCRIPT_PUBLIC_URL.rstrip('/') + '/accounts/google/login/callback/'

    def _decode_id_token(self, app, id_token):
        data = super()._decode_id_token(app, id_token)
        uid = data.get('sub')
        email = data.get('email')
        expires_at = data.get('exp')
        if (
            not isinstance(uid, str) or not uid or uid != uid.strip() or len(uid) > 191
            or not isinstance(email, str) or data.get('email_verified') is not True
            or not isinstance(expires_at, (int, float)) or isinstance(expires_at, bool)
            or expires_at <= timezone.now().timestamp()
            or (data.get('azp') is not None and data['azp'] != app.client_id)
        ):
            raise OAuth2Error(GOOGLE_EMAIL_REQUIRED)
        try:
            validate_email(email)
        except ValidationError as exc:
            raise OAuth2Error(GOOGLE_EMAIL_REQUIRED) from exc
        return data

    def complete_login(self, request, app, token, **kwargs):
        # Requiring OIDC prevents falling back to an identity without issuer/audience/expiry checks.
        if not kwargs.get('response', {}).get('id_token'):
            raise OAuth2Error(GOOGLE_EMAIL_REQUIRED)
        sociallogin = super().complete_login(request, app, token, **kwargs)
        minimize_google_data(sociallogin)
        return sociallogin


@never_cache
@login_not_required
@require_POST
@csrf_protect
def google_login(request):
    if not google_enabled():
        return google_error(request, GOOGLE_UNAVAILABLE, status=503)
    process = request.POST.get('process', 'login')
    if process not in {'login', 'connect'}:
        return google_error(request)
    if process == 'connect' and not request.user.is_authenticated:
        return google_error(request, GOOGLE_CONNECT_REQUIRED, status=401)
    if process == 'login' and request.user.is_authenticated:
        return HttpResponseRedirect(reverse('studio:index'))
    clear_pending_signup(request)
    request.session.pop(PENDING_GOOGLE_TIME_KEY, None)
    language = google_language(request)
    state_data = {'lang': language}
    if process == 'connect':
        state_data['connecting_user_id'] = request.user.pk
    try:
        provider = get_adapter(request).get_provider(request, 'google')
        return provider.redirect(
            request, process=process, next_url=safe_google_next(request, connect=process == 'connect'),
            data=state_data, scope=['openid', 'profile', 'email'],
            auth_params={'access_type': 'online', 'prompt': 'select_account'},
        )
    except (ImproperlyConfigured, MultipleObjectsReturned, SocialApp.DoesNotExist):
        return google_error(request, GOOGLE_UNAVAILABLE, status=503)


@never_cache
@login_not_required
@require_GET
def google_callback(request):
    if not google_enabled():
        return google_error(request, GOOGLE_UNAVAILABLE, status=503)
    try:
        return OAuth2CallbackView.adapter_view(OpenScriptGoogleOAuth2Adapter)(request)
    except (ImproperlyConfigured, MultipleObjectsReturned, SocialApp.DoesNotExist):
        return google_error(request, GOOGLE_UNAVAILABLE, status=503)


class GoogleSignupForm(SocialSignupForm):
    display_name = forms.CharField(
        label='Nome', max_length=100,
        error_messages={'required': 'Informe seu nome.', 'max_length': 'Use no máximo 100 caracteres para o nome.'},
    )
    accept_terms = forms.BooleanField(required=True, initial=False, error_messages={'required': TERMS_REQUIRED})
    accept_privacy = forms.BooleanField(required=True, initial=False, error_messages={'required': PRIVACY_REQUIRED})
    terms_version = forms.CharField(max_length=40, widget=forms.HiddenInput, error_messages={'required': LEGAL_VERSION_CHANGED})
    privacy_version = forms.CharField(max_length=40, widget=forms.HiddenInput, error_messages={'required': LEGAL_VERSION_CHANGED})

    def __init__(self, *args, request, **kwargs):
        self.request = request
        super().__init__(*args, **kwargs)
        self.initial.update({
            'display_name': self.sociallogin.user.display_name,
            'email': verified_google_email(self.sociallogin),
            'terms_version': legal_content.TERMS_VERSION,
            'privacy_version': legal_content.PRIVACY_VERSION,
        })
        self.fields['email'].disabled = True
        self.fields['email'].required = True
        self.account_already_exists = False

    def clean_email(self):
        email = verified_google_email(self.sociallogin)
        if User.objects.filter(email__iexact=email).exists():
            raise forms.ValidationError(GOOGLE_DUPLICATE_EMAIL, code='duplicate_email')
        return email

    def clean(self):
        cleaned_data = super().clean()
        receipts = self.request.session.get(LEGAL_READ_SESSION_KEY, {})
        for kind, version in legal_versions().items():
            if cleaned_data.get(f'{kind}_version') != version:
                self.add_error(f'{kind}_version', LEGAL_VERSION_CHANGED)
            if receipts.get(kind) != version:
                self.add_error(f'accept_{kind}', LEGAL_READ_REQUIRED)
        return cleaned_data


@method_decorator(never_cache, name='dispatch')
@method_decorator(login_not_required, name='dispatch')
@method_decorator(csrf_protect, name='dispatch')
class GoogleSignupView(FormView):
    template_name = 'accounts/google_signup.html'
    form_class = GoogleSignupForm
    http_method_names = ['get', 'post', 'head', 'options']

    def dispatch(self, request, *args, **kwargs):
        if request.user.is_authenticated:
            return HttpResponseRedirect(reverse('studio:index'))
        if not google_enabled():
            return google_error(request, GOOGLE_UNAVAILABLE, status=503)
        self.sociallogin = get_pending_signup(request)
        issued_at = request.session.get(PENDING_GOOGLE_TIME_KEY)
        if (
            not self.sociallogin or not isinstance(issued_at, (int, float))
            or not 0 <= timezone.now().timestamp() - issued_at <= PENDING_GOOGLE_MAX_AGE
        ):
            clear_pending_signup(request)
            request.session.pop(PENDING_GOOGLE_TIME_KEY, None)
            return google_error(request, GOOGLE_EXPIRED)
        try:
            verified_google_email(self.sociallogin)
        except PermissionDenied:
            clear_pending_signup(request)
            return google_error(request, GOOGLE_EMAIL_REQUIRED)
        if request.method == 'POST':
            key = attempt_key(request, 'signup')
            if is_blocked(key, SIGNUP_ATTEMPTS):
                form = self.get_form()
                form.is_valid()
                form.add_error(None, THROTTLE_MESSAGE)
                response = self.render_to_response(self.get_context_data(form=form), status=429)
                response['Retry-After'] = str(WINDOW_SECONDS)
                return response
            record_attempt(key)
        return super().dispatch(request, *args, **kwargs)

    def get_form_kwargs(self):
        return {**super().get_form_kwargs(), 'sociallogin': self.sociallogin, 'request': self.request}

    def get_context_data(self, **kwargs):
        context = super().get_context_data(**kwargs)
        context.update({
            **legal_context(self.request), 'next': self.sociallogin.get_redirect_url(self.request) or '/',
            'language': google_language(self.request, self.sociallogin),
            'legal_return_to': 'google_signup', 'signup_return_url': reverse('accounts:google_signup'),
        })
        return context

    def form_valid(self, form):
        try:
            form.save(self.request)
        except IntegrityError:
            message = GOOGLE_DUPLICATE_EMAIL if User.objects.filter(email__iexact=form.cleaned_data['email']).exists() else GOOGLE_CREATE_FAILED
            form.add_error(None, message)
            return self.form_invalid(form)
        clear_pending_signup(self.request)
        for key in (PENDING_GOOGLE_TIME_KEY, LEGAL_OPENED_SESSION_KEY, LEGAL_READ_SESSION_KEY):
            self.request.session.pop(key, None)
        response = complete_social_signup(self.request, self.sociallogin)
        self.request.session.set_expiry(0)
        return response


google_signup = GoogleSignupView.as_view()
