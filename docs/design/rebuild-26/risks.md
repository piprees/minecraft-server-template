# Risks

| Risk | Watch for | If it happens |
| --- | --- | --- |
| Zone edges look artificial or seam at 26.3's single precision | F0, week 1 | Our own mask type for every shape, with a wider blend |
| Our terrain doesn't feel as good as Terralith's | W1's gallery, week 7 | More time on W1 before W7; this is the part worth waiting for |
| Fantasy's lifecycle doesn't fit lazy activation | F0, week 1 | Keep our own level manager, using the same registry technique Fantasy does |
| Geyser's 26.3 release lags | P5 | Launch for Java; Bedrock joins when Geyser does |
| 26.4 changes worldgen again before launch | The canary | A version-table entry; build for 26.4 if it's out by launch |
| The sampler disagrees with generated worlds | W6b's 50-point check | Fix before any seed is picked; rolls are blocked until it matches |
| A kept mod turns vanilla clients away | P3's join gate | Cut or hold it |
| A Polymer patch lags its base mod | dep-review holds | Hold both at the last working pair |
| The owner's review time is the bottleneck | W7 and W8 queues | The cut order in [work-plan.md](work-plan.md#cut-order) |

## Owner actions

In order; the private-repository switch is [decisions.md](decisions.md) RB-06.

1. Merge PR #29 and the Dependabot PRs.
2. Cut the v5 release carrying `STACK_READ_TOKEN` support, so the `v5` tag has it.
3. Add the `STACK_READ_TOKEN` secret and the new `deploy.yml` lines to elfydd.
4. Switch the repository's visibility to private.
5. Set Actions access to "user".

## Open with the owner

- **Vanilla-client previews.** [`immersive-portals.md`](../immersive-portals.md) holds that the server-side block slab is the fallback for vanilla clients and must keep working unchanged. This plan (RB-03) cuts it and makes previews companion-only. One of the two changes before M6 starts.

