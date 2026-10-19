from django.urls import path

from . import api_views

app_name = 'studio_api'

urlpatterns = [
    path('scripts/', api_views.scripts, name='scripts'),
    path('scripts/<uuid:script_id>/', api_views.script_detail, name='script-detail'),
    path('preferences/', api_views.preferences, name='preferences'),
]
