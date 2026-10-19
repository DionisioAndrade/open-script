import re
from datetime import datetime, timedelta
from unittest.mock import patch
from urllib.parse import urlsplit

from django.contrib.auth import SESSION_KEY, authenticate, get_user_model
from django.contrib.auth.tokens import default_token_generator
from django.contrib.auth.views import INTERNAL_RESET_SESSION_TOKEN
from django.core import mail
from django.core.cache import cache
from django.test import Client, TestCase, override_settings
from django.urls import reverse
from django.utils.encoding import force_bytes
from django.utils.http import urlsafe_base64_encode

from .password_reset import (
    RESET_CONFIRM_ATTEMPTS, RESET_REQUEST_ATTEMPTS, RESET_UNAVAILABLE,
    RecoveryPasswordForm, public_reset_origin,
)
from .throttle import THROTTLE_MESSAGE


User = get_user_model()
PASSWORD = 'SafeStudio-secret-4926!'
NEW_PASSWORD = 'FreshOpenScript-secret-7143!'
PUBLIC_URL = 'https://studio.example.test'


@override_settings(
    EMAIL_BACKEND='django.core.mail.backends.locmem.EmailBackend',
    DEFAULT_FROM_EMAIL='OpenScript <no-reply@example.test>',
    OPENSCRIPT_PUBLIC_URL=PUBLIC_URL,
    OPENSCRIPT_PASSWORD_RESET_ENABLED=True,
    PASSWORD_RESET_TIMEOUT=3600,
)
class PasswordRecoveryTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = User.objects.create_user('ana@example.test', PASSWORD, display_name='Ana')
        cls.inactive = User.objects.create_user('inactive@example.test', PASSWORD, is_active=False)
        cls.federated = User.objects.create_user('google@example.test', password=None)

    def setUp(self):
        cache.clear()
        mail.outbox = []

    def request_reset(self, email=None, **kwargs):
        return self.client.post(
            reverse('accounts:password_reset'),
            {'email': self.user.email if email is None else email, 'lang': 'pt-BR'},
            **kwargs,
        )

    def token_url(self, user=None, token=None):
        user = user or self.user
        return reverse('accounts:password_reset_confirm', kwargs={
            'uidb64': urlsafe_base64_encode(force_bytes(user.pk)),
            'token': token or default_token_generator.make_token(user),
        })

    def open_reset(self, **kwargs):
        first = self.client.get(self.token_url(**kwargs))
        self.assertEqual(first.status_code, 302)
        self.assertIn('set-password/', first['Location'])
        self.assertEqual(first['Referrer-Policy'], 'no-referrer')
        response = self.client.get(first['Location'])
        self.assertTrue(response.context['validlink'])
        return first['Location']

    def test_public_form_is_available_without_authentication_or_camera(self):
        response = self.client.get(reverse('accounts:password_reset'))
        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'accounts/password_reset_form.html')
        self.assertTrue(response.context['reset_email_enabled'])
        self.assertIn('no-store', response['Cache-Control'])
        self.assertNotContains(response, 'getUserMedia')
        self.assertNotIn(SESSION_KEY, self.client.session)

    def test_existing_email_receives_fixed_origin_single_use_link(self):
        response = self.request_reset(' ANA@EXAMPLE.TEST ')
        self.assertRedirects(response, reverse('accounts:password_reset_done') + '?lang=pt-BR')
        self.assertEqual(len(mail.outbox), 1)
        message = mail.outbox[0]
        self.assertEqual(message.to, [self.user.email])
        self.assertEqual(message.from_email, 'OpenScript <no-reply@example.test>')
        self.assertEqual(message.subject, 'Redefina sua senha do OpenScript')
        self.assertIn('uma hora', message.body)
        self.assertIn('apenas uma vez', message.body)
        link = re.search(r'https://[^\s]+', message.body).group()
        self.assertEqual(urlsplit(link).netloc, 'studio.example.test')
        response = self.client.get(urlsplit(link).path, {'lang': 'pt-BR'}, follow=True)
        self.assertTrue(response.context['validlink'])
        self.assertNotIn(SESSION_KEY, self.client.session)

    def test_unknown_inactive_and_federated_addresses_get_identical_done(self):
        for email in ('missing@example.test', self.inactive.email, self.federated.email):
            with self.subTest(email=email):
                response = self.request_reset(email)
                self.assertEqual(response.status_code, 302)
                self.assertEqual(response['Location'], reverse('accounts:password_reset_done') + '?lang=pt-BR')
                self.assertEqual(len(mail.outbox), 0)
        self.assertEqual(User.objects.count(), 3)

    def test_request_host_and_forwarded_host_cannot_poison_reset_link(self):
        response = self.request_reset(
            HTTP_HOST='attacker.example.test',
            HTTP_X_FORWARDED_HOST='forwarded-attacker.example.test',
            HTTP_X_FORWARDED_PROTO='http',
        )
        self.assertEqual(response.status_code, 302)
        self.assertEqual(len(mail.outbox), 1)
        self.assertIn(PUBLIC_URL, mail.outbox[0].body)
        self.assertNotIn('attacker', mail.outbox[0].body)

    def test_local_http_origin_is_explicitly_allowed_for_tests(self):
        with override_settings(OPENSCRIPT_PUBLIC_URL='http://127.0.0.1:8001/'):
            self.request_reset()
        self.assertIn('http://127.0.0.1:8001/accounts/', mail.outbox[0].body)

    def test_invalid_public_origins_disable_all_delivery(self):
        for origin in ('', 'http://public.example.test', 'https://name:pass@example.test',
                       'https://example.test/path', 'https://example.test?host=bad',
                       'https://example.test#bad', 'https://example.test:0',
                       'https://example.test\nInjected: value'):
            with self.subTest(origin=origin), override_settings(OPENSCRIPT_PUBLIC_URL=origin):
                cache.clear()
                self.assertIsNone(public_reset_origin())
                response = self.request_reset()
                self.assertEqual(response.status_code, 503)
                self.assertFalse(response.context['reset_email_enabled'])
                self.assertIn(RESET_UNAVAILABLE, response.context['form'].non_field_errors())
        self.assertEqual(len(mail.outbox), 0)

    def test_globally_disabled_delivery_does_not_query_account_existence(self):
        with override_settings(OPENSCRIPT_PASSWORD_RESET_ENABLED=False):
            with patch('accounts.password_reset.RecoveryEmailForm.get_users') as get_users:
                for email in (self.user.email, 'missing@example.test'):
                    response = self.request_reset(email)
                    self.assertEqual(response.status_code, 503)
                    self.assertIn(RESET_UNAVAILABLE, response.context['form'].non_field_errors())
                get_users.assert_not_called()
        self.assertEqual(len(mail.outbox), 0)

    def test_smtp_failure_is_generic_and_does_not_log_secrets(self):
        with patch('django.core.mail.backends.locmem.EmailBackend.send_messages', side_effect=RuntimeError('secret-token-and-address')):
            with self.assertLogs('accounts.password_reset', level='WARNING') as captured:
                response = self.request_reset()
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], reverse('accounts:password_reset_done') + '?lang=pt-BR')
        self.assertEqual(captured.output, ['WARNING:accounts.password_reset:Password reset email delivery failed.'])
        self.assertEqual(len(mail.outbox), 0)

    def test_invalid_email_is_rejected_without_sending_mail(self):
        response = self.request_reset('invalid-address')
        self.assertEqual(response.status_code, 200)
        self.assertIn('Informe um e-mail válido.', response.context['form'].errors['email'])
        self.assertEqual(len(mail.outbox), 0)

    def test_request_throttle_is_shared_across_addresses_and_ignores_proxy_ip(self):
        for index in range(RESET_REQUEST_ATTEMPTS):
            self.request_reset(f'missing-{index}@example.test', HTTP_X_FORWARDED_FOR=f'192.0.2.{index}')
        response = self.request_reset(self.user.email, HTTP_X_FORWARDED_FOR='192.0.2.199')
        self.assertEqual(response.status_code, 429)
        self.assertIn(THROTTLE_MESSAGE, response.context['form'].non_field_errors())
        self.assertEqual(response['Retry-After'], '900')
        self.assertEqual(len(mail.outbox), 0)

    def test_reset_request_requires_csrf(self):
        client = Client(enforce_csrf_checks=True)
        response = client.post(reverse('accounts:password_reset'), {'email': self.user.email})
        self.assertEqual(response.status_code, 403)
        self.assertEqual(len(mail.outbox), 0)

    def test_valid_reset_changes_password_once_and_does_not_login(self):
        original_token_url = self.token_url()
        first = self.client.get(original_token_url)
        self.assertNotEqual(self.client.session.get(INTERNAL_RESET_SESSION_TOKEN), None)
        response = self.client.post(first['Location'], {
            'new_password1': NEW_PASSWORD, 'new_password2': NEW_PASSWORD, 'lang': 'en',
        })
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response['Location'], reverse('accounts:password_reset_complete') + '?lang=en')
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password(NEW_PASSWORD))
        self.assertFalse(self.user.check_password(PASSWORD))
        self.assertIsNone(authenticate(username=self.user.email, password=PASSWORD))
        self.assertNotIn(SESSION_KEY, self.client.session)
        self.assertNotIn(INTERNAL_RESET_SESSION_TOKEN, self.client.session)
        self.assertFalse(self.client.get(original_token_url).context['validlink'])
        self.assertEqual(User.objects.count(), 3)

    def test_reset_invalidates_preexisting_authenticated_sessions(self):
        logged_in = Client()
        logged_in.force_login(self.user)
        target = self.open_reset()
        self.client.post(target, {'new_password1': NEW_PASSWORD, 'new_password2': NEW_PASSWORD})
        self.assertFalse(logged_in.get(reverse('accounts:session')).json()['authenticated'])

    def test_weak_mismatch_and_oversized_passwords_are_rejected(self):
        target = self.open_reset()
        for password1, password2 in (('abc', 'abc'), ('password', 'password'),
                                     ('947263850194', '947263850194'),
                                     (NEW_PASSWORD, 'mismatch-password-17'), ('x' * 129, 'x' * 129)):
            with self.subTest(password=password1):
                response = self.client.post(target, {'new_password1': password1, 'new_password2': password2})
                self.assertEqual(response.status_code, 200)
                self.assertTrue(response.context['form'].errors)
                self.user.refresh_from_db()
                self.assertTrue(self.user.check_password(PASSWORD))
        self.assertEqual(RecoveryPasswordForm(self.user).fields['new_password1'].max_length, 128)

    def test_confirmation_post_requires_csrf(self):
        client = Client(enforce_csrf_checks=True)
        first = client.get(self.token_url())
        response = client.post(first['Location'], {'new_password1': NEW_PASSWORD, 'new_password2': NEW_PASSWORD})
        self.assertEqual(response.status_code, 403)
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password(PASSWORD))

    def test_expired_token_is_rejected_after_one_hour(self):
        created_at = datetime(2026, 10, 12, 12, 0, 0)
        with patch.object(default_token_generator, '_now', return_value=created_at):
            token = default_token_generator.make_token(self.user)
        with patch.object(default_token_generator, '_now', return_value=created_at + timedelta(seconds=3601)):
            response = self.client.get(self.token_url(token=token))
        self.assertEqual(response.status_code, 200)
        self.assertFalse(response.context['validlink'])
        self.assertNotIn(INTERNAL_RESET_SESSION_TOKEN, self.client.session)

    def test_invalid_missing_user_or_disabled_account_tokens_are_rejected(self):
        for url in (self.token_url(token='invalid-token'), self.token_url(user=self.inactive),
                    self.token_url(user=self.federated),
                    reverse('accounts:password_reset_confirm', kwargs={'uidb64': 'not-a-user', 'token': 'invalid'})):
            with self.subTest(url=url):
                response = self.client.get(url)
                self.assertEqual(response.status_code, 200)
                self.assertFalse(response.context['validlink'])
        self.assertNotIn(INTERNAL_RESET_SESSION_TOKEN, self.client.session)

    def test_invalid_confirmation_attempts_are_throttled(self):
        url = self.token_url(token='invalid-token')
        for _ in range(RESET_CONFIRM_ATTEMPTS):
            self.assertEqual(self.client.get(url).status_code, 200)
        response = self.client.get(url)
        self.assertEqual(response.status_code, 429)
        self.assertFalse(response.context['validlink'])
        self.assertTrue(response.context['throttled'])
        self.assertEqual(response['Retry-After'], '900')
        self.assertIn('no-store', response['Cache-Control'])

    def test_recovery_email_language_is_validated_and_preserved_in_link(self):
        for language, subject, link_language in (
            ('en', 'Reset your OpenScript password', 'en'),
            ('es', 'Restablece tu contraseña de OpenScript', 'es'),
            ('invalid', 'Redefina sua senha do OpenScript', 'pt-BR'),
        ):
            with self.subTest(language=language):
                response = self.client.post(reverse('accounts:password_reset'), {'email': self.user.email, 'lang': language})
                self.assertEqual(response['Location'], reverse('accounts:password_reset_done') + '?lang=' + link_language)
                self.assertEqual(mail.outbox[-1].subject, subject)
                self.assertIn('?lang=' + link_language, mail.outbox[-1].body)

    def test_confirmation_token_redirect_preserves_only_valid_language(self):
        for language, expected in (('en', 'en'), ('es', 'es'), ('invalid', 'pt-BR')):
            with self.subTest(language=language):
                response = self.client.get(self.token_url(), {'lang': language, 'next': 'https://attacker.example.test'})
                self.assertEqual(response.status_code, 302)
                self.assertTrue(response['Location'].endswith('set-password/?lang=' + expected))
                self.assertNotIn('attacker', response['Location'])
                target = self.client.get(response['Location'])
                self.assertEqual(target.context['language'], expected)

    def test_done_and_complete_are_read_only_public_pages(self):
        for name, template in (('password_reset_done', 'accounts/password_reset_done.html'),
                               ('password_reset_complete', 'accounts/password_reset_complete.html')):
            response = self.client.get(reverse('accounts:' + name), {'lang': 'es'})
            self.assertEqual(response.status_code, 200)
            self.assertTemplateUsed(response, template)
            self.assertEqual(response.context['language'], 'es')
            self.assertIn('no-store', response['Cache-Control'])
            self.assertEqual(self.client.post(reverse('accounts:' + name)).status_code, 405)
