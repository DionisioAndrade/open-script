from django import forms
from django.contrib.auth.forms import AuthenticationForm, BaseUserCreationForm

from . import legal_content
from .models import User


DUPLICATE_EMAIL = 'Este e-mail já está cadastrado.'
TERMS_REQUIRED = 'Leia e aceite os Termos de Uso para criar a conta.'
PRIVACY_REQUIRED = 'Leia e aceite a Política de Privacidade para criar a conta.'
LEGAL_VERSION_CHANGED = 'Os documentos foram atualizados. Leia e aceite a versão atual.'
LEGAL_READ_REQUIRED = 'Abra e leia este documento antes de aceitar.'


class LoginForm(AuthenticationForm):
    username = forms.EmailField(
        label='E-mail',
        max_length=254,
        widget=forms.EmailInput(attrs={'autocomplete': 'username', 'autocapitalize': 'none', 'spellcheck': 'false', 'maxlength': 254}),
        error_messages={'required': 'Informe seu e-mail.', 'invalid': 'Informe um e-mail válido.', 'max_length': 'Use um e-mail com até 254 caracteres.'},
    )
    password = forms.CharField(
        label='Senha',
        strip=False,
        max_length=128,
        widget=forms.PasswordInput(attrs={'autocomplete': 'current-password', 'maxlength': 128}),
        error_messages={'required': 'Informe sua senha.', 'max_length': 'Use uma senha com até 128 caracteres.'},
    )
    remember_me = forms.BooleanField(label='Manter conectado', required=False, initial=False)
    error_messages = {
        'invalid_login': 'E-mail ou senha incorretos.',
        'inactive': 'E-mail ou senha incorretos.',
    }

    def __init__(self, *args, blocked=False, **kwargs):
        self.blocked = blocked
        super().__init__(*args, **kwargs)

    def clean_username(self):
        return User.objects.normalize_email(self.cleaned_data['username'])

    def clean(self):
        if self.blocked:
            return self.cleaned_data
        return super().clean()


class SignupForm(BaseUserCreationForm):
    display_name = forms.CharField(
        label='Nome',
        max_length=100,
        widget=forms.TextInput(attrs={'autocomplete': 'name', 'maxlength': 100}),
        error_messages={'required': 'Informe seu nome.', 'max_length': 'Use no máximo 100 caracteres para o nome.'},
    )
    email = forms.EmailField(
        label='E-mail',
        max_length=254,
        widget=forms.EmailInput(attrs={'autocomplete': 'email', 'autocapitalize': 'none', 'spellcheck': 'false', 'maxlength': 254}),
        error_messages={'required': 'Informe seu e-mail.', 'invalid': 'Informe um e-mail válido.', 'max_length': 'Use um e-mail com até 254 caracteres.'},
    )
    password1 = forms.CharField(
        label='Senha',
        strip=False,
        max_length=128,
        widget=forms.PasswordInput(attrs={'autocomplete': 'new-password', 'minlength': 8, 'maxlength': 128}),
        error_messages={'required': 'Informe sua senha.', 'max_length': 'Use uma senha com até 128 caracteres.'},
    )
    password2 = forms.CharField(
        label='Confirmar senha',
        strip=False,
        max_length=128,
        widget=forms.PasswordInput(attrs={'autocomplete': 'new-password', 'minlength': 8, 'maxlength': 128}),
        error_messages={'required': 'Confirme sua senha.', 'max_length': 'Use uma senha com até 128 caracteres.'},
    )
    accept_terms = forms.BooleanField(
        label='Li e aceito os Termos de Uso', required=True, initial=False,
        error_messages={'required': TERMS_REQUIRED},
    )
    accept_privacy = forms.BooleanField(
        label='Li e aceito a Política de Privacidade', required=True, initial=False,
        error_messages={'required': PRIVACY_REQUIRED},
    )
    terms_version = forms.CharField(
        max_length=40, widget=forms.HiddenInput,
        error_messages={'required': LEGAL_VERSION_CHANGED, 'max_length': LEGAL_VERSION_CHANGED},
    )
    privacy_version = forms.CharField(
        max_length=40, widget=forms.HiddenInput,
        error_messages={'required': LEGAL_VERSION_CHANGED, 'max_length': LEGAL_VERSION_CHANGED},
    )
    error_messages = {'password_mismatch': 'As senhas não coincidem.'}

    class Meta:
        model = User
        fields = ('display_name', 'email')

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.fields['terms_version'].initial = legal_content.TERMS_VERSION
        self.fields['privacy_version'].initial = legal_content.PRIVACY_VERSION

    def clean_terms_version(self):
        value = self.cleaned_data['terms_version']
        if value != legal_content.TERMS_VERSION:
            raise forms.ValidationError(LEGAL_VERSION_CHANGED, code='stale_version')
        return value

    def clean_privacy_version(self):
        value = self.cleaned_data['privacy_version']
        if value != legal_content.PRIVACY_VERSION:
            raise forms.ValidationError(LEGAL_VERSION_CHANGED, code='stale_version')
        return value

    def clean_email(self):
        email = User.objects.normalize_email(self.cleaned_data['email'])
        if User.objects.filter(email__iexact=email).exists():
            raise forms.ValidationError(DUPLICATE_EMAIL, code='duplicate_email')
        return email
