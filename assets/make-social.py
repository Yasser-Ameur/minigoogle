# Builds assets/social-preview.png, the 1280x640 image GitHub shows when the
# repository link is shared (Settings, Social preview; upload by hand, there is
# no API for it). The light hero from capture.cjs, scaled to fit, on white.
#
#   python assets/make-social.py
from PIL import Image

W, H = 1280, 640
hero = Image.open("assets/hero-light@2x.png").convert("RGB")
scale = (W - 80) / hero.width
hero = hero.resize((W - 80, round(hero.height * scale)), Image.LANCZOS)
canvas = Image.new("RGB", (W, H), "white")
canvas.paste(hero.crop((0, 0, hero.width, H - 40)), (40, 40))
canvas.save("assets/social-preview.png", optimize=True)
print("assets/social-preview.png", canvas.size)
