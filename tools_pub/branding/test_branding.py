"""Branding resource checks: python -B -m unittest discover -s tools_pub/branding."""

import unittest
import colorsys
import xml.etree.ElementTree as ET

from PIL import Image

from export_vivo_icon import OUTPUT, ROOT, SOURCE


RESOURCES = ROOT / "app/src/vivoRelease/res"


class BrandingTest(unittest.TestCase):
    def test_source_and_android_export_keep_transparent_background_and_opening(self):
        for path in (SOURCE, OUTPUT):
            with self.subTest(path=path), Image.open(path) as artwork:
                self.assertEqual("RGBA", artwork.mode)
                self.assertEqual(0, artwork.getpixel((0, 0))[3])
                self.assertEqual(0, artwork.getpixel((artwork.width // 2, artwork.height // 2))[3])
                self.assertGreater(artwork.getchannel("A").getextrema()[1], 250)
        with Image.open(OUTPUT) as artwork:
            self.assertEqual((432, 432), artwork.size)

    def test_adaptive_icon_background_is_bright_low_saturation_orange(self):
        colors = ET.parse(RESOURCES / "values/colors.xml").getroot()
        color = colors.find("color[@name='vivo_brand_background']").text
        self.assertEqual("#FFF1E1", color)
        rgb = tuple(int(color[i:i + 2], 16) / 255 for i in (1, 3, 5))
        hue, saturation, brightness = colorsys.rgb_to_hsv(*rgb)
        self.assertTrue(20 <= hue * 360 <= 40)
        self.assertLessEqual(saturation, 0.15)
        self.assertGreaterEqual(brightness, 0.95)
        for name in ("ic_launcher", "ic_launcher_round"):
            icon = ET.parse(RESOURCES / f"mipmap-anydpi/{name}.xml").getroot()
            self.assertEqual(
                "@color/vivo_brand_background",
                icon.find("background").get("{http://schemas.android.com/apk/res/android}drawable"),
            )

    def test_all_locales_use_original_app_name_and_keep_adaptation_credit(self):
        for locale in ("values", "values-zh", "values-en"):
            with self.subTest(locale=locale):
                strings = ET.parse(RESOURCES / locale / "strings.xml").getroot()
                self.assertEqual("NeriPlayer", strings.find("string[@name='app_name']").text)
                footer = strings.find("string[@name='about_app_footer']").text
                self.assertIn("GPLv3", footer)
                self.assertIn("适配版作者：DongLan", footer)


if __name__ == "__main__":
    unittest.main()
