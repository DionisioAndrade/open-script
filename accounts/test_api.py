import hashlib
import json
from datetime import timedelta
from unittest.mock import patch

from django.conf import settings
from django.contrib.auth import SESSION_KEY
from django.contrib.auth.tokens import default_token_generator
from django.core import mail, signing
from django.core.cache import cache
from django.test import Client, TestCase, override_settings
from django.urls import reverse
from django.utils import timezone
from django.utils.encoding import force_bytes
from django.utils.http import urlsafe_base64_encode

from . import legal_content
from .api_auth import issue_access_token
from .api_views import OPENED_RECEIPT_SALT, READ_RECEIPT_SALT, RECEIPT_MAX_AGE
from .models import LegalAcceptance, NativeAccessToken, User
from .throttle import LOGIN_ATTEMPTS, SIGNUP_ATTEMPTS


PASSWORD = 'SafeStudio-secret-4926!'
NEW_PASSWORD = 'AnotherStudio-secret-9352!'
AUTH = '/api/v1/auth/'
ME = '/api/v1/me/'
LEGAL = '/api/v1/legal/'


@override_settings(OPENSCRIPT_API_TOKEN_DAYS=30)
class NativeAccountApiTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = User.objects.create_user('ana@example.com', PASSWORD, display_name='Ana')

    def setUp(self):
        cache.clear()
        self.client = Client(enforce_csrf_checks=True)

    def post(self, path, data, client=None, **headers):
        return (client or self.client).post(path, data=json.dumps(data), content_type='application/json', **headers)

    def login(self, client=None):
        result = self.post(AUTH + 'login/', {'email': ' ANA@EXAMPLE.COM ', 'password': PASSWORD}, client=client)
        self.assertEqual(result.status_code, 200, result.content)
        return result.json()['access_token']

    def bearer(self, secret):
        return {'HTTP_AUTHORIZATION': 'Bearer ' + secret}

    def read_documents(self):
        receipts = {}
        for kind in ('terms', 'privacy'):
            opened = self.client.get(LEGAL + kind + '/').json()
            result = self.post(LEGAL + 'read/', {
                'kind': kind, 'version': opened['document']['version'],
                'opened_receipt': opened['opened_receipt'], 'confirmed': True,
            })
            self.assertEqual(result.status_code, 200, result.content)
            receipts[kind + '_receipt'] = result.json()['read_receipt']
        return receipts

    def signup_data(self, **changes):
        data = {
            'display_name': '  Bruna  ', 'email': ' BRUNA@EXAMPLE.COM ',
            'password': PASSWORD, 'password_confirm': PASSWORD,
            'accept_terms': True, 'accept_privacy': True,
            'terms_version': legal_content.TERMS_VERSION,
            'privacy_version': legal_content.PRIVACY_VERSION,
            **self.read_documents(),
        }
        data.update(changes)
        return data

    def test_login_issues_hashed_token_without_creating_web_session(self):
        secret = self.login()
        token = NativeAccessToken.objects.get()
        self.assertEqual(len(secret), 43)
        self.assertEqual(token.token_hash, hashlib.sha256(secret.encode('ascii')).hexdigest())
        self.assertNotEqual(secret, token.token_hash)
        self.assertNotEqual(secret, token.password_fingerprint)
        self.assertNotEqual(self.user.password, token.password_fingerprint)
        self.assertEqual(token.user_id, self.user.pk)
        self.assertAlmostEqual((token.expires_at - token.created_at).total_seconds(), 30 * 86400, delta=2)
        self.assertNotIn(settings.SESSION_COOKIE_NAME, self.client.cookies)
        self.user.refresh_from_db()
        self.assertIsNotNone(self.user.last_login)
        self.assertFalse(LegalAcceptance.objects.filter(user=self.user).exists())

    def test_login_returns_public_uuid_and_no_internal_password(self):
        result = self.post(AUTH + 'login/', {'email': self.user.email, 'password': PASSWORD})
        data = result.json()
        self.assertEqual(data['token_type'], 'Bearer')
        self.assertEqual(data['user'], {'id': str(self.user.storage_id), 'display_name': 'Ana', 'email': self.user.email})
        self.assertEqual(set(data), {'access_token', 'token_type', 'expires_at', 'user'})
        self.assertIn('no-store', result['Cache-Control'])

    def test_invalid_credentials_and_inactive_accounts_are_indistinguishable(self):
        inactive = User.objects.create_user('inactive@example.com', PASSWORD, is_active=False)
        results = [self.post(AUTH + 'login/', {'email': email, 'password': password}) for email, password in (
            (self.user.email, 'wrong-password'), ('missing@example.com', PASSWORD), (inactive.email, PASSWORD),
        )]
        self.assertTrue(all(result.status_code == 401 for result in results))
        self.assertEqual(results[0].json(), results[1].json())
        self.assertEqual(results[1].json(), results[2].json())
        self.assertEqual(NativeAccessToken.objects.count(), 0)

    def test_login_throttles_failed_attempts_and_resets_counter_after_success(self):
        data = {'email': self.user.email, 'password': 'wrong-password'}
        for _ in range(LOGIN_ATTEMPTS - 1):
            self.assertEqual(self.post(AUTH + 'login/', data).status_code, 401)
        self.login()
        self.assertEqual(self.post(AUTH + 'login/', data).status_code, 401)
        for _ in range(LOGIN_ATTEMPTS - 1):
            self.post(AUTH + 'login/', data)
        result = self.post(AUTH + 'login/', {'email': self.user.email, 'password': PASSWORD})
        self.assertEqual(result.status_code, 429)
        self.assertEqual(result['Retry-After'], '900')

    def test_web_login_still_requires_csrf_while_api_login_accepts_json(self):
        web = self.client.post(reverse('accounts:login'), {'username': self.user.email, 'password': PASSWORD})
        self.assertEqual(web.status_code, 403)
        self.login()

    def test_session_cookie_does_not_authorize_api_and_bearer_does_not_login_web(self):
        cookie_client = Client()
        cookie_client.force_login(self.user)
        self.assertEqual(cookie_client.get(ME).status_code, 401)
        secret = self.login()
        self.assertEqual(self.client.get(ME, **self.bearer(secret)).status_code, 200)
        web = self.client.get(reverse('accounts:session'), **self.bearer(secret))
        self.assertFalse(web.json()['authenticated'])

    def test_api_login_and_logout_preserve_an_existing_web_session(self):
        other = User.objects.create_user('other@example.com', PASSWORD, display_name='Other')
        self.client.force_login(other)
        web_id = self.client.session[SESSION_KEY]
        secret = self.login()
        self.assertEqual(self.client.session[SESSION_KEY], web_id)
        self.assertEqual(self.client.get(ME, **self.bearer(secret)).json()['user']['email'], self.user.email)
        result = self.post(AUTH + 'logout/', {}, **self.bearer(secret))
        self.assertEqual(result.status_code, 200)
        self.assertEqual(self.client.session[SESSION_KEY], web_id)

    def test_malformed_missing_or_unknown_bearer_tokens_are_rejected(self):
        for header in ('', 'Basic abc', 'Bearer', 'Bearer abc', 'Bearer ' + 'a' * 43,
                       'Bearer ' + 'a' * 42 + 'é', 'Bearer ' + 'a' * 43 + ' extra'):
            with self.subTest(header=header):
                result = self.client.get(ME, HTTP_AUTHORIZATION=header)
                self.assertEqual(result.status_code, 401)
                self.assertEqual(result['WWW-Authenticate'], 'Bearer')

    def test_token_expiry_and_explicit_revocation_take_effect(self):
        expired, expired_token = issue_access_token(self.user)
        NativeAccessToken.objects.filter(pk=expired_token.pk).update(expires_at=timezone.now())
        self.assertEqual(self.client.get(ME, **self.bearer(expired)).status_code, 401)
        revoked, revoked_token = issue_access_token(self.user)
        NativeAccessToken.objects.filter(pk=revoked_token.pk).update(revoked_at=timezone.now())
        self.assertEqual(self.client.get(ME, **self.bearer(revoked)).status_code, 401)

    def test_password_change_invalidates_all_preexisting_tokens(self):
        secrets = [issue_access_token(self.user)[0] for _ in range(2)]
        self.user.set_password(NEW_PASSWORD)
        self.user.save(update_fields=['password'])
        for secret in secrets:
            self.assertEqual(self.client.get(ME, **self.bearer(secret)).status_code, 401)
        fresh, _ = issue_access_token(self.user)
        self.assertEqual(self.client.get(ME, **self.bearer(fresh)).status_code, 200)

    def test_disabled_and_deleted_accounts_cannot_use_tokens(self):
        secret, _ = issue_access_token(self.user)
        self.user.is_active = False
        self.user.save(update_fields=['is_active'])
        self.assertEqual(self.client.get(ME, **self.bearer(secret)).status_code, 401)
        self.user.delete()
        self.assertEqual(NativeAccessToken.objects.count(), 0)
        self.assertEqual(self.client.get(ME, **self.bearer(secret)).status_code, 401)

    def test_logout_revokes_only_current_device_token(self):
        first, first_token = issue_access_token(self.user)
        second, _ = issue_access_token(self.user)
        result = self.post(AUTH + 'logout/', {}, **self.bearer(first))
        self.assertEqual(result.status_code, 200)
        first_token.refresh_from_db()
        self.assertIsNotNone(first_token.revoked_at)
        self.assertEqual(self.client.get(ME, **self.bearer(first)).status_code, 401)
        self.assertEqual(self.client.get(ME, **self.bearer(second)).status_code, 200)

    def test_profile_patch_changes_only_display_name(self):
        secret = self.login()
        result = self.client.patch(ME, json.dumps({'display_name': '  Ana Maria  '}), content_type='application/json', **self.bearer(secret))
        self.assertEqual(result.status_code, 200, result.content)
        self.assertEqual(result.json()['user']['display_name'], 'Ana Maria')
        self.user.refresh_from_db()
        self.assertEqual(self.user.display_name, 'Ana Maria')
        self.assertEqual(self.user.email, 'ana@example.com')
        self.assertTrue(self.user.check_password(PASSWORD))

    def test_profile_rejects_other_fields_blank_oversize_and_non_text_values(self):
        secret = self.login()
        for data in ({'email': 'evil@example.com'}, {'is_staff': True}, {'password': NEW_PASSWORD},
                     {'display_name': ''}, {'display_name': 'x' * 101}, {'display_name': 42}):
            with self.subTest(data=data):
                result = self.client.patch(ME, json.dumps(data), content_type='application/json', **self.bearer(secret))
                self.assertEqual(result.status_code, 400, result.content)
        self.user.refresh_from_db()
        self.assertEqual(self.user.display_name, 'Ana')

    def test_json_api_rejects_form_payload_unknown_keys_and_non_string_credentials(self):
        self.assertEqual(self.client.post(AUTH + 'login/', {'email': self.user.email, 'password': PASSWORD}).status_code, 415)
        for data in ({'email': self.user.email, 'password': PASSWORD, 'next': 'https://evil.example'},
                     {'email': ['ana@example.com'], 'password': PASSWORD}, {'email': self.user.email, 'password': True}):
            self.assertEqual(self.post(AUTH + 'login/', data).status_code, 400)
        self.assertEqual(NativeAccessToken.objects.count(), 0)

    def test_json_api_rejects_malformed_or_non_object_payloads(self):
        for body in ('{', '[]', 'null', '"text"'):
            result = self.client.post(AUTH + 'login/', data=body, content_type='application/json')
            self.assertEqual(result.status_code, 400)

    def test_unsupported_methods_return_json_and_allow(self):
        result = self.client.get(AUTH + 'login/')
        self.assertEqual(result.status_code, 405)
        self.assertEqual(result['Content-Type'], 'application/json')
        self.assertIn('POST', result['Allow'])

    def test_signup_records_current_acceptance_and_returns_access_without_session(self):
        result = self.post(AUTH + 'signup/', self.signup_data())
        self.assertEqual(result.status_code, 201, result.content)
        created = User.objects.get(email='bruna@example.com')
        self.assertEqual(created.display_name, 'Bruna')
        acceptance = LegalAcceptance.objects.get(user=created)
        self.assertEqual(acceptance.terms_version, legal_content.TERMS_VERSION)
        self.assertEqual(acceptance.privacy_version, legal_content.PRIVACY_VERSION)
        self.assertEqual(self.client.get(ME, **self.bearer(result.json()['access_token'])).json()['user']['id'], str(created.storage_id))
        self.assertNotIn(settings.SESSION_COOKIE_NAME, self.client.cookies)

    def test_signup_requires_separate_read_receipts_and_strict_boolean_acceptances(self):
        data = self.signup_data()
        for changes in ({'terms_receipt': ''}, {'privacy_receipt': data['terms_receipt']},
                        {'accept_terms': 'true'}, {'accept_privacy': 1}):
            result = self.post(AUTH + 'signup/', {**data, **changes})
            self.assertEqual(result.status_code, 400, result.content)
        self.assertFalse(User.objects.filter(email='bruna@example.com').exists())

    def test_signup_rejects_stale_versions_and_tampered_receipts(self):
        data = self.signup_data()
        stale = signing.dumps({'kind': 'terms', 'version': 'old'}, salt=READ_RECEIPT_SALT)
        for changes in ({'terms_version': 'old'}, {'terms_receipt': stale},
                        {'terms_receipt': data['terms_receipt'] + 'tampered'}):
            result = self.post(AUTH + 'signup/', {**data, **changes})
            self.assertEqual(result.status_code, 400, result.content)
        self.assertFalse(User.objects.filter(email='bruna@example.com').exists())

    def test_signup_rejects_expired_read_receipt(self):
        data = self.signup_data()
        with patch('django.core.signing.time.time', return_value=timezone.now().timestamp() + RECEIPT_MAX_AGE + 2):
            result = self.post(AUTH + 'signup/', data)
        self.assertEqual(result.status_code, 400, result.content)
        self.assertFalse(User.objects.filter(email='bruna@example.com').exists())

    def test_signup_reuses_password_and_duplicate_email_validation(self):
        data = self.signup_data()
        for changes, field in (({'email': 'ANA@EXAMPLE.COM'}, 'email'), ({'password': 'abc', 'password_confirm': 'abc'}, 'password_confirm'),
                               ({'password_confirm': NEW_PASSWORD}, 'password_confirm')):
            result = self.post(AUTH + 'signup/', {**data, **changes})
            self.assertEqual(result.status_code, 400)
            self.assertIn(field, result.json()['error']['fields'])
        self.assertFalse(User.objects.filter(email='bruna@example.com').exists())

    def test_signup_attempts_are_throttled(self):
        for _ in range(SIGNUP_ATTEMPTS):
            self.assertEqual(self.post(AUTH + 'signup/', {}).status_code, 400)
        result = self.post(AUTH + 'signup/', {})
        self.assertEqual(result.status_code, 429)
        self.assertEqual(result['Retry-After'], '900')

    def test_legal_document_is_localized_without_session_and_unknown_kind_is_404(self):
        result = self.client.get(LEGAL + 'terms/', {'lang': 'en'})
        self.assertEqual(result.status_code, 200)
        document = result.json()['document']
        self.assertEqual(document['language'], 'en')
        self.assertEqual(document['title'], 'Terms of Use')
        self.assertTrue(document['sections'])
        self.assertNotIn(settings.SESSION_COOKIE_NAME, self.client.cookies)
        self.assertEqual(self.client.get(LEGAL + 'other/').status_code, 404)

    def test_legal_read_requires_opened_matching_current_receipt_and_true_confirmation(self):
        opened = self.client.get(LEGAL + 'terms/').json()
        data = {'kind': 'terms', 'version': legal_content.TERMS_VERSION, 'opened_receipt': opened['opened_receipt'], 'confirmed': True}
        for changes in ({'opened_receipt': ''}, {'kind': 'privacy'}, {'version': 'old'},
                        {'confirmed': False}, {'confirmed': 'true'}):
            result = self.post(LEGAL + 'read/', {**data, **changes})
            self.assertEqual(result.status_code, 400, result.content)
        result = self.post(LEGAL + 'read/', data)
        self.assertEqual(result.status_code, 200)
        self.assertEqual(signing.loads(result.json()['read_receipt'], salt=READ_RECEIPT_SALT), {'kind': 'terms', 'version': legal_content.TERMS_VERSION})
        self.assertEqual(self.post(LEGAL + 'read/', {**data, 'opened_receipt': result.json()['read_receipt']}).status_code, 400)

    def test_legal_read_rejects_expired_opened_receipt(self):
        receipt = signing.dumps({'kind': 'terms', 'version': legal_content.TERMS_VERSION}, salt=OPENED_RECEIPT_SALT)
        with patch('django.core.signing.time.time', return_value=timezone.now().timestamp() + RECEIPT_MAX_AGE + 2):
            result = self.post(LEGAL + 'read/', {'kind': 'terms', 'version': legal_content.TERMS_VERSION, 'opened_receipt': receipt, 'confirmed': True})
        self.assertEqual(result.status_code, 400)


