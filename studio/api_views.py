import math
import re

from django.contrib.auth import get_user_model
from django.db import transaction
from django.db.models import F
from django.utils import timezone

from api.common import ApiError, api_endpoint, check_fields, response

from .models import Script, StudioPreferences


MAX_SCRIPT_BYTES = 100 * 1024
MAX_SCRIPTS_PER_USER = 1000
MAX_REVISION = 2_147_483_647
DEFAULT_PREFERENCES = {
    'language': 'pt-BR',
    'speed': 40,
    'font': 'sans',
    'size': 36,
    'auto_size': True,
    'opacity': 0,
    'color': '#ffffff',
    'show_prompt': True,
    'mirror': True,
}


def invalid_field(name, message):
    raise ApiError(
        'validation_error', 'Verifique os campos informados.',
        fields={name: [{'message': message, 'code': 'invalid'}]},
    )


def required_revision(data):
    revision = data.get('revision')
    if type(revision) is not int or not 1 <= revision <= MAX_REVISION:
        invalid_field('revision', f'Informe um inteiro de 1 a {MAX_REVISION}.')
    return revision


def validate_script(data, *, partial=False):
    allowed = {'title', 'content', 'revision'} if partial else {'title', 'content'}
    check_fields(data, allowed)
    updates = {}
    for name in ('title', 'content'):
        if name not in data:
            if not partial:
                invalid_field(name, 'Este campo é obrigatório.')
            continue
        value = data[name]
        if not isinstance(value, str):
            invalid_field(name, 'Informe um texto.')
        if name == 'title' and len(value) > 100:
            invalid_field(name, 'Use no máximo 100 caracteres.')
        if name == 'content':
            try:
                size = len(value.encode('utf-8'))
            except UnicodeEncodeError:
                invalid_field(name, 'Informe um texto Unicode válido.')
            if size > MAX_SCRIPT_BYTES:
                invalid_field(name, 'Use no máximo 100 KiB de texto em UTF-8.')
        # Lone surrogate escapes are not valid UTF-8 text, even in a short title.
        if name == 'title':
            try:
                value.encode('utf-8')
            except UnicodeEncodeError:
                invalid_field(name, 'Informe um texto Unicode válido.')
        updates[name] = value
    return updates


def script_data(script):
    return {
        'id': str(script.pk),
        'title': script.title,
        'content': script.content,
        'revision': script.revision,
        'created_at': script.created_at.isoformat(),
        'updated_at': script.updated_at.isoformat(),
    }


def pagination_value(request, name, default, *, minimum, maximum=None):
    values = request.GET.getlist(name)
    if not values:
        return default
    raw = values[0]
    if len(values) != 1 or not re.fullmatch(r'[0-9]+', raw):
        invalid_field(name, 'Informe um inteiro válido.')
    try:
        value = int(raw)
    except ValueError:
        invalid_field(name, 'Informe um inteiro válido.')
    if value < minimum or (maximum is not None and value > maximum):
        message = f'Informe um inteiro de {minimum} a {maximum}.' if maximum is not None else f'Informe um inteiro maior ou igual a {minimum}.'
        invalid_field(name, message)
    return value


def owned_script(user, script_id):
    script = Script.objects.filter(owner=user, pk=script_id).first()
    if script is None:
        raise ApiError('not_found', 'Roteiro não encontrado.', status=404)
    return script


def conflict(current_revision):
    raise ApiError(
        'revision_conflict', 'Este recurso foi alterado. Busque a versão atual antes de salvar.',
        status=409, details={'current_revision': current_revision},
    )


@api_endpoint(['GET', 'POST'], authenticated=True)
def scripts(request):
    if request.method == 'GET':
        limit = pagination_value(request, 'limit', 20, minimum=1, maximum=100)
        offset = pagination_value(request, 'offset', 0, minimum=0)
        queryset = Script.objects.filter(owner=request.api_user)
        total = queryset.count()
        items = [] if offset >= total else [script_data(item) for item in queryset[offset:offset + limit]]
        return response({
            'items': items, 'total': total, 'limit': limit, 'offset': offset,
        })

    values = validate_script(request.api_data)
    with transaction.atomic():
        # Serializes concurrent creations for one account on databases with row locks.
        get_user_model().objects.select_for_update().get(pk=request.api_user.pk)
        if Script.objects.filter(owner=request.api_user).count() >= MAX_SCRIPTS_PER_USER:
            raise ApiError('script_limit_reached', 'O limite de 1000 roteiros por conta foi atingido.', status=409)
        script = Script.objects.create(owner=request.api_user, **values)
    return response(script_data(script), status=201)


