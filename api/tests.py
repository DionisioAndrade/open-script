import json
from unittest.mock import patch

from django.conf import settings
from django.test import Client, RequestFactory, SimpleTestCase, TestCase, override_settings
from django.urls import resolve

from studio.catalog import export_catalog

from .common import MAX_JSON_BYTES, api_endpoint


class PublicApiTests(TestCase):
    def setUp(self):
        self.client = Client(enforce_csrf_checks=True)

    def test_config_describes_local_capture_and_native_capabilities(self):
        result = self.client.get('/api/v1/config/')
        self.assertEqual(result.status_code, 200)
        config = result.json()
        self.assertEqual(config['api_version'], 'v1')
        self.assertEqual(config['authentication']['token_days'], settings.OPENSCRIPT_API_TOKEN_DAYS)
        self.assertFalse(config['authentication']['native_google_login'])
        self.assertFalse(config['recording']['upload_supported'])
        self.assertEqual(config['recording']['execution'], 'device')
        self.assertEqual(result['Cache-Control'], 'no-store')

    def test_catalog_is_shared_by_web_and_native_clients(self):
        catalog = export_catalog()
        web = self.client.get('/')
        native = self.client.get('/api/v1/export-profiles/').json()
        self.assertEqual(web.context['export_catalog'], catalog)
        self.assertContains(web, 'id="studio-export-catalog"')
        self.assertEqual(native['resolutions'], catalog['resolutions'])
        self.assertEqual(native['video_bitrates'], catalog['bitrates'])
        self.assertEqual([p['id'] for p in native['profiles']], [p['id'] for p in catalog['profiles']])
        for web_profile, native_profile in zip(catalog['profiles'], native['profiles']):
            self.assertEqual(native_profile['preferred_formats'], web_profile['formats'])
            self.assertEqual(native_profile['frame_rate'], web_profile['frameRate'])
            self.assertEqual(native_profile['resolution'], web_profile['resolution'])

    def test_schema_paths_resolve_and_all_references_exist(self):
        result = self.client.get('/api/v1/openapi.json')
        self.assertEqual(result.status_code, 200)
        schema = result.json()
        self.assertEqual(schema['openapi'], '3.0.3')
        identifiers = []
        for path, methods in schema['paths'].items():
            actual = path.replace('{kind}', 'terms').replace('{script_id}', '00000000-0000-0000-0000-000000000001')
            resolved = resolve('/api/v1' + actual)
            self.assertNotEqual(resolved.func.__name__, 'not_found', path)
            for method, operation in methods.items():
                if method == 'parameters':
                    continue
                identifiers.append(operation['operationId'])
        self.assertEqual(len(identifiers), len(set(identifiers)))

        def check_refs(item):
            if isinstance(item, dict):
                if '$ref' in item:
                    current = schema
                    for part in item['$ref'][2:].split('/'):
                        current = current[part]
                for value in item.values():
                    check_refs(value)
            elif isinstance(item, list):
                for value in item:
                    check_refs(value)
        check_refs(schema)

    def test_unknown_paths_and_methods_return_json_without_redirects(self):
        for path in ('/api/v1/', '/api/v1/missing/', '/api/v1/scripts/not-a-uuid/', '/api/v1/config'):
            with self.subTest(path=path):
                result = self.client.get(path)
                self.assertEqual(result.status_code, 404)
                self.assertEqual(result.json()['error']['code'], 'not_found')
        result = self.client.post('/api/v1/config/', '{}', content_type='application/json')
        self.assertEqual(result.status_code, 405)
        self.assertEqual(result['Allow'], 'GET, OPTIONS')

    def test_options_does_not_enable_cross_origin_cookie_access(self):
        result = self.client.options('/api/v1/preferences/', HTTP_ORIGIN='https://another.example')
        self.assertEqual(result.status_code, 204)
        self.assertEqual(result.content, b'')
        self.assertEqual(result['Allow'], 'GET, PATCH, OPTIONS')
        self.assertNotIn('Access-Control-Allow-Origin', result)

    def test_invalid_json_is_rejected_before_endpoint_logic(self):
        invalid = [
            b'{', b'[]', b'null', b'{"email":"a","email":"b"}',
            b'{"password":NaN}', b'{"password":Infinity}', b'{"password":1e999}',
            b'{"password":"\\ud800"}', b'{"\\ud800":"value"}', b'{"password":"\xff"}',
            b'[' * 2000 + b']' * 2000,
        ]
        for body in invalid:
            with self.subTest(body=body[:80]):
                result = self.client.post('/api/v1/auth/login/', body, content_type='application/json')
                self.assertEqual(result.status_code, 400)
                self.assertEqual(result.json()['error']['code'], 'invalid_json')

    def test_oversized_and_non_json_requests_are_rejected(self):
        result = self.client.post('/api/v1/auth/login/', b' ' * (MAX_JSON_BYTES + 1), content_type='application/json')
        self.assertEqual(result.status_code, 413)
        result = self.client.post('/api/v1/auth/login/', '{"email":"a"}', content_type='text/plain')
        self.assertEqual(result.status_code, 415)


class ApiFailureTests(SimpleTestCase):
    @override_settings(DEBUG=True)
    def test_unexpected_error_returns_sanitized_json_even_in_debug(self):
        @api_endpoint(['GET'])
        def broken(request):
            raise RuntimeError('private-internal-detail')
        with patch('api.common.logger.exception'):
            result = broken(RequestFactory().get('/api/v1/test/'))
        self.assertEqual(result.status_code, 500)
        self.assertEqual(json.loads(result.content)['error']['code'], 'internal_error')
        self.assertNotIn(b'private-internal-detail', result.content)
        self.assertEqual(result['Cache-Control'], 'no-store')
