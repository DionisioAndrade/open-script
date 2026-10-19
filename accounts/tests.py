from unittest.mock import patch

from django.contrib.auth import SESSION_KEY, authenticate, get_user_model
from django.core.cache import cache
from django.db import IntegrityError, transaction
from django.test import Client, TestCase
from django.urls import reverse
from django.utils import timezone

from . import legal_content
from .forms import (
    LEGAL_READ_REQUIRED, LEGAL_VERSION_CHANGED, PRIVACY_REQUIRED, TERMS_REQUIRED,
    LoginForm, SignupForm,
)
from .models import LegalAcceptance
from .throttle import THROTTLE_MESSAGE
from .views import LEGAL_OPENED_SESSION_KEY, LEGAL_READ_SESSION_KEY


User = get_user_model()
PASSWORD = 'SafeStudio-secret-4926!'


def signup_payload(**changes):
    data = {
        'display_name': 'Bruna', 'email': 'bruna@example.com',
        'password1': PASSWORD, 'password2': PASSWORD,
        'accept_terms': 'on', 'accept_privacy': 'on',
        'terms_version': legal_content.TERMS_VERSION,
        'privacy_version': legal_content.PRIVACY_VERSION,
    }
    data.update(changes)
    return data


def read_documents(client):
    for kind, version in (('terms', legal_content.TERMS_VERSION), ('privacy', legal_content.PRIVACY_VERSION)):
        client.get(reverse(f'accounts:{kind}'))
        response = client.post(reverse('accounts:legal_read'), {'kind': kind, 'version': version})
        if response.status_code != 200:
            raise AssertionError(f'Falha na leitura de {kind}: {response.status_code}')


class UserModelTests(TestCase):
    def test_email_is_normalized_and_password_is_hashed(self):
        user = User.objects.create_user(
            '  Ana@Example.COM  ', PASSWORD, display_name='Ana'
        )
        self.assertEqual(user.email, 'ana@example.com')
        self.assertNotEqual(user.password, PASSWORD)
        self.assertTrue(user.check_password(PASSWORD))
        self.assertEqual(user.get_full_name(), 'Ana')
        self.assertIsNotNone(user.storage_id)

    def test_email_case_cannot_create_another_account(self):
        User.objects.create_user('ana@example.com', PASSWORD)
        with self.assertRaises(IntegrityError), transaction.atomic():
            User.objects.create_user('ANA@EXAMPLE.COM', PASSWORD)

    def test_database_protects_case_insensitive_uniqueness(self):
        user = User.objects.create_user('ana@example.com', PASSWORD)
        other = User.objects.create_user('other@example.com', PASSWORD)
        # update() bypasses model.save() normalização; a restrição ainda protege.
        with self.assertRaises(IntegrityError), transaction.atomic():
            User.objects.filter(pk=other.pk).update(email=user.email.upper())

    def test_superuser_manager_has_native_flags(self):
        user = User.objects.create_superuser('admin@example.com', PASSWORD, display_name='Admin')
        self.assertTrue(user.is_staff)
        self.assertTrue(user.is_superuser)
        with self.assertRaises(ValueError):
            User.objects.create_superuser('invalid@example.com', PASSWORD, is_staff=False)


class AccountFormTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = User.objects.create_user('ana@example.com', PASSWORD, display_name='Ana')

    def signup_data(self, **changes):
        return signup_payload(**changes)

    def test_login_accepts_email_case_and_rejects_wrong_password(self):
        valid = LoginForm(data={'username': ' ANA@EXAMPLE.COM ', 'password': PASSWORD})
        self.assertTrue(valid.is_valid(), valid.errors)
        self.assertEqual(valid.get_user(), self.user)
        invalid = LoginForm(data={'username': 'ana@example.com', 'password': 'incorrect'})
        self.assertFalse(invalid.is_valid())
        self.assertIn('E-mail ou senha incorretos.', invalid.non_field_errors())

    def test_inactive_user_gets_same_generic_login_error(self):
        self.user.is_active = False
        self.user.save()
        self.assertIsNone(authenticate(username=self.user.email, password=PASSWORD))
        form = LoginForm(data={'username': self.user.email, 'password': PASSWORD})
        self.assertFalse(form.is_valid())
        self.assertIn('E-mail ou senha incorretos.', form.non_field_errors())

    def test_signup_prevents_duplicate_email_and_mismatched_passwords(self):
        duplicate = SignupForm(data=self.signup_data(email='ANA@Example.com'))
        self.assertFalse(duplicate.is_valid())
        self.assertIn('Este e-mail já está cadastrado.', duplicate.errors['email'])
        mismatch = SignupForm(data=self.signup_data(password2='different-password-49'))
        self.assertFalse(mismatch.is_valid())
        self.assertIn('As senhas não coincidem.', mismatch.errors['password2'])

    def test_password_validators_reject_short_common_numeric_and_personal_passwords(self):
        for password in ('abc', 'password', '947263850194', 'bruna@example.com'):
            with self.subTest(password=password):
                form = SignupForm(data=self.signup_data(password1=password, password2=password))
                self.assertFalse(form.is_valid())
                self.assertIn('password2', form.errors)

    def test_signup_trims_name_and_normalizes_email(self):
        form = SignupForm(data=self.signup_data(display_name='  Bruna  ', email=' BRUNA@EXAMPLE.COM '))
        self.assertTrue(form.is_valid(), form.errors)
        user = form.save()
        self.assertEqual(user.display_name, 'Bruna')
        self.assertEqual(user.email, 'bruna@example.com')
        self.assertTrue(user.check_password(PASSWORD))


class AccountViewTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = User.objects.create_user('ana@example.com', PASSWORD, display_name='Ana')

    def setUp(self):
        cache.clear()

    def login_data(self, **changes):
        data = {'username': 'ana@example.com', 'password': PASSWORD}
        data.update(changes)
        return data

    def signup_data(self, **changes):
        read_documents(self.client)
        return signup_payload(**changes)

    def assert_visitor_studio(self, response):
        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, 'studio/index.html')
        self.assertIn('no-store', response['Cache-Control'])
        self.assertContains(response, 'id="account-login-link"')
        self.assertContains(response, 'id="account-signup-link"')
        self.assertContains(response, f'href="{reverse("accounts:login")}?next=/"')
        self.assertContains(response, f'href="{reverse("accounts:signup")}?next=/"')
        self.assertNotContains(response, 'data-user-key=')
        self.assertNotContains(response, 'id="account-name"')
        self.assertNotContains(response, 'id="account-email"')
        self.assertNotContains(response, 'id="logout-form"')
        self.assertNotContains(response, self.user.email)
        self.assertNotContains(response, str(self.user.storage_id))

    def test_anonymous_studio_is_public_without_account_data(self):
        response = self.client.get('/?take=1')
        self.assert_visitor_studio(response)
        self.assertNotIn(SESSION_KEY, self.client.session)

    def test_authenticated_studio_shows_only_its_account(self):
        self.client.force_login(self.user)
        response = self.client.get('/')
        self.assertEqual(response.status_code, 200)
        self.assertIn('no-store', response['Cache-Control'])
        self.assertContains(response, f'data-user-key="{self.user.storage_id}"')
        self.assertContains(response, '<p id="account-name" class="account-name">Ana</p>', html=True)
        self.assertContains(response, self.user.email)
        self.assertContains(response, 'id="logout-form"')
        self.assertNotContains(response, 'id="account-login-link"')
        self.assertNotContains(response, 'id="account-signup-link"')

    def test_public_studio_still_requires_get(self):
        self.assertEqual(self.client.post('/').status_code, 405)

    def test_login_pages_are_available_and_never_cached(self):
        for route, template in (('accounts:login', 'accounts/login.html'), ('accounts:signup', 'accounts/signup.html')):
            response = self.client.get(reverse(route))
            self.assertEqual(response.status_code, 200)
            self.assertTemplateUsed(response, template)
            self.assertIn('no-store', response['Cache-Control'])

    def test_login_creates_browser_session_by_default(self):
        response = self.client.post(reverse('accounts:login'), self.login_data())
        self.assertRedirects(response, '/')
        self.assertEqual(self.client.session[SESSION_KEY], str(self.user.pk))
        self.assertTrue(self.client.session.get_expire_at_browser_close())
        self.assertEqual(response.cookies['sessionid']['httponly'], True)
        self.assertEqual(response.cookies['sessionid']['samesite'], 'Lax')
        self.assertEqual(response.cookies['sessionid']['expires'], '')

    def test_remember_me_uses_30_days(self):
        response = self.client.post(reverse('accounts:login'), self.login_data(remember_me='on'))
        self.assertEqual(response.status_code, 302)
        self.assertFalse(self.client.session.get_expire_at_browser_close())
        self.assertEqual(self.client.session.get_expiry_age(), 30 * 24 * 60 * 60)
        self.assertTrue(response.cookies['sessionid']['expires'])

    def test_signup_hashes_password_and_logs_in(self):
        response = self.client.post(reverse('accounts:signup'), self.signup_data(email='BRUNA@EXAMPLE.COM'))
        self.assertRedirects(response, '/')
        user = User.objects.get(email='bruna@example.com')
        self.assertEqual(self.client.session[SESSION_KEY], str(user.pk))
        self.assertNotEqual(user.password, PASSWORD)
        self.assertTrue(user.check_password(PASSWORD))
        self.assertTrue(self.client.session.get_expire_at_browser_close())
        self.assertEqual(user.legal_acceptance.terms_version, legal_content.TERMS_VERSION)
        self.assertEqual(user.legal_acceptance.privacy_version, legal_content.PRIVACY_VERSION)
        self.assertLess(abs((timezone.now() - user.legal_acceptance.accepted_at).total_seconds()), 5)
        self.assertNotIn(LEGAL_OPENED_SESSION_KEY, self.client.session)
        self.assertNotIn(LEGAL_READ_SESSION_KEY, self.client.session)

    def test_invalid_signup_does_not_create_user_or_session(self):
        response = self.client.post(reverse('accounts:signup'), self.signup_data(password1='123', password2='123'))
        self.assertEqual(response.status_code, 200)
        self.assertFalse(User.objects.filter(email='bruna@example.com').exists())
        self.assertNotIn(SESSION_KEY, self.client.session)

    def test_safe_next_is_preserved(self):
        response = self.client.post(reverse('accounts:login'), self.login_data(next='/?tab=recordings'))
        self.assertEqual(response.url, '/?tab=recordings')

    def test_unsafe_next_cannot_redirect_login_or_signup_off_site(self):
        for next_url in ('https://evil.example/', '//evil.example/', '///evil.example/', 'javascript:alert(1)', '/accounts/login/'):
            with self.subTest(next_url=next_url):
                self.client.logout()
                response = self.client.post(reverse('accounts:login'), self.login_data(next=next_url))
                self.assertEqual(response.url, '/')
        self.client.logout()
        response = self.client.post(reverse('accounts:signup'), self.signup_data(next='https://evil.example/'))
        self.assertEqual(response.url, '/')

    def test_ngrok_https_does_not_allow_http_next_downgrade(self):
        host = 'prefix-jalapeno-footboard.ngrok-free.dev'
        response = self.client.post(
            reverse('accounts:login'), self.login_data(next=f'http://{host}/'),
            HTTP_HOST=host, HTTP_X_FORWARDED_PROTO='https',
        )
        self.assertEqual(response.url, '/')

    def test_authenticated_users_skip_auth_pages(self):
        self.client.force_login(self.user)
        for route in ('accounts:login', 'accounts:signup'):
            response = self.client.get(reverse(route))
            self.assertRedirects(response, '/')

    def test_logout_requires_post_and_removes_session(self):
        self.client.force_login(self.user)
        self.assertEqual(self.client.get(reverse('accounts:logout')).status_code, 405)
        self.assertIn(SESSION_KEY, self.client.session)
        response = self.client.post(reverse('accounts:logout'))
        self.assertRedirects(response, reverse('studio:index'))
        self.assertNotIn(SESSION_KEY, self.client.session)
        self.assert_visitor_studio(self.client.get('/'))

    def test_expired_and_inactive_sessions_open_visitor_studio(self):
        self.client.force_login(self.user)
        session = self.client.session
        session.set_expiry(-1)
        session.save()
        self.assert_visitor_studio(self.client.get('/'))
        self.client.force_login(self.user)
        self.user.is_active = False
        self.user.save()
        self.assert_visitor_studio(self.client.get('/'))

    def test_session_endpoint_reports_identity_without_cache(self):
        response = self.client.get(reverse('accounts:session'))
        self.assertEqual(response.json(), {'authenticated': False})
        self.assertIn('no-store', response['Cache-Control'])
        self.client.force_login(self.user)
        data = self.client.get(reverse('accounts:session')).json()
        self.assertTrue(data['authenticated'])
        self.assertEqual(data['user_key'], str(self.user.storage_id))
        self.assertTrue(data['csrf_token'])
        self.assertEqual(self.client.post(reverse('accounts:session')).status_code, 405)

    def test_login_throttle_blocks_after_failed_attempts_and_resets_on_success(self):
        with patch('accounts.views.LOGIN_ATTEMPTS', 2):
            for _ in range(2):
                response = self.client.post(reverse('accounts:login'), self.login_data(password='wrong'))
                self.assertEqual(response.status_code, 200)
            with patch('django.contrib.auth.forms.authenticate') as authentication:
                response = self.client.post(reverse('accounts:login'), self.login_data())
                self.assertEqual(response.status_code, 429)
                authentication.assert_not_called()
            self.assertContains(response, THROTTLE_MESSAGE, status_code=429)
            self.assertEqual(response['Retry-After'], '900')
            self.assertNotIn(SESSION_KEY, self.client.session)
            cache.clear()
            self.assertEqual(self.client.post(reverse('accounts:login'), self.login_data()).status_code, 302)

    def test_signup_throttle_limits_creation_attempts_without_creating_accounts(self):
        with patch('accounts.views.SIGNUP_ATTEMPTS', 2):
            for _ in range(2):
                self.client.post(reverse('accounts:signup'), self.signup_data(password2='wrong'))
            response = self.client.post(reverse('accounts:signup'), self.signup_data())
            self.assertEqual(response.status_code, 429)
            self.assertFalse(User.objects.filter(email='bruna@example.com').exists())


class AccountCsrfTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = User.objects.create_user('csrf@example.com', PASSWORD, display_name='CSRF')

    def setUp(self):
        cache.clear()
        self.client = Client(enforce_csrf_checks=True)

    def test_login_signup_and_logout_reject_missing_csrf(self):
        for route in ('accounts:login', 'accounts:signup', 'accounts:logout', 'accounts:legal_read'):
            self.assertEqual(self.client.post(reverse(route), {}).status_code, 403)

    def test_ngrok_https_origin_supports_real_csrf_login(self):
        host = 'prefix-jalapeno-footboard.ngrok-free.dev'
        self.client.get(reverse('accounts:login'), HTTP_HOST=host, HTTP_X_FORWARDED_PROTO='https')
        token = self.client.cookies['csrftoken'].value
        response = self.client.post(
            reverse('accounts:login'),
            {'username': self.user.email, 'password': PASSWORD, 'csrfmiddlewaretoken': token},
            HTTP_HOST=host, HTTP_ORIGIN=f'https://{host}', HTTP_X_FORWARDED_PROTO='https',
        )
        self.assertEqual(response.status_code, 302)
        self.assertIn(SESSION_KEY, self.client.session)

    def test_foreign_origin_is_rejected_even_with_token(self):
        self.client.get(reverse('accounts:login'))
        token = self.client.cookies['csrftoken'].value
        response = self.client.post(
            reverse('accounts:login'),
            {'username': self.user.email, 'password': PASSWORD, 'csrfmiddlewaretoken': token},
            HTTP_ORIGIN='https://evil.example',
        )
        self.assertEqual(response.status_code, 403)
        self.assertNotIn(SESSION_KEY, self.client.session)


