import django.db.models.deletion
import django.utils.timezone
from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):
    dependencies = [
        ('accounts', '0001_initial'),
    ]

    operations = [
        migrations.CreateModel(
            name='LegalAcceptance',
            fields=[
                ('id', models.BigAutoField(auto_created=True, primary_key=True, serialize=False, verbose_name='ID')),
                ('terms_version', models.CharField(max_length=40, verbose_name='versão dos termos')),
                ('privacy_version', models.CharField(max_length=40, verbose_name='versão da política')),
                ('accepted_at', models.DateTimeField(default=django.utils.timezone.now, editable=False, verbose_name='aceito em')),
                ('user', models.OneToOneField(on_delete=django.db.models.deletion.CASCADE, related_name='legal_acceptance', to=settings.AUTH_USER_MODEL)),
            ],
        ),
    ]