@api_endpoint(['GET', 'PATCH', 'DELETE'], authenticated=True)
def script_detail(request, script_id):
    # Resolve ownership before validating writes, so other accounts always receive 404.
    script = owned_script(request.api_user, script_id)
    if request.method == 'GET':
        return response(script_data(script))

    if request.method == 'PATCH':
        updates = validate_script(request.api_data, partial=True)
    else:
        check_fields(request.api_data, {'revision'})
        updates = None
    revision = required_revision(request.api_data)

    with transaction.atomic():
        matching = Script.objects.filter(owner=request.api_user, pk=script_id, revision=revision)
        if request.method == 'DELETE':
            deleted, _ = matching.delete()
            if not deleted:
                conflict(owned_script(request.api_user, script_id).revision)
            return response(None, status=204)

        if revision == MAX_REVISION:
            raise ApiError('revision_limit_reached', 'O limite de revisões deste roteiro foi atingido.', status=409)
        changed = matching.update(**updates, revision=F('revision') + 1, updated_at=timezone.now())
        if not changed:
            conflict(owned_script(request.api_user, script_id).revision)
        script = owned_script(request.api_user, script_id)
    return response(script_data(script))


def validate_preferences(data):
    check_fields(data, set(DEFAULT_PREFERENCES) | {'revision'})
    updates = {}
    for name, value in data.items():
        if name == 'revision':
            continue
        if name in ('auto_size', 'show_prompt', 'mirror'):
            if type(value) is not bool:
                invalid_field(name, 'Informe true ou false.')
        elif name in ('speed', 'size', 'opacity'):
            bounds = {'speed': (10, 100), 'size': (20, 120), 'opacity': (0, 70)}
            low, high = bounds[name]
            if type(value) not in (int, float) or not low <= value <= high or not math.isfinite(value):
                invalid_field(name, f'Informe um número finito de {low} a {high}.')
        elif name in ('language', 'font'):
            choices = {'language': ('pt-BR', 'en', 'es'), 'font': ('sans', 'serif', 'mono')}
            if not isinstance(value, str) or value not in choices[name]:
                invalid_field(name, f'Escolha um destes valores: {", ".join(choices[name])}.')
        elif name == 'color':
            if not isinstance(value, str) or not re.fullmatch(r'#[0-9a-fA-F]{6}', value):
                invalid_field(name, 'Informe uma cor no formato #RRGGBB.')
            value = value.lower()
        updates[name] = value
    return updates


def preferences_data(prefs):
    return {
        **DEFAULT_PREFERENCES, **prefs.data,
        'revision': prefs.revision, 'updated_at': prefs.updated_at.isoformat(),
    }


@api_endpoint(['GET', 'PATCH'], authenticated=True)
def preferences(request):
    if request.method == 'GET':
        prefs, _ = StudioPreferences.objects.get_or_create(
            owner=request.api_user, defaults={'data': DEFAULT_PREFERENCES.copy()},
        )
        return response(preferences_data(prefs))

    updates = validate_preferences(request.api_data)
    revision = required_revision(request.api_data)
    with transaction.atomic():
        prefs, _ = StudioPreferences.objects.select_for_update().get_or_create(
            owner=request.api_user, defaults={'data': DEFAULT_PREFERENCES.copy()},
        )
        if prefs.revision != revision:
            conflict(prefs.revision)
        if revision == MAX_REVISION:
            raise ApiError('revision_limit_reached', 'O limite de revisões das preferências foi atingido.', status=409)
        merged = {**DEFAULT_PREFERENCES, **prefs.data, **updates}
        changed = StudioPreferences.objects.filter(owner=request.api_user, revision=revision).update(
            data=merged, revision=F('revision') + 1, updated_at=timezone.now(),
        )
        if not changed:
            prefs.refresh_from_db()
            conflict(prefs.revision)
        prefs.refresh_from_db()
    return response(preferences_data(prefs))
