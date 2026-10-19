import json
import uuid

from django.contrib.auth import get_user_model
from django.test import TestCase

from accounts.api_auth import issue_access_token

from .api_views import DEFAULT_PREFERENCES, MAX_SCRIPT_BYTES
from .models import Script, StudioPreferences


class StudioApiTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = get_user_model().objects.create_user(
            email='native-studio@example.com', password='Native-password-482!', display_name='Estúdio',
        )
        cls.other_user = get_user_model().objects.create_user(
            email='other-studio@example.com', password='Other-password-482!', display_name='Outra conta',
        )
        cls.secret, _ = issue_access_token(cls.user)
        cls.other_secret, _ = issue_access_token(cls.other_user)

    def api(self, method, path, data=None, *, secret=None):
        return self.client.generic(
            method, f'/api/v1/{path}',
            data='' if data is None else json.dumps(data, ensure_ascii=False),
            content_type='application/json',
            HTTP_AUTHORIZATION=f'Bearer {secret or self.secret}',
        )

    def create_script(self, title='Roteiro', content='Olá, mundo!'):
        result = self.api('POST', 'scripts/', {'title': title, 'content': content})
        self.assertEqual(result.status_code, 201, result.content)
        return result.json()

    def test_script_crud_uses_revision_and_persists_unicode(self):
        created = self.create_script('Roteiro 🎥', 'Olá, ação!\nNova linha.')
        uuid.UUID(created['id'])
        self.assertEqual(created['revision'], 1)
        self.assertIn('T', created['created_at'])
        self.assertEqual(self.api('GET', f"scripts/{created['id']}/").json(), created)

        changed = self.api('PATCH', f"scripts/{created['id']}/", {
            'revision': 1, 'title': 'Atualizado',
        })
        self.assertEqual(changed.status_code, 200)
        self.assertEqual(changed.json()['revision'], 2)
        self.assertEqual(changed.json()['content'], created['content'])
        self.assertEqual(changed.json()['title'], 'Atualizado')

        deleted = self.api('DELETE', f"scripts/{created['id']}/", {'revision': 2})
        self.assertEqual(deleted.status_code, 204)
        self.assertEqual(deleted.content, b'')
        self.assertFalse(Script.objects.filter(pk=created['id']).exists())
        self.assertEqual(self.api('GET', f"scripts/{created['id']}/").status_code, 404)

    def test_scripts_are_isolated_for_listing_read_update_and_delete(self):
        created = self.create_script()
        own_other = Script.objects.create(owner=self.other_user, title='Outra conta', content='Privado')
        other_list = self.api('GET', 'scripts/', secret=self.other_secret).json()
        self.assertEqual(other_list['total'], 1)
        self.assertEqual([item['id'] for item in other_list['items']], [str(own_other.pk)])
        own_list = self.api('GET', 'scripts/').json()
        self.assertEqual([item['id'] for item in own_list['items']], [created['id']])
        for method, data in [('GET', None), ('PATCH', {'revision': 1, 'title': 'Invadido'}), ('DELETE', {'revision': 1})]:
            with self.subTest(method=method):
                result = self.api(method, f"scripts/{created['id']}/", data, secret=self.other_secret)
                self.assertEqual(result.status_code, 404)
        record = Script.objects.get(pk=created['id'])
        self.assertEqual(record.title, 'Roteiro')
        self.assertEqual(record.revision, 1)

    def test_stale_script_update_and_delete_cannot_overwrite_latest_version(self):
        created = self.create_script()
        path = f"scripts/{created['id']}/"
        self.assertEqual(self.api('PATCH', path, {'revision': 1, 'content': 'Versão nova'}).status_code, 200)
        for method, data in [('PATCH', {'revision': 1, 'content': 'Versão antiga'}), ('DELETE', {'revision': 1})]:
            with self.subTest(method=method):
                result = self.api(method, path, data)
                self.assertEqual(result.status_code, 409)
                self.assertEqual(result.json()['error']['code'], 'revision_conflict')
                self.assertEqual(result.json()['error']['details']['current_revision'], 2)
        current = self.api('GET', path).json()
        self.assertEqual(current['content'], 'Versão nova')
        self.assertEqual(current['revision'], 2)

    def test_script_revision_must_be_present_and_a_positive_integer(self):
        created = self.create_script()
        for method in ('PATCH', 'DELETE'):
            for revision in (None, True, False, '1', 1.0, 0, -1, 10 ** 100):
                with self.subTest(method=method, revision=revision):
                    data = {} if revision is None else {'revision': revision}
                    result = self.api(method, f"scripts/{created['id']}/", data)
                    self.assertEqual(result.status_code, 400)
                    self.assertIn('revision', result.json()['error']['fields'])
        self.assertEqual(Script.objects.get(pk=created['id']).revision, 1)

    def test_script_text_required_types_and_unknown_fields(self):
        cases = [
            {}, {'title': 'Sem conteúdo'}, {'content': 'Sem título'},
            {'title': 5, 'content': 'Texto'}, {'title': 'Título', 'content': False},
            {'title': 'a' * 101, 'content': 'Texto'},
            {'title': 'Título', 'content': 'Texto', 'owner': self.other_user.pk},
            {'title': 'Título', 'content': 'Texto', 'revision': 1},
        ]
        for data in cases:
            with self.subTest(data=data):
                self.assertEqual(self.api('POST', 'scripts/', data).status_code, 400)
        self.assertEqual(Script.objects.count(), 0)

        created = self.create_script()
        self.assertEqual(self.api('PATCH', f"scripts/{created['id']}/", {
            'revision': 1, 'owner': self.other_user.pk,
        }).status_code, 400)
        self.assertEqual(self.api('DELETE', f"scripts/{created['id']}/", {
            'revision': 1, 'title': 'Extra',
        }).status_code, 400)

    def test_script_content_limit_counts_utf8_bytes(self):
        at_limit = '🎥' * (MAX_SCRIPT_BYTES // 4)
        created = self.create_script('Vídeo', at_limit)
        self.assertEqual(created['content'], at_limit)
        result = self.api('POST', 'scripts/', {'title': 'Muito texto', 'content': at_limit + 'a'})
        self.assertEqual(result.status_code, 400)
        self.assertIn('content', result.json()['error']['fields'])
        self.assertEqual(self.api('PATCH', f"scripts/{created['id']}/", {
            'revision': 1, 'content': at_limit + 'a',
        }).status_code, 400)

    def test_script_lone_surrogate_is_rejected(self):
        for raw in ('{"title":"\\ud800","content":"ok"}', '{"title":"ok","content":"\\ud800"}'):
            with self.subTest(raw=raw):
                result = self.client.post(
                    '/api/v1/scripts/', raw, content_type='application/json',
                    HTTP_AUTHORIZATION=f'Bearer {self.secret}',
                )
                self.assertEqual(result.status_code, 400)
        self.assertEqual(Script.objects.count(), 0)

    def test_scripts_paginate_with_defaults_and_bounds(self):
        Script.objects.bulk_create([
            Script(owner=self.user, title=f'Roteiro {i}', content='Texto') for i in range(25)
        ])
        defaults = self.api('GET', 'scripts/').json()
        self.assertEqual((defaults['total'], defaults['limit'], defaults['offset']), (25, 20, 0))
        self.assertEqual(len(defaults['items']), 20)
        page = self.api('GET', 'scripts/?limit=5&offset=20').json()
        self.assertEqual(len(page['items']), 5)
        self.assertFalse({item['id'] for item in defaults['items']} & {item['id'] for item in page['items']})
        self.assertEqual(self.api('GET', 'scripts/?limit=100').status_code, 200)
        self.assertEqual(self.api('GET', f'scripts/?offset={10 ** 100}').json()['items'], [])
        for query in ('limit=0', 'limit=101', 'limit=true', 'limit=1.5', 'offset=-1', 'offset=1.2', 'limit=2&limit=3'):
            with self.subTest(query=query):
                self.assertEqual(self.api('GET', f'scripts/?{query}').status_code, 400)

    def test_script_quota_is_per_account(self):
        Script.objects.bulk_create([
            Script(owner=self.user, title=f'Roteiro {i}', content='Texto') for i in range(1000)
        ])
        result = self.api('POST', 'scripts/', {'title': 'Excesso', 'content': 'Texto'})
        self.assertEqual(result.status_code, 409)
        self.assertEqual(result.json()['error']['code'], 'script_limit_reached')
        self.assertEqual(Script.objects.filter(owner=self.user).count(), 1000)
        self.assertEqual(self.api('POST', 'scripts/', {'title': 'Novo', 'content': ''}, secret=self.other_secret).status_code, 201)

    def test_preferences_defaults_partial_update_and_account_isolation(self):
        defaults = self.api('GET', 'preferences/').json()
        self.assertEqual({key: defaults[key] for key in DEFAULT_PREFERENCES}, DEFAULT_PREFERENCES)
        self.assertEqual(defaults['revision'], 1)
        self.assertEqual(StudioPreferences.objects.filter(owner=self.user).count(), 1)
        self.assertEqual(self.api('GET', 'preferences/').json(), defaults)

        result = self.api('PATCH', 'preferences/', {
            'revision': 1, 'font': 'mono', 'color': '#AB12CD', 'speed': 52.5, 'auto_size': False,
        })
        self.assertEqual(result.status_code, 200)
        changed = result.json()
        self.assertEqual(changed['revision'], 2)
        self.assertEqual(changed['color'], '#ab12cd')
        self.assertEqual(changed['font'], 'mono')
        self.assertEqual(changed['speed'], 52.5)
        self.assertFalse(changed['auto_size'])
        for key in ('language', 'size', 'opacity', 'show_prompt', 'mirror'):
            self.assertEqual(changed[key], DEFAULT_PREFERENCES[key])
        self.assertEqual(self.api('GET', 'preferences/').json(), changed)
        other = self.api('GET', 'preferences/', secret=self.other_secret).json()
        self.assertEqual(other['revision'], 1)
        self.assertEqual({key: other[key] for key in DEFAULT_PREFERENCES}, DEFAULT_PREFERENCES)

    def test_preferences_patch_without_prior_get_uses_revision_one(self):
        result = self.api('PATCH', 'preferences/', {'revision': 1, 'language': 'en', 'mirror': False})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()['revision'], 2)
        self.assertEqual(result.json()['language'], 'en')
        self.assertFalse(result.json()['mirror'])

    def test_preferences_conflict_keeps_latest_partial_state(self):
        self.assertEqual(self.api('PATCH', 'preferences/', {'revision': 1, 'font': 'serif'}).status_code, 200)
        result = self.api('PATCH', 'preferences/', {'revision': 1, 'size': 60})
        self.assertEqual(result.status_code, 409)
        self.assertEqual(result.json()['error']['details']['current_revision'], 2)
        unchanged = self.api('GET', 'preferences/').json()
        self.assertEqual(unchanged['font'], 'serif')
        self.assertEqual(unchanged['size'], 36)
        self.assertEqual(unchanged['revision'], 2)
        result = self.api('PATCH', 'preferences/', {'revision': 2, 'size': 60})
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()['font'], 'serif')
        self.assertEqual(result.json()['size'], 60)

    def test_preferences_validates_ranges_types_and_enums(self):
        cases = [
            {'speed': True}, {'speed': 9}, {'speed': 101}, {'speed': '40'}, {'speed': 10 ** 400},
            {'size': 19}, {'size': 121}, {'opacity': -1}, {'opacity': 71},
            {'auto_size': 1}, {'mirror': 'true'}, {'show_prompt': None},
            {'font': 'comic'}, {'font': []}, {'language': 'pt'}, {'language': {}},
            {'color': '#fff'}, {'color': '#ZZ00FF'}, {'color': 'red'},
            {'unknown': True}, {'revision': True}, {'revision': 0}, {'revision': 1.0},
        ]
        for updates in cases:
            with self.subTest(updates=updates):
                result = self.api('PATCH', 'preferences/', {'revision': 1, **updates})
                self.assertEqual(result.status_code, 400, result.content)
        self.assertFalse(StudioPreferences.objects.exists())
        for raw in ('{"revision":1,"speed":NaN}', '{"revision":1,"speed":Infinity}', '{"revision":1,"speed":1e400}'):
            with self.subTest(raw=raw):
                result = self.client.patch('/api/v1/preferences/', raw, content_type='application/json',
                                           HTTP_AUTHORIZATION=f'Bearer {self.secret}')
                self.assertEqual(result.status_code, 400)

    def test_preferences_requires_revision_and_accepts_boundary_values(self):
        self.assertEqual(self.api('PATCH', 'preferences/', {'font': 'mono'}).status_code, 400)
        result = self.api('PATCH', 'preferences/', {
            'revision': 1, 'speed': 10, 'size': 20, 'opacity': 0,
        })
        self.assertEqual(result.status_code, 200)
        result = self.api('PATCH', 'preferences/', {
            'revision': 2, 'speed': 100, 'size': 120, 'opacity': 70, 'language': 'es',
        })
        self.assertEqual(result.status_code, 200)
        self.assertEqual(result.json()['revision'], 3)

    def test_resources_require_bearer_even_when_web_session_is_active(self):
        self.client.force_login(self.user)
        for path in ('scripts/', 'preferences/'):
            with self.subTest(path=path):
                self.assertEqual(self.client.get(f'/api/v1/{path}').status_code, 401)
