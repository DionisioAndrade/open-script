from django.conf import settings
from allauth.socialaccount.models import SocialAccount


def authentication_options(request):
    """Expose availability only; credentials never belong in page context."""
    return {
        'google_enabled': settings.OPENSCRIPT_GOOGLE_ENABLED,
        'reset_email_enabled': settings.OPENSCRIPT_PASSWORD_RESET_ENABLED,
        'google_connected': request.user.is_authenticated and SocialAccount.objects.filter(user=request.user, provider='google').exists(),
    }
