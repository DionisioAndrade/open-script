"""Allauth policy for Google's verified identities and explicit legal acceptance."""

from django.core.exceptions import PermissionDenied
from django.db import transaction
from django.urls import reverse
from django.utils import timezone

from allauth.account.adapter import DefaultAccountAdapter
from allauth.core.exceptions import ImmediateHttpResponse
from allauth.socialaccount.adapter import DefaultSocialAccountAdapter
from allauth.socialaccount.providers.base import AuthError

from .models import LegalAcceptance, User


class OpenScriptAccountAdapter(DefaultAccountAdapter):
    def is_open_for_signup(self, request):
        # The project's own signup endpoint owns the consent requirement.
        return False

    def get_login_redirect_url(self, request):
        return reverse('studio:index')

    def get_signup_redirect_url(self, request):
        return reverse('studio:index')


class OpenScriptSocialAccountAdapter(DefaultSocialAccountAdapter):
    def get_provider(self, request, provider, client_id=None):
        instance = super().get_provider(request, provider, client_id=client_id)
        if instance.id == 'google':
            from .google import OpenScriptGoogleOAuth2Adapter
            instance.oauth2_adapter_class = OpenScriptGoogleOAuth2Adapter
        return instance

    def can_authenticate_by_email(self, login, email):
        # Google login uses its stable subject ID, never an email match alone.
        return False

    def is_auto_signup_allowed(self, request, sociallogin):
        return False

    def is_open_for_signup(self, request, sociallogin):
        return sociallogin.account.provider == 'google'

    def populate_user(self, request, sociallogin, data):
        user = sociallogin.user
        user.email = User.objects.normalize_email(data.get('email', ''))
        identity = sociallogin.account.extra_data
        user.display_name = str(identity.get('name') or ' '.join(
            part for part in (data.get('first_name'), data.get('last_name')) if part
        )).strip()[:100]
        return user

    def pre_social_login(self, request, sociallogin):
        from .google import (
            GOOGLE_CONNECT_EMAIL, GOOGLE_CONNECT_REQUIRED, GOOGLE_CONNECTED_OTHER,
            GOOGLE_DUPLICATE_EMAIL, GOOGLE_EMAIL_REQUIRED, GOOGLE_INACTIVE,
            PENDING_GOOGLE_TIME_KEY, google_error, google_language, minimize_google_data,
        )
        language = google_language(request, sociallogin)

        def stop(message):
            raise ImmediateHttpResponse(google_error(request, message, language=language))

        try:
            email = minimize_google_data(sociallogin)
        except PermissionDenied:
            stop(GOOGLE_EMAIL_REQUIRED)
        if sociallogin.is_existing and not sociallogin.user.is_active:
            stop(GOOGLE_INACTIVE)
        if sociallogin.state.get('process') == 'connect':
            if not request.user.is_authenticated:
                stop(GOOGLE_CONNECT_REQUIRED)
            if sociallogin.state.get('data', {}).get('connecting_user_id') != request.user.pk:
                stop(GOOGLE_CONNECT_REQUIRED)
            if email != User.objects.normalize_email(request.user.email):
                stop(GOOGLE_CONNECT_EMAIL)
            if sociallogin.is_existing and sociallogin.user.pk != request.user.pk:
                stop(GOOGLE_CONNECTED_OTHER)
        elif not sociallogin.is_existing:
            if User.objects.filter(email__iexact=email).exists():
                stop(GOOGLE_DUPLICATE_EMAIL)
            request.session[PENDING_GOOGLE_TIME_KEY] = timezone.now().timestamp()

    def save_user(self, request, sociallogin, form=None):
        from .google import GOOGLE_DUPLICATE_EMAIL, GoogleSignupForm, minimize_google_data
        from .views import LEGAL_READ_SESSION_KEY, legal_versions

        if not isinstance(form, GoogleSignupForm) or not form.is_valid():
            raise PermissionDenied('O cadastro exige leitura e aceite dos documentos.')
        receipts = request.session.get(LEGAL_READ_SESSION_KEY, {})
        for kind, version in legal_versions().items():
            if (
                not form.cleaned_data.get(f'accept_{kind}')
                or form.cleaned_data.get(f'{kind}_version') != version
                or receipts.get(kind) != version
            ):
                raise PermissionDenied('O cadastro exige leitura e aceite dos documentos.')
        email = minimize_google_data(sociallogin)
        if form.cleaned_data.get('email') != email:
            raise PermissionDenied(GOOGLE_DUPLICATE_EMAIL)
        sociallogin.user.display_name = form.cleaned_data['display_name']
        with transaction.atomic():
            user = super().save_user(request, sociallogin, form=form)
            LegalAcceptance.objects.create(
                user=user, terms_version=form.cleaned_data['terms_version'],
                privacy_version=form.cleaned_data['privacy_version'],
            )
        return user

    def get_connect_redirect_url(self, request, socialaccount):
        return reverse('studio:index') + '?settings=1'

    def on_authentication_error(self, request, provider, error=None, exception=None, extra_context=None):
        from .google import GOOGLE_CANCELLED, GOOGLE_ERROR, google_error, google_language
        language = google_language(request)
        state = (extra_context or {}).get('state') or {}
        if state.get('data', {}).get('lang') in {'pt-BR', 'en', 'es'}:
            language = state['data']['lang']
        message = GOOGLE_CANCELLED if error == AuthError.CANCELLED else GOOGLE_ERROR
        raise ImmediateHttpResponse(google_error(request, message, language=language))
