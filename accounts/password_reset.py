"""Password recovery with Django's one-use tokens and a fixed public origin."""

import logging
from urllib.parse import urlencode, urlsplit

from django import forms
from django.conf import settings
from django.contrib.auth import views as auth_views
from django.contrib.auth.forms import PasswordResetForm, SetPasswordForm
from django.core.mail import EmailMultiAlternatives
from django.core.exceptions import ValidationError
from django.core.validators import URLValidator
from django.http import HttpResponseRedirect
from django.template import loader
from django.urls import reverse, reverse_lazy
from django.utils.decorators import method_decorator
from django.views.decorators.cache import never_cache
from django.views.decorators.debug import sensitive_post_parameters

from .models import User
from .throttle import THROTTLE_MESSAGE, WINDOW_SECONDS, attempt_key, is_blocked, record_attempt


logger = logging.getLogger('accounts.password_reset')
RESET_REQUEST_ATTEMPTS = 5
RESET_CONFIRM_ATTEMPTS = 20
RESET_UNAVAILABLE = 'A recuperação de senha está temporariamente indisponível. Tente novamente mais tarde.'
LANGUAGES = {'pt-BR', 'en', 'es'}


def reset_language(request):
    language = request.POST.get('lang') or request.GET.get('lang', 'pt-BR')
    return language if language in LANGUAGES else 'pt-BR'


def public_reset_origin():
    """Do not allow a request Host or forwarded host to enter a recovery email."""
    value = str(getattr(settings, 'OPENSCRIPT_PUBLIC_URL', '')).strip().rstrip('/')
    if not value or any(character in value for character in '\r\n\t'):
        return None
    try:
        URLValidator(schemes=['http', 'https'])(value)
        parts = urlsplit(value)
        port = parts.port
    except (ValueError, ValidationError):
        return None
    if (
        parts.scheme not in {'http', 'https'} or not parts.netloc or not parts.hostname
        or parts.username is not None or parts.password is not None
        or parts.path or parts.query or parts.fragment
        or (port is not None and not 1 <= port <= 65535)
    ):
        return None
    if parts.scheme == 'http' and parts.hostname not in {'localhost', '127.0.0.1', '::1'}:
        return None
    return value, parts


def reset_email_enabled():
    return bool(getattr(settings, 'OPENSCRIPT_PASSWORD_RESET_ENABLED', False) and public_reset_origin())


class RecoveryEmailForm(PasswordResetForm):
    email = forms.EmailField(
        label='E-mail', max_length=254,
        widget=forms.EmailInput(attrs={
            'autocomplete': 'email', 'autocapitalize': 'none',
            'spellcheck': 'false', 'maxlength': 254,
        }),
        error_messages={
            'required': 'Informe seu e-mail.', 'invalid': 'Informe um e-mail válido.',
            'max_length': 'Use um e-mail com até 254 caracteres.',
        },
    )

    def clean_email(self):
        return User.objects.normalize_email(self.cleaned_data['email'])

    def send_mail(
        self, subject_template_name, email_template_name, context, from_email,
        to_email, html_email_template_name=None,
    ):
        # A delivery error gets the same visitor response as an unknown address.
        # Never record the body, token, recipient, or exception text in logs.
        try:
            subject = ''.join(loader.render_to_string(subject_template_name, context).splitlines())
            body = loader.render_to_string(email_template_name, context)
            message = EmailMultiAlternatives(subject, body, from_email, [to_email])
            if html_email_template_name:
                message.attach_alternative(loader.render_to_string(html_email_template_name, context), 'text/html')
            message.send()
        except Exception:
            logger.warning('Password reset email delivery failed.')


class RecoveryPasswordForm(SetPasswordForm):
    new_password1 = forms.CharField(
        label='Nova senha', strip=False, max_length=128,
        widget=forms.PasswordInput(attrs={'autocomplete': 'new-password', 'minlength': 8, 'maxlength': 128}),
        error_messages={'required': 'Informe sua nova senha.', 'max_length': 'Use uma senha com até 128 caracteres.'},
    )
    new_password2 = forms.CharField(
        label='Confirmar nova senha', strip=False, max_length=128,
        widget=forms.PasswordInput(attrs={'autocomplete': 'new-password', 'minlength': 8, 'maxlength': 128}),
        error_messages={'required': 'Confirme sua nova senha.', 'max_length': 'Use uma senha com até 128 caracteres.'},
    )
    error_messages = {'password_mismatch': 'As senhas não coincidem.'}