@override_settings(
    OPENSCRIPT_API_TOKEN_DAYS=30, OPENSCRIPT_PASSWORD_RESET_ENABLED=True,
    OPENSCRIPT_PUBLIC_URL='https://openscript.example.test', DEFAULT_FROM_EMAIL='OpenScript <noreply@example.test>',
    EMAIL_BACKEND='django.core.mail.backends.locmem.EmailBackend',
)
class NativePasswordResetApiTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = User.objects.create_user('ana@example.com', PASSWORD, display_name='Ana')

    def setUp(self):
        cache.clear()
        self.client = Client(enforce_csrf_checks=True)

    def request_reset(self, email, **headers):
        return self.client.post(AUTH + 'password-reset/', json.dumps({'email': email, 'lang': 'en'}), content_type='application/json', **headers)

    def test_password_reset_returns_same_response_for_known_and_unknown_addresses(self):
        known = self.request_reset(self.user.email)
        unknown = self.request_reset('missing@example.com')
        self.assertEqual(known.status_code, 202)
        self.assertEqual(unknown.status_code, 202)
        self.assertEqual(known.json(), unknown.json())
        self.assertEqual(len(mail.outbox), 1)
        self.assertEqual(mail.outbox[0].to, [self.user.email])
        self.assertIn('https://openscript.example.test/accounts/reset/', mail.outbox[0].body)
        self.assertIn('?lang=en', mail.outbox[0].body)
        self.assertNotIn(settings.SESSION_COOKIE_NAME, self.client.cookies)

    @override_settings(ALLOWED_HOSTS=['attacker.example.test'])
    def test_password_reset_email_uses_configured_origin_instead_of_request_host(self):
        result = self.request_reset(self.user.email, HTTP_HOST='attacker.example.test')
        self.assertEqual(result.status_code, 202)
        self.assertIn('https://openscript.example.test/accounts/reset/', mail.outbox[0].body)
        self.assertNotIn('attacker.example.test', mail.outbox[0].body)

    def test_completed_web_password_reset_invalidates_native_access(self):
        secret, _ = issue_access_token(self.user)
        browser = Client()
        url = reverse('accounts:password_reset_confirm', kwargs={
            'uidb64': urlsafe_base64_encode(force_bytes(self.user.pk)),
            'token': default_token_generator.make_token(self.user),
        })
        opened = browser.get(url)
        result = browser.post(opened['Location'], {'new_password1': NEW_PASSWORD, 'new_password2': NEW_PASSWORD})
        self.assertEqual(result.status_code, 302)
        self.assertEqual(self.client.get(ME, HTTP_AUTHORIZATION='Bearer ' + secret).status_code, 401)
        self.user.refresh_from_db()
        self.assertTrue(self.user.check_password(NEW_PASSWORD))

    @override_settings(OPENSCRIPT_PASSWORD_RESET_ENABLED=False)
    def test_unconfigured_email_returns_503_without_attempting_delivery(self):
        result = self.request_reset(self.user.email)
        self.assertEqual(result.status_code, 503)
        self.assertEqual(len(mail.outbox), 0)

    def test_reset_request_reuses_throttling_and_does_not_send_after_limit(self):
        for _ in range(5):
            self.assertEqual(self.request_reset(self.user.email).status_code, 202)
        result = self.request_reset(self.user.email)
        self.assertEqual(result.status_code, 429)
        self.assertEqual(result['Retry-After'], '900')
        self.assertEqual(len(mail.outbox), 5)
