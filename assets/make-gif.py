# Assembles assets/demo.gif from the six stills capture.cjs wrote to assets/scenes/.
# Each scene holds 3 s; six scenes give an 18 s loop at 960 px wide.
#
#   python assets/make-gif.py
import glob
from PIL import Image

frames = [Image.open(p).convert("RGB").resize((960, 600)) for p in sorted(glob.glob("assets/scenes/*.png"))]
frames[0].save("assets/demo.gif", save_all=True, append_images=frames[1:], duration=3000, loop=0, optimize=True)
print(len(frames), "scenes ->", "assets/demo.gif")