class RecoveryContextMixin:
    http_method_names = ['get', 'post', 'head', 'options']

    def get_context_data(self, **kwargs):
        context = super().get_context_data(**kwargs)
        context.update(language=reset_language(self.request), reset_email_enabled=reset_email_enabled())
        return context

    def localized_success_url(self, name):
        return reverse(name) + '?' + urlencode({'lang': reset_language(self.request)})

    def render_to_response(self, context, **response_kwargs):
        response = super().render_to_response(context, **response_kwargs)
        # Authentication pages do not need to disclose recovery URLs to referrers.
        response['Referrer-Policy'] = 'no-referrer'
        return response


@method_decorator(never_cache, name='dispatch')
class RequestResetView(RecoveryContextMixin, auth_views.PasswordResetView):
    form_class = RecoveryEmailForm
    template_name = 'accounts/password_reset_form.html'
    email_template_name = 'accounts/password_reset_email.txt'
    subject_template_name = 'accounts/password_reset_subject.txt'
    success_url = reverse_lazy('accounts:password_reset_done')

    def post(self, request, *args, **kwargs):
        key = attempt_key(request, 'password-reset-request')
        if is_blocked(key, RESET_REQUEST_ATTEMPTS):
            form = self.get_form()
            form.errors
            form.add_error(None, THROTTLE_MESSAGE)
            response = self.render_to_response(self.get_context_data(form=form), status=429)
            response['Retry-After'] = str(WINDOW_SECONDS)
            return response
        record_attempt(key)
        if not reset_email_enabled():
            form = self.get_form()
            form.errors
            form.add_error(None, RESET_UNAVAILABLE)
            return self.render_to_response(self.get_context_data(form=form), status=503)
        return super().post(request, *args, **kwargs)

    def form_valid(self, form):
        public_url, parts = public_reset_origin()
        form.save(
            domain_override=parts.netloc,
            use_https=parts.scheme == 'https',
            token_generator=self.token_generator,
            from_email=settings.DEFAULT_FROM_EMAIL,
            email_template_name=self.email_template_name,
            subject_template_name=self.subject_template_name,
            request=self.request,
            extra_email_context={'public_url': public_url, 'language': reset_language(self.request)},
        )
        return HttpResponseRedirect(self.localized_success_url('accounts:password_reset_done'))


@method_decorator(never_cache, name='dispatch')
class ResetDoneView(RecoveryContextMixin, auth_views.PasswordResetDoneView):
    template_name = 'accounts/password_reset_done.html'
    http_method_names = ['get', 'head', 'options']


@method_decorator([never_cache, sensitive_post_parameters('new_password1', 'new_password2')], name='dispatch')
class ResetConfirmView(RecoveryContextMixin, auth_views.PasswordResetConfirmView):
    form_class = RecoveryPasswordForm
    template_name = 'accounts/password_reset_confirm.html'
    success_url = reverse_lazy('accounts:password_reset_complete')
    post_reset_login = False

    def dispatch(self, request, *args, **kwargs):
        key = attempt_key(request, 'password-reset-confirm')
        if is_blocked(key, RESET_CONFIRM_ATTEMPTS):
            self.validlink = False
            self.user = None
            response = self.render_to_response(self.get_context_data(throttled=True), status=429)
            response['Retry-After'] = str(WINDOW_SECONDS)
            return response
        response = super().dispatch(request, *args, **kwargs)
        response['Referrer-Policy'] = 'no-referrer'
        if response.status_code == 302 and not self.validlink:
            # Django removes the secret token from the URL; retain only a known language.
            response['Location'] += '?' + urlencode({'lang': reset_language(request)})
        if not self.validlink and response.status_code != 302:
            record_attempt(key)
        return response

    def get_user(self, uidb64):
        user = super().get_user(uidb64)
        # An account disabled after delivery cannot be revived through recovery.
        return user if user is not None and user.is_active and user.has_usable_password() else None

    def form_invalid(self, form):
        record_attempt(attempt_key(self.request, 'password-reset-confirm'))
        return super().form_invalid(form)

    def form_valid(self, form):
        response = super().form_valid(form)
        response['Location'] = self.localized_success_url('accounts:password_reset_complete')
        return response


@method_decorator(never_cache, name='dispatch')
class ResetCompleteView(RecoveryContextMixin, auth_views.PasswordResetCompleteView):
    template_name = 'accounts/password_reset_complete.html'
    http_method_names = ['get', 'head', 'options']
