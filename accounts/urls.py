from django.urls import path

from . import views
from .password_reset import RequestResetView, ResetDoneView, ResetConfirmView, ResetCompleteView
from . import google


app_name = 'accounts'
urlpatterns = [
    path('login/', views.login, name='login'),
    path('signup/', views.signup, name='signup'),
    path('google/login/', google.google_login, name='google_login'),
    path('google/login/callback/', google.google_callback, name='google_callback'),
    path('google/signup/', google.google_signup, name='google_signup'),
    path('password-reset/', RequestResetView.as_view(), name='password_reset'),
    path('password-reset/done/', ResetDoneView.as_view(), name='password_reset_done'),
    path('reset/<uidb64>/<token>/', ResetConfirmView.as_view(), name='password_reset_confirm'),
    path('reset/complete/', ResetCompleteView.as_view(), name='password_reset_complete'),
    path('logout/', views.logout, name='logout'),
    path('session/', views.session, name='session'),
    path('terms/', views.legal_document, {'kind': 'terms'}, name='terms'),
    path('privacy/', views.legal_document, {'kind': 'privacy'}, name='privacy'),
    path('legal-read/', views.legal_read, name='legal_read'),
]
