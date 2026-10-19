from django.urls import include, path

from accounts import api_views as accounts

from . import views


app_name = 'api'
urlpatterns = [
    path('config/', views.config, name='config'),
    path('openapi.json', views.schema, name='schema'),
    path('export-profiles/', views.export_profiles, name='export_profiles'),
    path('auth/', include('accounts.api_urls')),
    path('me/', accounts.me, name='me'),
    path('legal/read/', accounts.legal_read, name='legal_read'),
    path('legal/<str:kind>/', accounts.legal_document, name='legal_document'),
    path('', include('studio.api_urls')),
    path('', views.not_found),
    path('<path:unmatched>', views.not_found),
]
