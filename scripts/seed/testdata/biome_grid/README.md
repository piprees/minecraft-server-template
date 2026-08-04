# biome_grid fixtures

Ground-truth biome grids dumped from a running server via
`/customdim sample-biome-grid <dim> 768 64`.

Each CSV carries a `# stackVersion=...` header comment. The parity test
(`test_biome_parity.py`) diffs these point-for-point against the Python
`build_from_spec` sampler at zero tolerance.

## Regeneration

Start a local server with the CURRENT mod jar installed, then:

```bash
./scripts/seed/refresh-biome-fixtures.sh
```

The script loads each dimension, runs the grid command, copies the dump
out, and validates stamps. Run the parity test afterwards:

```bash
python3 -m pytest scripts/seed/test_biome_parity.py -q
```

## Fixture format

```
# stackVersion=X.Y.Z kind=biome-grid generatedAt=...
x,z,biome_id
x,z,biome_id
...
```

Coordinates are block coordinates. The server samples at quart y=16
(block y=64). Step=64, radius=768: each fixture contains 625 points
(25x25 grid from -768 to 768 inclusive).
