import uuid

from django.contrib.auth.base_user import BaseUserManager
from django.contrib.auth.models import AbstractUser
from django.conf import settings
from django.db import models
from django.db.models.functions import Lower
from django.utils import timezone


class UserManager(BaseUserManager):
    use_in_migrations = True

    @classmethod
    def normalize_email(cls, email):
        return super().normalize_email(email.strip() if email else '').casefold()

    def get_by_natural_key(self, email):
        return self.get(email=self.normalize_email(email))

    async def aget_by_natural_key(self, email):
        return await self.aget(email=self.normalize_email(email))

    def _create_user(self, email, password, **extra_fields):
        if not email:
            raise ValueError('Informe um e-mail para criar a conta.')
        user = self.model(email=self.normalize_email(email), **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_user(self, email, password=None, **extra_fields):
        extra_fields.setdefault('is_staff', False)
        extra_fields.setdefault('is_superuser', False)
        return self._create_user(email, password, **extra_fields)

    def create_superuser(self, email, password=None, **extra_fields):
        extra_fields.setdefault('is_staff', True)
        extra_fields.setdefault('is_superuser', True)
        if extra_fields.get('is_staff') is not True:
            raise ValueError('Um superusuário deve ter is_staff=True.')
        if extra_fields.get('is_superuser') is not True:
            raise ValueError('Um superusuário deve ter is_superuser=True.')
        return self._create_user(email, password, **extra_fields)


class User(AbstractUser):
    username = None
    email = models.EmailField('e-mail', unique=True)
    display_name = models.CharField('nome', max_length=100)
    storage_id = models.UUIDField(default=uuid.uuid4, unique=True, editable=False)

    USERNAME_FIELD = 'email'
    REQUIRED_FIELDS = ['display_name']

    objects = UserManager()

    class Meta(AbstractUser.Meta):
        constraints = [models.UniqueConstraint(Lower('email'), name='accounts_user_email_case_unique')]

    def clean(self):
        super().clean()
        self.email = type(self).objects.normalize_email(self.email)

    def save(self, *args, **kwargs):
        self.email = type(self).objects.normalize_email(self.email)
        super().save(*args, **kwargs)

    def get_full_name(self):
        return self.display_name

    def get_short_name(self):
        return self.display_name

    def __str__(self):
        return self.email


class LegalAcceptance(models.Model):
    """Versões efetivamente aceitas no cadastro, sem coletar IP ou dispositivo."""

    user = models.OneToOneField(
        settings.AUTH_USER_MODEL, on_delete=models.CASCADE,
        related_name='legal_acceptance',
    )
    terms_version = models.CharField('versão dos termos', max_length=40)
    privacy_version = models.CharField('versão da política', max_length=40)
    accepted_at = models.DateTimeField('aceito em', default=timezone.now, editable=False)

    def __str__(self):
        return f'Aceite de {self.user_id} em {self.accepted_at}'


class NativeAccessToken(models.Model):
    """Revocable native access, without storing the bearer secret itself."""

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL, on_delete=models.CASCADE,
        related_name='native_access_tokens',
    )
    token_hash = models.CharField(max_length=64, unique=True, editable=False)
    password_fingerprint = models.CharField(max_length=64, editable=False)
    created_at = models.DateTimeField(default=timezone.now, editable=False)
    expires_at = models.DateTimeField()
    revoked_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        indexes = [models.Index(fields=['user', 'expires_at'], name='native_token_user_expiry')]

    def __str__(self):
        return f'Acesso nativo de {self.user_id}'