class LegalConsentTests(TestCase):
    def setUp(self):
        cache.clear()

    def assert_signup_blocked(self, data):
        response = self.client.post(reverse('accounts:signup'), data)
        self.assertEqual(response.status_code, 200)
        self.assertFalse(User.objects.filter(email='bruna@example.com').exists())
        self.assertFalse(LegalAcceptance.objects.exists())
        self.assertNotIn(SESSION_KEY, self.client.session)
        return response

    def test_new_form_never_preselects_acceptance(self):
        response = self.client.get(reverse('accounts:signup'))
        form = response.context['form']
        self.assertFalse(form['accept_terms'].value())
        self.assertFalse(form['accept_privacy'].value())
        self.assertFalse(response.context['terms_read'])
        self.assertFalse(response.context['privacy_read'])
        self.assertEqual(form['terms_version'].value(), legal_content.TERMS_VERSION)
        self.assertEqual(form['privacy_version'].value(), legal_content.PRIVACY_VERSION)

    def test_checkboxes_without_reading_cannot_create_account(self):
        response = self.assert_signup_blocked(signup_payload())
        self.assertIn(LEGAL_READ_REQUIRED, response.context['form'].errors['accept_terms'])
        self.assertIn(LEGAL_READ_REQUIRED, response.context['form'].errors['accept_privacy'])

    def test_opening_documents_alone_does_not_mark_them_read_or_accepted(self):
        for kind in ('terms', 'privacy'):
            self.client.get(reverse(f'accounts:{kind}'))
        self.assertNotIn(LEGAL_READ_SESSION_KEY, self.client.session)
        self.assert_signup_blocked(signup_payload())

    def test_reading_without_both_checkboxes_cannot_create_account(self):
        for missing, source in (('accept_terms', TERMS_REQUIRED), ('accept_privacy', PRIVACY_REQUIRED)):
            with self.subTest(missing=missing):
                self.client = Client()
                read_documents(self.client)
                data = signup_payload()
                data.pop(missing)
                response = self.assert_signup_blocked(data)
                self.assertIn(source, response.context['form'].errors[missing])

    def test_reading_only_one_document_cannot_create_account(self):
        self.client.get(reverse('accounts:terms'))
        self.client.post(reverse('accounts:legal_read'), {'kind': 'terms', 'version': legal_content.TERMS_VERSION})
        response = self.assert_signup_blocked(signup_payload())
        self.assertNotIn('accept_terms', response.context['form'].errors)
        self.assertIn(LEGAL_READ_REQUIRED, response.context['form'].errors['accept_privacy'])

    def test_stale_and_missing_versions_cannot_create_account(self):
        read_documents(self.client)
        for field in ('terms_version', 'privacy_version'):
            for stale in ('2020-01-01', ''):
                with self.subTest(field=field, stale=stale):
                    response = self.assert_signup_blocked(signup_payload(**{field: stale}))
                    self.assertIn(LEGAL_VERSION_CHANGED, response.context['form'].errors[field])

    def test_version_change_invalidates_previous_read_receipt(self):
        read_documents(self.client)
        with patch('accounts.legal_content.TERMS_VERSION', '2026-10-13.1'):
            response = self.assert_signup_blocked(signup_payload())
            self.assertIn(LEGAL_READ_REQUIRED, response.context['form'].errors['accept_terms'])
            self.assertFalse(response.context['terms_read'])
            self.assertTrue(response.context['privacy_read'])

    def test_read_endpoint_requires_open_document_and_current_version(self):
        data = {'kind': 'terms', 'version': legal_content.TERMS_VERSION}
        response = self.client.post(reverse('accounts:legal_read'), data)
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()['error'], LEGAL_READ_REQUIRED)
        self.client.get(reverse('accounts:terms'))
        response = self.client.post(reverse('accounts:legal_read'), {**data, 'version': 'old'})
        self.assertEqual(response.status_code, 400)
        self.assertEqual(response.json()['error'], LEGAL_VERSION_CHANGED)
        self.assertNotIn(LEGAL_READ_SESSION_KEY, self.client.session)
        response = self.client.post(reverse('accounts:legal_read'), data)
        self.assertEqual(response.json(), {**data, 'read': True})
        self.assertIn('no-store', response['Cache-Control'])
        self.assertFalse(User.objects.exists())
        self.assertFalse(LegalAcceptance.objects.exists())
        self.assertEqual(self.client.get(reverse('accounts:legal_read')).status_code, 405)
        self.assertEqual(self.client.post(reverse('accounts:legal_read'), {'kind': 'invalid'}).status_code, 400)

    def test_html_read_action_redirects_to_signup_and_safely_keeps_next(self):
        self.client.get(reverse('accounts:terms'))
        response = self.client.post(reverse('accounts:legal_read'), {
            'kind': 'terms', 'version': legal_content.TERMS_VERSION,
            'return_to': 'signup', 'next': '/?take=1',
        })
        self.assertEqual(response.url, '/accounts/signup/?next=%2F%3Ftake%3D1')
        response = self.client.post(reverse('accounts:legal_read'), {
            'kind': 'terms', 'version': legal_content.TERMS_VERSION,
            'return_to': 'signup', 'next': 'https://evil.example',
        })
        self.assertEqual(response.url, '/accounts/signup/')
        response = self.client.post(reverse('accounts:legal_read'), {
            'kind': 'terms', 'version': legal_content.TERMS_VERSION, 'return_to': 'signup',
        }, HTTP_ACCEPT='application/json')
        self.assertEqual(response.status_code, 200)
        self.assertTrue(response.json()['read'])

    def test_documents_are_public_and_localized_in_full_and_fragment_views(self):
        for kind in ('terms', 'privacy'):
            for language in ('pt-BR', 'en', 'es'):
                for fragment in (False, True):
                    with self.subTest(kind=kind, language=language, fragment=fragment):
                        response = self.client.get(reverse(f'accounts:{kind}'), {
                            'lang': language, 'fragment': '1' if fragment else '',
                        })
                        self.assertEqual(response.status_code, 200)
                        self.assertTemplateUsed(response, 'accounts/legal_fragment.html' if fragment else 'accounts/legal_document.html')
                        self.assertEqual(response.context['language'], language)
                        document = response.context['document']
                        self.assertEqual(document['kind'], kind)
                        self.assertEqual(document['version'], getattr(legal_content, f'{kind.upper()}_VERSION'))
                        self.assertTrue(document['sections'])
                        self.assertContains(response, document['sections'][0]['title'])
                        if not fragment:
                            self.assertContains(response, document['title'])
                        self.assertIn('no-store', response['Cache-Control'])
            self.assertEqual(self.client.post(reverse(f'accounts:{kind}')).status_code, 405)
        response = self.client.get(reverse('accounts:terms'), {'lang': '<script>'})
        self.assertEqual(response.context['language'], 'pt-BR')

    def test_read_receipts_do_not_count_as_signup_attempts(self):
        with patch('accounts.views.SIGNUP_ATTEMPTS', 1):
            for _ in range(7):
                read_documents(self.client)
            response = self.client.post(reverse('accounts:signup'), signup_payload())
            self.assertEqual(response.status_code, 302)
            self.assertEqual(LegalAcceptance.objects.count(), 1)

    def test_existing_and_directly_created_users_do_not_get_invented_acceptances(self):
        user = User.objects.create_user('existing@example.com', PASSWORD)
        self.assertFalse(LegalAcceptance.objects.filter(user=user).exists())
        self.client.force_login(user)
        self.assertEqual(self.client.get('/').status_code, 200)
        self.assertFalse(LegalAcceptance.objects.exists())

    def test_failure_saving_acceptance_rolls_back_user_creation(self):
        read_documents(self.client)
        with patch('accounts.views.LegalAcceptance.objects.create', side_effect=IntegrityError):
            self.assert_signup_blocked(signup_payload())

    def test_acceptance_is_removed_when_its_account_is_deleted(self):
        read_documents(self.client)
        self.client.post(reverse('accounts:signup'), signup_payload())
        user = User.objects.get(email='bruna@example.com')
        self.assertTrue(LegalAcceptance.objects.filter(user=user).exists())
        user.delete()
        self.assertFalse(LegalAcceptance.objects.exists())

    def test_csrf_protects_read_receipt_and_final_signup(self):
        client = Client(enforce_csrf_checks=True)
        client.get(reverse('accounts:terms'))
        token = client.cookies['csrftoken'].value
        data = {'kind': 'terms', 'version': legal_content.TERMS_VERSION}
        self.assertEqual(client.post(reverse('accounts:legal_read'), data).status_code, 403)
        response = client.post(reverse('accounts:legal_read'), data, HTTP_X_CSRFTOKEN=token)
        self.assertTrue(response.json()['read'])
        client.get(reverse('accounts:privacy'))
        response = client.post(reverse('accounts:legal_read'), {
            'kind': 'privacy', 'version': legal_content.PRIVACY_VERSION,
        }, HTTP_X_CSRFTOKEN=token)
        self.assertTrue(response.json()['read'])
        self.assertEqual(client.post(reverse('accounts:signup'), signup_payload()).status_code, 403)
        self.assertFalse(LegalAcceptance.objects.exists())
        response = client.post(reverse('accounts:signup'), signup_payload(), HTTP_X_CSRFTOKEN=token)
        self.assertEqual(response.status_code, 302)
        self.assertEqual(LegalAcceptance.objects.count(), 1)
