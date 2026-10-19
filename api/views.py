"""Public capabilities describe server data, not the phone's camera hardware."""

import json
from pathlib import Path

from django.conf import settings

from accounts import legal_content
from accounts.password_reset import reset_email_enabled
from studio.api_views import DEFAULT_PREFERENCES, MAX_SCRIPT_BYTES, MAX_SCRIPTS_PER_USER
from studio.catalog import export_catalog

from .common import MAX_JSON_BYTES, ApiError, api_endpoint, response


@api_endpoint(['GET'])
def config(request):
    return response({
        'api_version': 'v1',
        'authentication': {
            'scheme': 'Bearer', 'token_days': settings.OPENSCRIPT_API_TOKEN_DAYS,
            'password_login': True, 'password_reset': reset_email_enabled(),
            'native_google_login': False,
        },
        'languages': ['pt-BR', 'en', 'es'],
        'legal_versions': {'terms': legal_content.TERMS_VERSION, 'privacy': legal_content.PRIVACY_VERSION},
        'default_preferences': DEFAULT_PREFERENCES,
        'limits': {'json_bytes': MAX_JSON_BYTES, 'script_content_bytes': MAX_SCRIPT_BYTES,
                   'scripts_per_account': MAX_SCRIPTS_PER_USER, 'page_size_max': 100},
        'recording': {
            'execution': 'device', 'upload_supported': False,
            'preferred_width': 1080, 'preferred_height': 1920,
            'preferred_frame_rate': 30, 'countdown_seconds': 5,
            'include_prompt_in_video': False,
        },
        'web_storage': 'local_only',
    })


@api_endpoint(['GET'])
def export_profiles(request):
    catalog = export_catalog()
    names = {
        'frameRate': 'frame_rate', 'formats': 'preferred_formats',
        'maxSeconds': 'max_seconds', 'minSeconds': 'min_seconds',
        'maxBytes': 'max_bytes', 'limitLabel': 'limit_label',
        'minimumLabel': 'minimum_label', 'adviceAboveSeconds': 'advice_above_seconds',
    }
    return response({
        'catalog_version': catalog['version'],
        'profiles': [{names.get(key, key): value for key, value in profile.items()} for profile in catalog['profiles']],
        'aspect_ratios': catalog['ratios'], 'resolutions': catalog['resolutions'],
        'frame_rates': catalog['frameRates'], 'video_bitrates': catalog['bitrates'],
        'audio_bitrates': catalog['audioBitrates'],
        'limits_are_advisory': True,
    })


@api_endpoint(['GET'])
def schema(request):
    return response(json.loads((Path(__file__).resolve().parent / 'openapi.json').read_text(encoding='utf-8')))


@api_endpoint(['GET', 'POST', 'PUT', 'PATCH', 'DELETE'])
def not_found(request, unmatched=''):
    raise ApiError('not_found', 'Endpoint não encontrado.', status=404)
