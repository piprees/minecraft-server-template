# Viewer Upgrade Plan

## Current State

- `render_viewer()` in `score-dimensions.py` generates a single-file HTML
- `viewer-server.py` serves it on localhost:8765 with POST /pick for winner selection
- Shows 10 candidates per dimension, no filtering, no re-roll triggers

## Requested Features

### 1. Dimension Config Editing
- "Edit config" button per dimension → opens the dimension JSON in VS Code
- If the consumer overlay doesn't have the file, copy from platform first
- Button: `code <path>` via a new server endpoint

### 2. Re-roll Trigger
- "Re-roll" button per dimension → dispatches `fast_roller.py --dims <name>` + `biome_renderer.py batch --dims <name>` in background
- Shows spinner/progress indicator while running
- Timer/ETA based on dimension count × ~5s average
- Server endpoint: POST /reroll {dim: name, pool: 5000, count: 100}

### 3. Unmined-CLI Preview
- "Detailed preview" button per candidate → runs unmined-cli on the candidate's seed
- Requires Docker (MC server to generate chunks) — show a warning if Docker isn't available
- This is the existing `seed_worker.py render_candidate()` flow
- Server endpoint: POST /preview {dim: name, seed: seed}

### 4. Low-Score Flagging
- Dimensions with best score < 50 get a warning badge
- Dimensions with 100% rejection rate get a "needs config review" badge
- Colour-code the dimension header: red (<30), amber (30-50), green (>50)

### 5. Filtering
- Filter bar at the top: by family (overworld/nether/end/paradise_lost), by type, by mood
- Text search on dimension name
- Toggle: show/hide flagged-only

### 6. Expandable Candidate List
- Show top 3 by default (not 10)
- "Show more" expander reveals up to 20
- Total candidate count shown in the header

## Implementation Approach

### Server-Side (viewer-server.py)

Add new endpoints:
- `POST /reroll` — spawns fast_roller + biome_renderer in background, returns job ID
- `GET /status/<job_id>` — returns progress (running/done/failed)
- `POST /edit-config` — opens VS Code, creates overlay file if needed
- `POST /preview` — spawns unmined-cli render (requires Docker)

### Client-Side (render_viewer in score-dimensions.py)

The HTML is regenerated on every finalise. All interactive features use fetch() to the server endpoints. No build step, no dependencies — vanilla HTML/CSS/JS.

## Phases

### Phase A: Viewer UI improvements (no server changes)
- Low-score flagging
- Filtering (family, type, mood, text search)
- Expandable candidate lists (3 default, expand to 20)
- Better layout and visual hierarchy

### Phase B: Server endpoints + re-roll
- POST /reroll endpoint
- Background job tracking
- Re-roll button with spinner
- Auto-refresh on completion

### Phase C: Config editing + unmined preview
- POST /edit-config endpoint
- POST /preview endpoint (Docker-dependent)
- VS Code integration
