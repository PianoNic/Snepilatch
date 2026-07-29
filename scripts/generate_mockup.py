"""
Generate the product mockup banner for the README.
Usage: python scripts/generate_mockup.py [seed]

Reads the screenshots from assets/ and writes assets/product_mockup.png.

Layout: an isometric weave of every screenshot, with one upright hero standing in the middle. The
lattice is exact so the field reads as a pattern rather than a scatter; only WHICH screen lands in
each slot is shuffled, drawn from a bag so no screen repeats until all of them have appeared. Pass a
different seed for a different arrangement of the same screens.
"""
from PIL import Image, ImageDraw, ImageFilter
import os
import random
import sys

ASSETS_DIR = os.path.join(os.path.dirname(__file__), '..', 'assets')

# Every screen we have. Order only affects the shuffle, not the composition.
SCREENSHOTS = [
    'screenshot_library.PNG',
    'screenshot_player_alt.PNG',
    'screenshot_lyrics.PNG',
    'screenshot_home.PNG',
    'screenshot_player_wave.PNG',
    'screenshot_equalizer.PNG',
    'screenshot_playlist.PNG',
    'screenshot_player.PNG',
    'screenshot_search.PNG',
]

# The one screen shown upright and full size.
HERO = 'screenshot_player.PNG'

CANVAS_W, CANVAS_H = 2600, 1180
OUTPUT_W = 1800       # composed large, delivered smaller: sharper edges, a third of the file size
HERO_H = 780          # height of the upright hero
TILE_H = 460          # height of each tilted screen in the field
TILE_ANGLE = -30      # degrees; one angle for the whole field keeps it isometric
COL_STEP = 0.78       # horizontal spacing, as a fraction of a tile's width
ROW_STEP = 0.33       # vertical spacing, as a fraction of a tile's height
CORNER_RADIUS = 18
DEFAULT_SEED = 11

BG_COLOR_TOP = (18, 18, 18)       # #121212
BG_COLOR_BOTTOM = (20, 22, 30)
ACCENT_GREEN = (29, 185, 84)      # #1DB954


def create_gradient_background(w, h):
    canvas = Image.new('RGBA', (w, h))
    draw = ImageDraw.Draw(canvas)
    for y in range(h):
        t = y / max(1, h)
        draw.line([(0, y), (w, y)], fill=(
            int(BG_COLOR_TOP[0] + (BG_COLOR_BOTTOM[0] - BG_COLOR_TOP[0]) * t),
            int(BG_COLOR_TOP[1] + (BG_COLOR_BOTTOM[1] - BG_COLOR_TOP[1]) * t),
            int(BG_COLOR_TOP[2] + (BG_COLOR_BOTTOM[2] - BG_COLOR_TOP[2]) * t), 255))

    glow = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    cx, cy = w // 2, h + 80
    for radius in range(420, 0, -1):
        alpha = int(14 * (1 - radius / 420))
        glow_draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                          fill=(*ACCENT_GREEN, alpha))
    return Image.alpha_composite(canvas, glow.filter(ImageFilter.GaussianBlur(40)))


def add_rounded_corners(img, radius=CORNER_RADIUS):
    mask = Image.new('L', img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1],
                                           radius=radius, fill=255)
    result = Image.new('RGBA', img.size, (0, 0, 0, 0))
    result.paste(img, mask=mask)
    return result


def add_shadow(img, blur=16, opacity=95, offset=10):
    pad = blur * 2
    layer = Image.new('RGBA', (img.size[0] + pad, img.size[1] + pad + offset), (0, 0, 0, 0))
    base = Image.new('RGBA', img.size, (0, 0, 0, opacity))
    base.putalpha(img.split()[3])
    layer.paste(base, (blur, blur + offset))
    layer = layer.filter(ImageFilter.GaussianBlur(blur))
    layer.paste(img, (blur, blur), img)
    return layer


def load_screen(name, height):
    img = Image.open(os.path.join(ASSETS_DIR, name)).convert('RGBA')
    ratio = height / img.size[1]
    return add_rounded_corners(img.resize((int(img.size[0] * ratio), height), Image.LANCZOS))


def build_field(seed):
    """The tilted lattice that fills the canvas and bleeds off every edge."""
    rnd = random.Random(seed)
    tiles = [add_shadow(load_screen(name, TILE_H).rotate(TILE_ANGLE, resample=Image.BICUBIC, expand=True))
             for name in SCREENSHOTS]
    tw, th = tiles[0].size
    dx, dy = int(tw * COL_STEP), int(th * ROW_STEP)

    layer = Image.new('RGBA', (CANVAS_W, CANVAS_H), (0, 0, 0, 0))
    bag = []
    for r in range(-2, CANVAS_H // dy + 3):
        # Alternate rows shift half a column, which is what turns a grid into a weave.
        x = -tw // 2 + (dx // 2 if r % 2 else 0)
        while x < CANVAS_W + tw // 2:
            if not bag:
                bag = tiles[:]
                rnd.shuffle(bag)
            layer.paste(bag[-1], (x, r * dy - th // 2), bag.pop())
            x += dx
    return Image.alpha_composite(create_gradient_background(CANVAS_W, CANVAS_H), layer)


def dim_towards_centre(canvas):
    """Fade the field down in the middle so the hero separates from it instead of competing."""
    veil = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(veil)
    cx, cy = canvas.size[0] // 2, canvas.size[1] // 2
    reach = int(canvas.size[0] * 0.40)
    for radius in range(reach, 0, -3):
        alpha = int(140 * (1 - radius / reach))
        draw.ellipse([cx - radius, cy - int(radius * 0.74), cx + radius, cy + int(radius * 0.74)],
                     fill=(10, 11, 13, alpha))
    return Image.alpha_composite(canvas, veil.filter(ImageFilter.GaussianBlur(70)))


def generate(seed=DEFAULT_SEED):
    canvas = dim_towards_centre(build_field(seed))
    hero = add_shadow(load_screen(HERO, HERO_H), blur=34, opacity=170)
    cx, cy = CANVAS_W // 2, CANVAS_H // 2
    canvas.paste(hero, (cx - hero.size[0] // 2, cy - hero.size[1] // 2), hero)

    height = round(CANVAS_H * OUTPUT_W / CANVAS_W)
    canvas = canvas.convert('RGB').resize((OUTPUT_W, height), Image.LANCZOS)

    out = os.path.join(ASSETS_DIR, 'product_mockup.png')
    canvas.save(out, 'PNG', optimize=True)
    size_mb = os.path.getsize(out) / 1024 / 1024
    print(f'Saved {OUTPUT_W}x{height} ({size_mb:.1f} MB, seed {seed}) -> {out}')


if __name__ == '__main__':
    generate(int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_SEED)
