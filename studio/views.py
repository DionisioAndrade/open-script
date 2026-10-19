from django.shortcuts import render
from django.views.decorators.cache import never_cache
from django.views.decorators.http import require_GET

from .catalog import export_catalog


@require_GET
@never_cache
def index(request):
    return render(request, "studio/index.html", {'export_catalog': export_catalog()})
