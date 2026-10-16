"""Generates the Mod Menu icon: a periodic-table tile for argon (Ar), in the style of QuantumMending's Md icon.

Usage: python3 tools/make_icon.py 18 39.95 Ar "Quantum Arrows" mod/src/main/resources/assets/quantumarrows/icon.png
Needs Pillow and the DejaVu Sans Bold font.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

SS = 4  # supersampling
N = 512 * SS
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
GREEN = (61, 220, 151, 255)
INNER = (20, 18, 63, 255)

def font(size):
    return ImageFont.truetype(FONT, int(size * SS))

def make(number, mass, symbol, label, out):
    # Background: vertical gradient in a rounded square.
    top, bottom = (46, 39, 132), (27, 23, 96)
    grad = Image.new("RGBA", (N, N))
    gd = ImageDraw.Draw(grad)
    for y in range(N):
        t = y / (N - 1)
        gd.line([(0, y), (N, y)], fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,))
    mask = Image.new("L", (N, N), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, N - 1, N - 1), radius=96 * SS, fill=255)
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)
    # Element tile.
    d.rounded_rectangle((73 * SS, 73 * SS, 438 * SS, 438 * SS), radius=46 * SS, fill=GREEN)
    d.rounded_rectangle((91 * SS, 91 * SS, 420 * SS, 420 * SS), radius=30 * SS, fill=INNER)

    d.text((118 * SS, 104 * SS), number, font=font(54), fill=(255, 255, 255, 255))
    d.text((397 * SS, 134 * SS), mass, font=font(28), fill=(170, 175, 255, 255), anchor="rm")
    # Symbol, centered on the tile, cap height like the reference.
    f = font(172)
    d.text((256 * SS, 322 * SS), symbol, font=f, fill=GREEN, anchor="ms")
    d.text((256 * SS, 380 * SS), label, font=font(28.5), fill=(232, 232, 250, 255), anchor="mm")
    img.resize((512, 512), Image.LANCZOS).save(out)

if __name__ == "__main__":
    make(*sys.argv[1:6])
