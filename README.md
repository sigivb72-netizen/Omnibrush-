# Omnibrush

Converts Clip Studio Paint brushes (`.sut`) into formats other painting apps can import.

```
pip install .
omnibrush convert "SU-Cream Pencil.sut" -o converted/
omnibrush convert my_brush_pack/ -n "My Pack" -o converted/   # a folder or .zip of .sut files
omnibrush inspect "converted/SU-Cream Pencil.abr"
```

## What you get

| File | Use it in |
|---|---|
| `<pack>.abr` | Photoshop, Procreate (import .abr), Krita, GIMP, Affinity Photo, Adobe Fresco, MediBang, CSP itself |
| `<brush>/<brush>.gbr` | GIMP, Krita |
| `<brush>/tip.png` | Any app with a custom stamp/shape (black-on-transparent) |
| `<brush>/texture.png` | Procreate grain, Krita/Photoshop pattern, any "paper texture" slot |
| `<brush>/settings.json` | Every setting read from CSP, for rebuilding by hand |

## What carries over into the .abr

- Brush tip image at full resolution, plus extra tips as extra presets
- Size, spacing, roundness, angle
- Size, opacity and flow pen pressure, with minimum values
- Random rotation, or rotation that follows stroke direction
- Spray, mapped to scatter
- Texture: image, scale, density, invert, brightness, contrast and blend mode
- Opacity, flow and stabilization, which go into the tool options

These have no ABR equivalent, and the CLI reports them for each brush:
- Start/end taper
- Velocity dynamics
- Dual brush
- Color mixing and watercolor edge

## How .sut files are read

A `.sut` file is an SQLite database. The `Variant` table holds the settings and `MaterialFile` holds the tip and texture images, each one as a tar archive. Inside each archive is a `material_0.layer` file in Celsys' **C2F** container. A C2F file is itself an SQLite database. Its first pages are obfuscated, but the pages that hold the pixels are stored as plain bytes. `omnibrush/c2f.py` reads those pages directly, rebuilds overflow chains and decodes the zlib-compressed 256×256 pixel blocks, which use the same layout as `.clip` files. If that ever fails, the reader falls back to the material's 300 px thumbnail and says so.

## Status

- Tested with *SU-Cream Pencil*. Other brushes may use encodings that haven't been tested yet, so please report any brush that fails.
- Some mappings are best guesses:
  - which CSP rotation-source value means random
  - CSP texture composite mode → Photoshop blend mode
  - CSP auto-spacing modes
- ibisPaint is not supported yet.
