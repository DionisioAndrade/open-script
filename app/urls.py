from django.urls import include, path
from accounts.google import google_signup

urlpatterns = [
    path('api/v1/', include('api.urls')),
    path('accounts/', include('accounts.urls')),
    path('accounts/google/signup/', google_signup, name='socialaccount_signup'),
    path('', include('studio.urls')),
]
