from django.urls import path

from . import api_views


app_name = 'api_auth'
urlpatterns = [
    path('login/', api_views.login, name='login'),
    path('signup/', api_views.signup, name='signup'),
    path('logout/', api_views.logout, name='logout'),
    path('password-reset/', api_views.password_reset, name='password_reset'),
]
