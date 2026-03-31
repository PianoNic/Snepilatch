"""
Generate product mockup image for README / app store listings.
Usage: python scripts/generate_mockup.py

Reads screenshots from assets/ and outputs assets/product_mockup.png
"""
from PIL import Image, ImageDraw, ImageFilter
import os

ASSETS_DIR = os.path.join(os.path.dirname(__file__), '..', 'assets')
SCREENSHOTS = [
    'screenshot_home.PNG',
    'screenshot_player.PNG',
    'screenshot_search.PNG',
    'screenshot_library.PNG',
]

CANVAS_W, CANVAS_H = 1400, 750
TARGET_H = 520
CORNER_RADIUS = 18

# App brand colors
BG_COLOR_TOP = (18, 18, 18)       # #121212
BG_COLOR_BOTTOM = (20, 22, 30)
ACCENT_GREEN = (29, 185, 84)      # #1DB954


def create_gradient_background(w, h):
    canvas = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)
    for y in range(h):
        t = y / h
        r = int(BG_COLOR_TOP[0] + (BG_COLOR_BOTTOM[0] - BG_COLOR_TOP[0]) * t)
        g = int(BG_COLOR_TOP[1] + (BG_COLOR_BOTTOM[1] - BG_COLOR_TOP[1]) * t)
        b = int(BG_COLOR_TOP[2] + (BG_COLOR_BOTTOM[2] - BG_COLOR_TOP[2]) * t)
        draw.line([(0, y), (w, y)], fill=(r, g, b, 255))

    # Subtle green glow at bottom center
    glow = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    cx, cy = w // 2, h + 100
    for radius in range(400, 0, -1):
        alpha = int(12 * (1 - radius / 400))
        glow_draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                          fill=(ACCENT_GREEN[0], ACCENT_GREEN[1], ACCENT_GREEN[2], alpha))
    glow = glow.filter(ImageFilter.GaussianBlur(40))
    return Image.alpha_composite(canvas, glow)


def add_rounded_corners(img, radius=CORNER_RADIUS):
    mask = Image.new('L', img.size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius=radius, fill=255)
    result = Image.new('RGBA', img.size, (0, 0, 0, 0))
    result.paste(img, mask=mask)
    return result


def apply_perspective(img, direction, strength=0.12):
    w, h = img.size
    s = strength
    if direction == 'left':
        coeffs = [1 + s * 0.3, s * 0.15, -w * s * 0.1,
                  s * 0.05, 1 + s * 0.1, 0,
                  s * 0.0004, s * 0.00008, 1]
    elif direction == 'right':
        coeffs = [1 + s * 0.3, -s * 0.15, w * s * 0.05,
                  -s * 0.05, 1 + s * 0.1, 0,
                  -s * 0.0004, s * 0.00008, 1]
    elif direction == 'slight_left':
        s *= 0.5
        coeffs = [1 + s * 0.2, s * 0.1, -w * s * 0.05,
                  s * 0.03, 1 + s * 0.05, 0,
                  s * 0.0003, s * 0.00005, 1]
    else:  # slight_right
        s *= 0.5
        coeffs = [1 + s * 0.2, -s * 0.1, w * s * 0.03,
                  -s * 0.03, 1 + s * 0.05, 0,
                  -s * 0.0003, s * 0.00005, 1]
    return img.transform((int(w * 1.2), int(h * 1.1)), Image.PERSPECTIVE, coeffs, Image.BICUBIC)


def add_shadow(img, offset=10, blur=20, opacity=100):
    shadow = Image.new('RGBA', (img.size[0] + blur * 2 + offset, img.size[1] + blur * 2 + offset), (0, 0, 0, 0))
    shadow_base = Image.new('RGBA', img.size, (0, 0, 0, opacity))
    if img.mode == 'RGBA':
        shadow_base.putalpha(img.split()[3])
    shadow.paste(shadow_base, (blur + offset, blur + offset))
    shadow = shadow.filter(ImageFilter.GaussianBlur(blur))
    shadow.paste(img, (blur, blur), img)
    return shadow


def generate():
    imgs = [Image.open(os.path.join(ASSETS_DIR, s)).convert('RGBA') for s in SCREENSHOTS]
    canvas = create_gradient_background(CANVAS_W, CANVAS_H)

    # Resize and round corners
    processed = []
    for img in imgs:
        ratio = TARGET_H / img.size[1]
        resized = img.resize((int(img.size[0] * ratio), TARGET_H), Image.LANCZOS)
        processed.append(add_rounded_corners(resized))

    # Perspective configs: (direction, strength)
    configs = [
        ('left', 0.14),
        ('slight_left', 0.08),
        ('slight_right', 0.08),
        ('right', 0.14),
    ]

    final_phones = []
    for i, (direction, strength) in enumerate(configs):
        phone = apply_perspective(processed[i], direction, strength)
        final_phones.append(add_shadow(phone, offset=8, blur=15, opacity=80))

    # Position with overlap
    total_width = sum(p.size[0] for p in final_phones) - 180
    x = (CANVAS_W - total_width) // 2
    y_offsets = [60, 30, 30, 60]

    for i, phone in enumerate(final_phones):
        canvas.paste(phone, (x, y_offsets[i]), phone)
        x += phone.size[0] - 60

    out = os.path.join(ASSETS_DIR, 'product_mockup.png')
    canvas.save(out, 'PNG')
    print(f'Saved {CANVAS_W}x{CANVAS_H} -> {out}')


if __name__ == '__main__':
    generate()
