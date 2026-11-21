# handoff/

The working material of the 26.3 rebuild's orchestration, kept in git so a paused session can resume in a fresh container. Delete this folder with `HANDOFF.md` once the rebuild ships.

- `scratchpad/` — every work-package brief (`<package>-brief.md`; `common-rules.md` binds every agent), the parked trap drafts (`pending-traps.md`), and the page tools: `build-review-page.py` (a batch's review page from its `page.json` and `terrain-shots.json`), `check-page.js` (Playwright width check), `terrain-picks/index.html` (the template the builder reads), `p7-page/` (the Gameplay settings page's source).
- `evidence/<package>/` — each package's progress log (the record of the commands and timings that work on the build machine) and its small result files: `page.json`, `terrain-shots.json`, `targets-all.md`, `results.md`, `rehearsal.md`, `kit-gaps.md`, `STATE.md`.
- `patches/` — unmerged work of an agent that was stopped mid-task, as a patch against the commit named in its file header.
- `restore.sh` — copies both back to the absolute paths the briefs and `HANDOFF.md` name. Run it first in a fresh container.

Images are not here: the published review pages hold them (`HANDOFF.md` § State lists the links).
