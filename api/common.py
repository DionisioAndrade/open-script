"""Bearer-only endpoints; browser authentication keeps its CSRF protection."""

import json
import logging
import math
from functools import wraps

from django.core.exceptions import RequestDataTooBig
from django.http import HttpResponse, JsonResponse
from django.views.decorators.csrf import csrf_exempt


logger = logging.getLogger('openscript.api')
MAX_JSON_BYTES = 256 * 1024


class ApiError(Exception):
    def __init__(self, code, message, status=400, fields=None, headers=None, details=None):
        super().__init__(message)
        self.code, self.message, self.status = code, message, status
        self.fields, self.headers, self.details = fields, headers or {}, details


def response(data, status=200):
    if status == 204:
        return HttpResponse(status=204)
    return JsonResponse(data, status=status, json_dumps_params={'ensure_ascii': False, 'allow_nan': False})


def error_response(error):
    body = {'code': error.code, 'message': error.message}
    if error.fields is not None:
        body['fields'] = error.fields
    if error.details is not None:
        body['details'] = error.details
    result = response({'error': body}, status=error.status)
    for name, value in error.headers.items():
        result[name] = str(value)
    return result


def form_errors(form):
    return form.errors.get_json_data(escape_html=False)


def check_fields(data, allowed):
    unknown = sorted(set(data) - set(allowed))
    if unknown:
        raise ApiError('validation_error', 'A requisição contém campos desconhecidos.', fields={
            key: [{'message': 'Campo desconhecido.', 'code': 'unknown_field'}] for key in unknown
        })


def _reject_constant(value):
    raise ValueError('Non-finite JSON number')


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def _validate_json_values(value):
    if isinstance(value, str):
        value.encode('utf-8')
    elif isinstance(value, float) and not math.isfinite(value):
        raise ValueError('Non-finite JSON number')
    elif isinstance(value, dict):
        for key, item in value.items():
            key.encode('utf-8')
            _validate_json_values(item)
    elif isinstance(value, list):
        for item in value:
            _validate_json_values(item)


def json_body(request):
    try:
        length = int(request.META.get('CONTENT_LENGTH') or 0)
        if length < 0:
            raise ValueError('Negative body length')
        if length > MAX_JSON_BYTES:
            raise ApiError('payload_too_large', 'Use uma requisição de até 256 KiB.', status=413)
    except (TypeError, ValueError):
        raise ApiError('invalid_json', 'O tamanho da requisição é inválido.')
    try:
        raw = request.body
    except RequestDataTooBig:
        raise ApiError('payload_too_large', 'Use uma requisição de até 256 KiB.', status=413)
    if len(raw) > MAX_JSON_BYTES:
        raise ApiError('payload_too_large', 'Use uma requisição de até 256 KiB.', status=413)
    if not raw:
        return {}
    if request.content_type != 'application/json':
        raise ApiError('unsupported_media_type', 'Envie Content-Type: application/json.', status=415)
    try:
        data = json.loads(raw.decode('utf-8'), parse_constant=_reject_constant, object_pairs_hook=_unique_object)
        _validate_json_values(data)
    except (ValueError, UnicodeDecodeError, RecursionError):
        raise ApiError('invalid_json', 'Envie um objeto JSON válido em UTF-8.')
    if not isinstance(data, dict):
        raise ApiError('invalid_json', 'A requisição deve ser um objeto JSON.')
    return data


def api_endpoint(methods, authenticated=False):
    """CSRF exemption is confined to JSON endpoints that never trust cookies."""
    methods = tuple(methods)

    def decorate(view):
        @csrf_exempt
        @wraps(view)
        def wrapped(request, *args, **kwargs):
            try:
                if request.method == 'OPTIONS':
                    result = response(None, status=204)
                    result['Allow'] = ', '.join((*methods, 'OPTIONS'))
                else:
                    if request.method not in methods:
                        raise ApiError('method_not_allowed', 'Método não permitido.', status=405,
                                       headers={'Allow': ', '.join((*methods, 'OPTIONS'))})
                    if authenticated:
                        from accounts.api_auth import authenticate_bearer
                        request.api_user, request.api_token = authenticate_bearer(request)
                    request.api_data = json_body(request) if request.method in {'POST', 'PUT', 'PATCH', 'DELETE'} else {}
                    result = view(request, *args, **kwargs)
            except ApiError as error:
                result = error_response(error)
            except Exception:
                # Native clients always receive JSON, including when DEBUG is on.
                # Do not log headers, request bodies, credentials, or token values.
                logger.exception('Native API request failed.')
                result = error_response(ApiError('internal_error', 'Não foi possível concluir a solicitação.', status=500))
            result['Cache-Control'] = 'no-store'
            result['X-Content-Type-Options'] = 'nosniff'
            return result
        return wrapped
    return decorate
