"""Render a quick stroke preview from an .abr written by omnibrush (sanity check, not a paint engine)."""
import sys

import numpy as np
from PIL import Image

from omnibrush.abr import read_abr


def render(abr_path, out_path, size=60, width=900, height=260):
    r = read_abr(open(abr_path, "rb").read())
    preset = r["desc"].get("Brsh")[0]
    tip = r["samples"][preset.get("Brsh").get("sampledData")].astype(np.float32) / 255
    spacing = preset.get("Brsh").get("Spcn").value / 100
    pat = None
    if preset.get("useTexture"):
        pid = preset.get("Txtr").get("Idnt")
        pat = r["patterns"][pid][1].astype(np.float32) / 255
        scale = preset.get("textureScale").value / 100
        depth = preset.get("textureDepth").value / 100
    min_d = preset.get("minimumDiameter").value / 100
    jitter = preset.get("angleDynamics").get("jitter").value / 100
    canvas = np.zeros((height, width), np.float32)
    rng = np.random.default_rng(1)
    x = 40.0
    while x < width - 40:
        t = (x - 40) / (width - 80)
        pressure = np.sin(np.pi * t) ** 0.7
        d = size * (min_d + (1 - min_d) * pressure)
        y = height / 2 + 60 * np.sin(t * 2 * np.pi)
        stamp = Image.fromarray((tip * 255).astype(np.uint8)).resize((max(2, int(d)), max(2, int(d * tip.shape[0] / tip.shape[1]))))
        stamp = stamp.rotate(rng.uniform(-180, 180) * jitter, expand=True)
        s = np.asarray(stamp, np.float32) / 255 * (0.3 + 0.7 * pressure)
        h, w = s.shape
        y0, x0 = int(y - h / 2), int(x - w / 2)
        sub = canvas[y0:y0 + h, x0:x0 + w]
        canvas[y0:y0 + h, x0:x0 + w] = sub + s[:sub.shape[0], :sub.shape[1]] * (1 - sub)
        x += max(1.0, d * spacing)
    if pat is not None:
        pimg = Image.fromarray((pat * 255).astype(np.uint8))
        pimg = pimg.resize((max(1, int(pat.shape[1] * scale)), max(1, int(pat.shape[0] * scale))))
        p = np.asarray(pimg, np.float32) / 255
        tiled = np.tile(p, (height // p.shape[0] + 1, width // p.shape[1] + 1))[:height, :width]
        canvas *= 1 - depth * (1 - tiled)
    Image.fromarray((255 - canvas.clip(0, 1) * 255).astype(np.uint8)).save(out_path)


if __name__ == "__main__":
    render(sys.argv[1], sys.argv[2])
