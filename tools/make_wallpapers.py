# Built-in wallpapers: soft abstract light on a dark base, rendered at 8K (7680x4320)
# straight from formulas, strip by strip, so memory stays low. A faint grain keeps
# the gradients free of banding on 10-bit and 8-bit panels alike.
import sys, numpy as np
from PIL import Image
W, H, STRIP = 7680, 4320, 270
OUT = sys.argv[1]
THEMES = {
    # name: base colour, [(x, y, radius, colour), ...]  (x, y, radius in screen fractions)
    "aurora":   ((10, 14, 30),  [(0.15, 0.20, 0.60, (30, 80, 170)), (0.75, 0.15, 0.55, (110, 50, 160)),
                                 (0.55, 0.90, 0.65, (10, 120, 130))]),
    "dusk":     ((22, 12, 20),  [(0.20, 0.85, 0.70, (170, 70, 40)), (0.80, 0.30, 0.55, (140, 40, 90)),
                                 (0.50, 0.05, 0.45, (60, 40, 110))]),
    "lagoon":   ((6, 20, 26),   [(0.10, 0.10, 0.55, (10, 110, 120)), (0.70, 0.70, 0.70, (20, 70, 150)),
                                 (0.95, 0.10, 0.40, (40, 140, 110))]),
    "graphite": ((16, 17, 20),  [(0.25, 0.30, 0.70, (60, 66, 78)), (0.85, 0.80, 0.60, (44, 50, 64))]),
}
rng = np.random.default_rng(7)
for name, (base, blobs) in THEMES.items():
    img = Image.new("RGB", (W, H))
    xs = (np.arange(W, dtype=np.float32) + 0.5) / W
    for top in range(0, H, STRIP):
        ys = (np.arange(top, top + STRIP, dtype=np.float32) + 0.5) / H
        y, x = np.meshgrid(ys, xs, indexing="ij")
        px = np.empty((STRIP, W, 3), np.float32)
        px[:] = base
        for cx, cy, r, col in blobs:
            d = np.sqrt(((x - cx) * 16 / 9) ** 2 + (y - cy) ** 2) / r
            w = np.clip(1 - d, 0, 1)
            px += (w * w * (3 - 2 * w))[..., None] * np.array(col, np.float32)  # smoothstep
        px += rng.normal(0, 0.9, (STRIP, W, 1)).astype(np.float32)          # grain
        img.paste(Image.fromarray(np.clip(px, 0, 255).astype(np.uint8)), (0, top))
    path = f"{OUT}/{name}.jpg"
    img.save(path, quality=86, subsampling=2, optimize=True)
    print(name, path)
