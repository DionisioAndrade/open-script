import django.db.models.deletion
import django.utils.timezone
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ('accounts', '0002_legalacceptance'),
    ]

    operations = [
        migrations.CreateModel(
            name='NativeAccessToken',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('token_hash', models.CharField(editable=False, max_length=64, unique=True)),
                ('password_fingerprint', models.CharField(editable=False, max_length=64)),
                ('created_at', models.DateTimeField(default=django.utils.timezone.now, editable=False)),
                ('expires_at', models.DateTimeField()),
                ('revoked_at', models.DateTimeField(blank=True, null=True)),
                ('user', models.ForeignKey(on_delete=django.db.models.deletion.CASCADE, related_name='native_access_tokens', to=settings.AUTH_USER_MODEL)),
            ],
            options={
                'indexes': [models.Index(fields=['user', 'expires_at'], name='native_token_user_expiry')],
            },
        ),
    ]
