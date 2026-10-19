"""OAuth checks use mocked Google token exchange and isolated test databases."""

from urllib.parse import parse_qs, urlsplit
from unittest.mock import patch

import jwt

from django.contrib.auth import SESSION_KEY
from django.core import mail
from django.core.cache import cache
from django.core.exceptions import PermissionDenied
from django.db import IntegrityError
from django.test import Client, TestCase, override_settings
from django.urls import reverse
from django.utils import timezone

from allauth.account.models import EmailAddress
from allauth.socialaccount.models import SocialAccount, SocialToken
from allauth.socialaccount.providers.oauth2.client import OAuth2Error

from . import legal_content
from .adapters import OpenScriptAccountAdapter, OpenScriptSocialAccountAdapter
from .google import (
    GOOGLE_CONNECT_EMAIL, GOOGLE_CONNECT_REQUIRED, GOOGLE_CREATE_FAILED,
    GOOGLE_DUPLICATE_EMAIL, GOOGLE_EXPIRED, GOOGLE_INACTIVE, GOOGLE_UNAVAILABLE,
    PENDING_GOOGLE_TIME_KEY,
)
from .models import LegalAcceptance, User
from .views import LEGAL_OPENED_SESSION_KEY, LEGAL_READ_SESSION_KEY


GOOGLE_TEST_SETTINGS = {
    'GOOGLE_CLIENT_ID': 'test-client.apps.googleusercontent.com',
    'GOOGLE_CLIENT_SECRET': 'test-secret-only',
    'OPENSCRIPT_PUBLIC_URL': 'https://studio.example.test',
    'OPENSCRIPT_GOOGLE_ENABLED': True,
    'EMAIL_BACKEND': 'django.core.mail.backends.locmem.EmailBackend',
    'SOCIALACCOUNT_PROVIDERS': {'google': {
        'SCOPE': ['openid', 'profile', 'email'],
        'OAUTH_PKCE_ENABLED': True,
        'AUTH_PARAMS': {'access_type': 'online', 'prompt': 'select_account'},
        'APP': {'client_id': 'test-client.apps.googleusercontent.com', 'secret': 'test-secret-only', 'key': ''},
    }},
}


