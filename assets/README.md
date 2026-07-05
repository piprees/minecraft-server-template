# Placeholder brand assets

These are obviously-placeholder assets shipped with the template. Replace them
with your own brand before going live.

## Files

| File | Dimensions | Purpose |
| --- | --- | --- |
| `icon.svg` | 128 x 128 | Square icon (grey background, pickaxe emoji) |
| `logo.svg` | 480 x 128 | Horizontal lockup: icon + "Adventure Server" wordmark |
| `cover.svg` | 1280 x 640 | Social/OpenGraph cover image |
| `favicon.svg` | 32 x 32 | Browser tab icon (SVG favicon, supported by all modern browsers) |

## Replacing with your own brand

1. Replace the four SVGs (or PNGs/JPGs) with your own artwork at the same
   dimensions.
2. Update references in these files:
   - `modpack/template/index.html` (favicon and OG image `<meta>` tags)
   - `config/uptime-kuma/custom.css` (if you add a logo/background)
   - `config/nginx/nav-proxy.conf` (OG image and favicon references, not owned by this asset set; coordinate with the nav-proxy config)
   - `scripts/build-modpack.sh` (copies `assets/` into `modpack/dist/` at build time)
3. If you need raster favicons (`.ico`, `.png`), generate them from your
   SVGs. One-liner examples:

   ```bash
   # With rsvg-convert (librsvg):
   rsvg-convert -w 32 -h 32 assets/favicon.svg -o assets/favicon-32.png
   rsvg-convert -w 180 -h 180 assets/icon.svg -o assets/apple-touch-icon.png

   # With ImageMagick:
   magick assets/favicon.svg -resize 32x32 assets/favicon-32.png
   magick assets/icon.svg -resize 16x16 -resize 32x32 -resize 48x48 assets/favicon.ico

   # With Python + Pillow (pip install cairosvg pillow):
   python3 -c "import cairosvg; cairosvg.svg2png(url='assets/icon.svg', write_to='assets/favicon-32.png', output_width=32, output_height=32)"
   ```

4. The `build-modpack.sh` script copies asset files from `assets/` into
   `modpack/dist/` so they are served by the pack-web container. After
   replacing assets, push to `main` and CI handles the rest.
