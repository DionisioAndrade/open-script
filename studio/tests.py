from django.contrib.auth import get_user_model
from django.test import TestCase
from django.urls import reverse


class StudioPageTests(TestCase):
    @classmethod
    def setUpTestData(cls):
        cls.user = get_user_model().objects.create_user(
            email='studio@example.com', password='Studio-password-482!', display_name='Studio'
        )

    def setUp(self):
        self.client.force_login(self.user)

    def test_home_renders_studio_and_loads_its_assets(self):
        response = self.client.get(reverse("studio:index"))

        self.assertEqual(response.status_code, 200)
        self.assertTemplateUsed(response, "studio/index.html")
        self.assertContains(response, 'lang="pt-BR"')
        self.assertContains(response, "/static/studio/styles.css")
        self.assertContains(response, "/static/studio/studio.js")

    def test_home_does_not_accept_uploads(self):
        response = self.client.post(reverse("studio:index"), {"video": "clip"})

        self.assertEqual(response.status_code, 405)

    def test_home_is_available_without_login(self):
        self.client.logout()
        response = self.client.get(reverse('studio:index'))
        self.assertEqual(response.status_code, 200)
        self.assertContains(response, 'id="camera-video"')
        self.assertContains(response, 'id="account-login-link"')
        self.assertNotContains(response, 'data-user-key=')
