# the_lantern_pools — noise:overworld; place kind: place (one oasis country at dusk)
> "Serene oasis pools glowing at dusk. A hermit's paradise — desert shrines mark where travellers stopped to rest, but nobody built a town. The pools are older than the shrines."

- **First:** a turquoise oasis pool ringed with grass and palms, a guide post (wants near_spawn).
- **Mostly:** desert oases, warm rivers, lush valleys, glistering meadows. **Rarely:** orchid swamp, mangrove, jungle; desert shrines.
- **Layers:** surface pools; lush and tuff caves.
- **Atmosphere:** "glowing at dusk": no 1.21.1 environment, so the glow and dusk are not set.
- **Place kind:** one place: oasis country.
- **Baseline wrong (W9g b1 full):** desert_oasis is listed but not placed (`biomeExpression.listedNotFound`; weight 2.3); mangrove_swamp 21.3 and sparse_jungle 20.6 make it a jungle swamp; warm_ocean 27.6% and water 42.7% of top blocks (a sea, not pools); jungle unplaced; regions scattered (largest 22.1, 22 major, specks 5.1).
- **v2 responsible:** weights sparse_jungle 24.7, plains 19.2, mangrove 14.8 vs desert_oasis 2.3; no fixed time or glow.
- **Conflict:** "glowing at dusk" has no 1.21.1 `environment`; a fixedTime near 12500 would be an addition the owner should approve.