@override_settings(**GOOGLE_TEST_SETTINGS)
class GoogleLoginTests(TestCase):
    def setUp(self):
        cache.clear()
        mail.outbox = []

    def begin(self, **data):
        response = self.client.post(reverse('accounts:google_login'), data)
        self.assertEqual(response.status_code, 302)
        self.assertEqual(urlsplit(response.url).netloc, 'accounts.google.com')
        return parse_qs(urlsplit(response.url).query)

    def claims(self, **changes):
        data = {
            'sub': 'google-subject-123', 'email': 'google@example.test', 'email_verified': True,
            'name': 'Nome Google', 'picture': 'https://private-avatar.example/photo.jpg',
            'aud': GOOGLE_TEST_SETTINGS['GOOGLE_CLIENT_ID'], 'iss': 'https://accounts.google.com',
            'exp': int(timezone.now().timestamp()) + 3600, 'iat': int(timezone.now().timestamp()),
        }
        data.update(changes)
        return data

    def callback(self, query, *, claims=None, token_response=None):
        response_data = token_response or {
            'access_token': 'test-access-token', 'refresh_token': 'test-refresh-token',
            'expires_in': 3600,
            'id_token': jwt.encode(claims or self.claims(), 'test-signing-key-with-at-least-32-bytes', algorithm='HS256'),
        }
        with patch('allauth.socialaccount.providers.oauth2.client.OAuth2Client.get_access_token', return_value=response_data) as exchange:
            response = self.client.get(reverse('accounts:google_callback'), {'state': query['state'][0], 'code': 'test-code'})
        return response, exchange

    def pending(self, **changes):
        query = self.begin(lang='en')
        response, _ = self.callback(query, claims=self.claims(**changes))
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.url, reverse('accounts:google_signup'))
        self.assertFalse(User.objects.exists())
        return response

    def read_documents(self):
        for kind in ('terms', 'privacy'):
            self.client.get(reverse(f'accounts:{kind}'), {'return_to': 'google_signup'})
            response = self.client.post(reverse('accounts:legal_read'), {
                'kind': kind, 'version': getattr(legal_content, f'{kind.upper()}_VERSION'),
            })
            self.assertEqual(response.status_code, 200)

    def signup_data(self, **changes):
        data = {
            'display_name': 'Nome escolhido', 'email': 'forged@example.test',
            'accept_terms': 'on', 'accept_privacy': 'on',
            'terms_version': legal_content.TERMS_VERSION,
            'privacy_version': legal_content.PRIVACY_VERSION,
        }
        data.update(changes)
        return data

    def test_visitor_studio_and_email_login_still_work(self):
        self.assertEqual(self.client.get('/').status_code, 200)
        user = User.objects.create_user('local@example.test', 'StrongTestPass123!', display_name='Local')
        response = self.client.post(reverse('accounts:login'), {'username': user.email, 'password': 'StrongTestPass123!'})
        self.assertEqual(response.status_code, 302)
        self.assertEqual(self.client.session[SESSION_KEY], str(user.pk))

    def test_start_requires_post_and_csrf(self):
        self.assertEqual(self.client.get(reverse('accounts:google_login')).status_code, 405)
        client = Client(enforce_csrf_checks=True)
        self.assertEqual(client.post(reverse('accounts:google_login')).status_code, 403)
        client.get(reverse('accounts:login'))
        token = client.cookies['csrftoken'].value
        self.assertEqual(client.post(reverse('accounts:google_login'), {'csrfmiddlewaretoken': token}).status_code, 302)

    def test_pending_credentials_have_friendly_failure(self):
        with override_settings(GOOGLE_CLIENT_ID='', GOOGLE_CLIENT_SECRET=''):
            for method, route in ((self.client.post, 'google_login'), (self.client.get, 'google_callback'), (self.client.get, 'google_signup')):
                response = method(reverse(f'accounts:{route}'))
                self.assertContains(response, GOOGLE_UNAVAILABLE, status_code=503)
                self.assertIn('no-store', response['Cache-Control'])
        self.assertFalse(User.objects.exists())

    def test_start_uses_canonical_callback_pkce_and_fixed_scopes(self):
        response = self.client.post(
            reverse('accounts:google_login') + '?scope=https://www.googleapis.com/auth/drive&auth_params=redirect_uri%3Dhttps%3A%2F%2Fevil.example',
            {'next': 'https://evil.example', 'process': 'login'}, HTTP_HOST='untrusted.example.test',
        )
        query = parse_qs(urlsplit(response.url).query)
        self.assertEqual(query['redirect_uri'], ['https://studio.example.test/accounts/google/login/callback/'])
        self.assertEqual(set(query['scope'][0].split()), {'openid', 'profile', 'email'})
        self.assertEqual(query['access_type'], ['online'])
        self.assertEqual(query['code_challenge_method'], ['S256'])
        self.assertGreaterEqual(len(query['code_challenge'][0]), 43)
        state = self.client.session['socialaccount_states'][query['state'][0]][0]
        self.assertEqual(state['next'], '/')
        self.assertGreaterEqual(len(state['pkce_code_verifier']), 43)

    def test_invalid_and_replayed_states_never_exchange_twice(self):
        with patch('allauth.socialaccount.providers.oauth2.client.OAuth2Client.get_access_token') as exchange:
            response = self.client.get(reverse('accounts:google_callback'), {'state': 'forged', 'code': 'test-code'})
            self.assertEqual(response.status_code, 400)
            exchange.assert_not_called()
        query = self.begin()
        first, exchange = self.callback(query)
        self.assertEqual(first.status_code, 302)
        self.assertTrue(exchange.call_args.kwargs['pkce_code_verifier'])
        second, exchange = self.callback(query)
        self.assertEqual(second.status_code, 400)
        exchange.assert_not_called()
        self.assertNotIn(SESSION_KEY, self.client.session)

    def test_cancel_and_token_network_failure_do_not_create_account(self):
        query = self.begin()
        response = self.client.get(reverse('accounts:google_callback'), {'state': query['state'][0], 'error': 'access_denied'})
        self.assertEqual(response.status_code, 400)
        query = self.begin()
        with patch('allauth.socialaccount.providers.oauth2.client.OAuth2Client.get_access_token', side_effect=OAuth2Error('test failure')):
            response = self.client.get(reverse('accounts:google_callback'), {'state': query['state'][0], 'code': 'test-code'})
        self.assertEqual(response.status_code, 400)
        self.assertFalse(User.objects.exists())

    def test_oidc_invalid_issuer_audience_expiry_and_subject_are_rejected(self):
        for changes in (
            {'iss': 'https://evil.example'}, {'aud': 'different-client'}, {'azp': 'different-client'},
            {'exp': int(timezone.now().timestamp()) - 1}, {'exp': None},
            {'sub': ''}, {'sub': ' '}, {'sub': 123}, {'sub': 'x' * 192},
        ):
            with self.subTest(changes=changes):
                query = self.begin()
                response, _ = self.callback(query, claims=self.claims(**changes))
                self.assertEqual(response.status_code, 400)
                self.assertFalse(User.objects.exists())
                self.assertNotIn('socialaccount_sociallogin', self.client.session)

    def test_email_must_be_present_valid_and_verified_boolean(self):
        for changes in (
            {'email_verified': False}, {'email_verified': 'true'},
            {'email': ''}, {'email': 'invalid-email'}, {'email': None},
        ):
            with self.subTest(changes=changes):
                query = self.begin()
                response, _ = self.callback(query, claims=self.claims(**changes))
                self.assertEqual(response.status_code, 400)
                self.assertFalse(User.objects.exists())

    def test_missing_id_token_is_rejected(self):
        query = self.begin()
        response, _ = self.callback(query, token_response={'access_token': 'test-access-token', 'expires_in': 3600})
        self.assertEqual(response.status_code, 400)
        self.assertFalse(User.objects.exists())

    def test_new_identity_waits_for_signup_and_stores_no_tokens_or_avatar(self):
        self.pending()
        stored = self.client.session['socialaccount_sociallogin']
        self.assertNotIn('token', stored)
        self.assertEqual(stored['account']['extra_data'], {'sub': 'google-subject-123', 'email': 'google@example.test', 'name': 'Nome Google'})
        self.assertNotIn('test-access-token', str(stored))
        self.assertNotIn('private-avatar', str(stored))
        self.assertFalse(SocialToken.objects.exists())
        response = self.client.get(reverse('accounts:google_signup'))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.context['language'], 'en')
        self.assertEqual(response.context['form']['email'].value(), 'google@example.test')
        self.assertEqual(response.context['form']['display_name'].value(), 'Nome Google')
        self.assertFalse(response.context['form']['accept_terms'].value())
        self.assertNotContains(response, 'name="password1"')

    def test_no_pending_identity_or_expired_pending_cannot_signup(self):
        response = self.client.get(reverse('accounts:google_signup'))
        self.assertContains(response, GOOGLE_EXPIRED, status_code=400)
        self.pending()
        session = self.client.session
        session[PENDING_GOOGLE_TIME_KEY] = timezone.now().timestamp() - 901
        session.save()
        response = self.client.post(reverse('accounts:google_signup'), self.signup_data())
        self.assertContains(response, GOOGLE_EXPIRED, status_code=400)
        self.assertFalse(User.objects.exists())

    def test_reading_and_each_separate_acceptance_are_required(self):
        self.pending()
        response = self.client.post(reverse('accounts:google_signup'), self.signup_data())
        self.assertEqual(response.status_code, 200)
        self.assertFalse(User.objects.exists())
        self.read_documents()
        for field in ('accept_terms', 'accept_privacy'):
            response = self.client.post(reverse('accounts:google_signup'), self.signup_data(**{field: ''}))
            self.assertEqual(response.status_code, 200)
            self.assertFalse(User.objects.exists())
        response = self.client.post(reverse('accounts:google_signup'), self.signup_data(terms_version='old'))
        self.assertEqual(response.status_code, 200)
        self.assertFalse(User.objects.exists())

    def test_valid_signup_uses_verified_email_ignores_forged_email_and_saves_acceptance(self):
        self.pending(email='GOOGLE@EXAMPLE.TEST')
        self.read_documents()
        response = self.client.post(reverse('accounts:google_signup'), self.signup_data())
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.url, '/')
        user = User.objects.get(email='google@example.test')
        self.assertEqual(user.display_name, 'Nome escolhido')
        self.assertFalse(user.has_usable_password())
        self.assertEqual(self.client.session[SESSION_KEY], str(user.pk))
        self.assertTrue(self.client.session.get_expire_at_browser_close())
        self.assertEqual(user.legal_acceptance.terms_version, legal_content.TERMS_VERSION)
        self.assertEqual(user.legal_acceptance.privacy_version, legal_content.PRIVACY_VERSION)
        self.assertTrue(EmailAddress.objects.get(user=user).verified)
        self.assertEqual(SocialAccount.objects.get(user=user).uid, 'google-subject-123')
        self.assertFalse(SocialToken.objects.exists())
        self.assertEqual(len(mail.outbox), 0)
        for key in ('socialaccount_sociallogin', PENDING_GOOGLE_TIME_KEY, LEGAL_OPENED_SESSION_KEY, LEGAL_READ_SESSION_KEY):
            self.assertNotIn(key, self.client.session)

    def test_signup_database_failure_rolls_back_user_social_account_and_acceptance(self):
        self.pending()
        self.read_documents()
        with patch('accounts.adapters.LegalAcceptance.objects.create', side_effect=IntegrityError('test failure')):
            response = self.client.post(reverse('accounts:google_signup'), self.signup_data())
        self.assertContains(response, GOOGLE_CREATE_FAILED)
        self.assertFalse(User.objects.exists())
        self.assertFalse(SocialAccount.objects.exists())
        self.assertFalse(EmailAddress.objects.exists())
        self.assertFalse(LegalAcceptance.objects.exists())
        self.assertNotIn(SESSION_KEY, self.client.session)
        self.assertIn('socialaccount_sociallogin', self.client.session)

    def test_local_email_collision_cannot_link_or_login_automatically(self):
        user = User.objects.create_user('google@example.test', 'StrongTestPass123!', display_name='Local')
        query = self.begin()
        response, _ = self.callback(query, claims=self.claims(email='GOOGLE@EXAMPLE.TEST'))
        self.assertContains(response, GOOGLE_DUPLICATE_EMAIL, status_code=400)
        self.assertFalse(SocialAccount.objects.exists())
        self.assertNotIn(SESSION_KEY, self.client.session)
        user.refresh_from_db()
        self.assertTrue(user.check_password('StrongTestPass123!'))
        self.assertEqual(len(mail.outbox), 0)

    def test_email_collision_after_pending_is_rejected(self):
        self.pending()
        User.objects.create_user('google@example.test', 'StrongTestPass123!', display_name='Racing local')
        self.read_documents()
        response = self.client.post(reverse('accounts:google_signup'), self.signup_data())
        self.assertContains(response, GOOGLE_DUPLICATE_EMAIL)
        self.assertEqual(User.objects.count(), 1)
        self.assertFalse(SocialAccount.objects.exists())

    def test_existing_google_subject_logs_into_same_account_without_new_consent(self):
        user = User.objects.create_user('old-address@example.test', display_name='Original')
        SocialAccount.objects.create(user=user, provider='google', uid='google-subject-123', extra_data={})
        query = self.begin(next='/?tab=recordings')
        response, _ = self.callback(query)
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.url, '/?tab=recordings')
        self.assertEqual(self.client.session[SESSION_KEY], str(user.pk))
        self.assertFalse(LegalAcceptance.objects.exists())
        self.assertEqual(User.objects.count(), 1)
        self.assertEqual(SocialAccount.objects.get(user=user).extra_data, {'sub': 'google-subject-123', 'email': 'google@example.test', 'name': 'Nome Google'})
        self.assertFalse(SocialToken.objects.exists())

    def test_inactive_existing_google_account_cannot_login(self):
        user = User.objects.create_user('google@example.test', display_name='Inactive', is_active=False)
        SocialAccount.objects.create(user=user, provider='google', uid='google-subject-123')
        response, _ = self.callback(self.begin())
        self.assertContains(response, GOOGLE_INACTIVE, status_code=400)
        self.assertNotIn(SESSION_KEY, self.client.session)

    def test_explicit_connect_requires_authenticated_same_account_and_same_email(self):
        response = self.client.post(reverse('accounts:google_login'), {'process': 'connect'})
        self.assertContains(response, GOOGLE_CONNECT_REQUIRED, status_code=401)
        user = User.objects.create_user('google@example.test', 'StrongTestPass123!', display_name='Local')
        self.client.force_login(user)
        query = self.begin(process='connect')
        response, _ = self.callback(query)
        self.assertEqual(response.status_code, 302)
        self.assertEqual(response.url, '/?settings=1')
        self.assertEqual(SocialAccount.objects.get().user, user)
        self.assertEqual(self.client.session[SESSION_KEY], str(user.pk))
        self.assertFalse(LegalAcceptance.objects.exists())
        self.assertFalse(SocialToken.objects.exists())
        self.assertEqual(len(mail.outbox), 0)

    def test_connect_rejects_different_google_email_and_changed_local_session(self):
        user = User.objects.create_user('different@example.test', 'StrongTestPass123!', display_name='Local')
        self.client.force_login(user)
        response, _ = self.callback(self.begin(process='connect'))
        self.assertContains(response, GOOGLE_CONNECT_EMAIL, status_code=400)
        self.assertFalse(SocialAccount.objects.exists())
        query = self.begin(process='connect')
        other = User.objects.create_user('google@example.test', 'StrongTestPass123!', display_name='Other')
        self.client.force_login(other)
        response, exchange = self.callback(query)
        self.assertEqual(response.status_code, 400)
        exchange.assert_not_called()
        self.assertFalse(SocialAccount.objects.exists())

    def test_connect_cannot_transfer_google_subject_owned_by_other_user(self):
        owner = User.objects.create_user('owner@example.test', display_name='Owner')
        SocialAccount.objects.create(user=owner, provider='google', uid='google-subject-123')
        local = User.objects.create_user('google@example.test', 'StrongTestPass123!', display_name='Local')
        self.client.force_login(local)
        response, _ = self.callback(self.begin(process='connect'))
        self.assertEqual(response.status_code, 400)
        self.assertEqual(SocialAccount.objects.get().user, owner)
        self.assertEqual(self.client.session[SESSION_KEY], str(local.pk))

    def test_google_signup_rate_limit_matches_local_signup(self):
        self.pending()
        with patch('accounts.google.SIGNUP_ATTEMPTS', 2):
            for _ in range(2):
                self.assertEqual(self.client.post(reverse('accounts:google_signup'), self.signup_data()).status_code, 200)
            response = self.client.post(reverse('accounts:google_signup'), self.signup_data())
        self.assertEqual(response.status_code, 429)
        self.assertEqual(response['Retry-After'], '900')
        self.assertFalse(User.objects.exists())

    def test_changed_document_version_invalidates_google_signup_read_receipt(self):
        self.pending()
        self.read_documents()
        with patch('accounts.legal_content.TERMS_VERSION', '2026-10-13.1'):
            response = self.client.post(reverse('accounts:google_signup'), self.signup_data())
        self.assertEqual(response.status_code, 200)
        self.assertIn('accept_terms', response.context['form'].errors)
        self.assertFalse(User.objects.exists())

    def test_html_document_read_returns_to_google_signup_with_language(self):
        self.pending()
        response = self.client.get(reverse('accounts:terms'), {'return_to': 'google_signup', 'lang': 'es'})
        self.assertContains(response, 'name="return_to" value="google_signup"')
        self.assertContains(response, reverse('accounts:google_signup') + '?lang=es')
        response = self.client.post(reverse('accounts:legal_read'), {
            'kind': 'terms', 'version': legal_content.TERMS_VERSION,
            'return_to': 'google_signup', 'lang': 'es', 'next': 'https://evil.example',
        })
        self.assertEqual(response.url, reverse('accounts:google_signup') + '?lang=es')
        response = self.client.get(response.url)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.context['language'], 'es')
        self.assertTrue(response.context['terms_read'])
        self.assertFalse(response.context['privacy_read'])
        self.assertFalse(response.context['form']['accept_terms'].value())

    def test_standard_allauth_signup_and_auto_email_linking_are_closed(self):
        self.assertFalse(OpenScriptAccountAdapter().is_open_for_signup(None))
        adapter = OpenScriptSocialAccountAdapter()
        self.assertFalse(adapter.is_auto_signup_allowed(None, None))
        self.assertFalse(adapter.can_authenticate_by_email(None, 'google@example.test'))
        with self.assertRaises(PermissionDenied):
            adapter.save_user(None, None)

    def test_social_signup_final_post_requires_csrf(self):
        self.pending()
        client = Client(enforce_csrf_checks=True)
        client.cookies['sessionid'] = self.client.cookies['sessionid']
        response = client.post(reverse('accounts:google_signup'), self.signup_data())
        self.assertEqual(response.status_code, 403)
        self.assertFalse(User.objects.exists())
