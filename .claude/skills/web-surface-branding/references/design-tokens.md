---
title: Design tokens — the Quarry palette
description: DESIGN.md's colour palette, type scale, named rules, and the do's/don'ts list, reproduced verbatim
tags: [design-tokens, quarry-palette, hearth-rule, mineral-tint-rule, one-family-rule, flat-stone-rule, dos-and-donts]
---

# Design tokens — the Quarry palette

Source of truth: `DESIGN.md` (repo root, 237 lines). This file mirrors its tokens and rules **verbatim** — their specificity is the value, not a paraphrase. If `DESIGN.md` changes, update this file in the same commit; nothing enforces that they stay in sync.

**Creative North Star: "The Slate Hearth."** Warm ember glow against cold stone. The copper primary is the fire; the slate is the mountain around it. The system explicitly rejects: generic Minecraft server aesthetics (neon green, pixel fonts, "JOIN NOW" energy), corporate SaaS dark mode (Tailwind Slate, blue accents), overstimulating gaming sites (Hypixel/CurseForge ad density), and twee/cutesy indie palettes.

## Colours: the Quarry palette

All contrast ratios verified WCAG AA.

| Token | Hex | Use |
| --- | --- | --- |
| Quarry Copper | `#b05540` | Primary action surfaces, step numbers, download button. ≤10% of any screen. |
| Copper Glow | `#c06550` | Hover state for copper elements. |
| Moss Green | `#5a9a70` | Links, active nav indicators, details summaries. 5.6:1 contrast on slate-bg. |
| Moss Light | `#70b088` | Hover state for moss elements. 7.4:1 contrast on slate-bg. |
| Deep Slate (`--bg`) | `#0c1319` | Page background. Near-black with a blue-green mineral tint, not pure black. |
| Slate Surface (`--surface`) | `#141d27` | Elevated surfaces, table headers, filter inputs. |
| Slate Surface Deep (`--surface-2`) | `#111922` | Details/accordion background. |
| Slate Border (`--border`) | `#1c2835` | Default borders. Also `--code-bg`. |
| Slate Border Strong (`--border-2`) | `#2a3a4c` | Emphasized borders, input outlines. |
| Ink | `#e8ecf1` | High-emphasis text, headings. |
| Text | `#c5cdd8` | Body text. 11.7:1 contrast on slate-bg. |
| Muted | `#7a8999` | Secondary text, hints, descriptions. 5.2:1 contrast on slate-bg. |
| Faint | `#546478` | Decorative accents ONLY (dividers, disabled states). 3.1:1 — fails AA for text; footers/labels/placeholders use Muted instead. |
| Nav Dark | `#080d12` | Nav bar background. Darker than the page to anchor the top edge. |
| Amber Warning (`--warn`) | `#d4950a` | Functional warning colour (updates available, pending states). Not part of the brand palette. |
| Iron Red | `#c96a6a` | Functional danger colour (down monitors, incompatible mods). Not part of the brand palette. |

### Named rules

**The Hearth Rule.** Copper appears on primary actions and step markers only. Its rarity is the warmth. If copper is everywhere, the hearth has gone out.

**The Mineral Tint Rule.** Neutrals carry 0.015-0.02 chroma toward hue 240 (slate-blue). Never pure grey, never warm-tinted. The stone is always present.

## Typography

**Display Font:** system-ui, -apple-system, sans-serif (placeholder — swap for your brand's display font). **Body Font:** system-ui. **Mono Font:** ui-monospace (with "SF Mono", "Cascadia Code", monospace fallback).

CSS custom properties define the type scale: `--text-display`, `--text-heading`, `--text-body`, `--text-sm`, `--text-xs`.

- **Display** (700, `clamp(1.5rem, 1rem + 2vw, 2.25rem)`, line-height 1.2, letter-spacing 0.04em): server name heading only. `text-wrap: balance`.
- **Heading** (700, 1.25rem, line-height 1.4, letter-spacing 0.02em): section headings.
- **Body** (400, 1rem, line-height 1.6): running text, step titles.
- **Secondary** (400/600, 0.875rem): step hints, details summaries.
- **Caption** (400/600, 0.8rem): footer, URL copy inputs, filter labels.
- **Mono** (400, 0.85em): inline code, server commands, URLs, on code-bg.

No external fonts loaded — CSP constraint `font-src 'self' data:` (relaxed to also allow `https://pack.${DOMAIN}` on the status.DOMAIN surface only, so Kuma's `customCSS` can load a self-hosted font cross-origin from pack.DOMAIN).

### Named rule

**The One Family Rule.** If you add a display font, use it on `h1` and `h2` only. Everything else stays system-ui. No third font.

## Elevation

Flat by default. Depth conveyed through tonal layering (bg → surface → surface-deep) and border, not shadow. The single exception: copper elements carry a warm glow on hover, as if catching firelight.

### Named rule

**The Flat Stone Rule.** Surfaces are flat at rest. Shadows appear only on copper elements, and only as warm glow, never as structural elevation. If a surface needs to feel elevated, use a lighter tonal step, not a shadow.

## Spacing & shape

| Token                      | Value                       |
| -------------------------- | --------------------------- |
| `rounded.xs`               | 3px                         |
| `rounded.sm`               | 4px                         |
| `rounded.nav`              | 6px                         |
| `rounded.md`               | 8px (button/details radius) |
| `rounded.lg`               | 14px                        |
| `spacing.xs`–`spacing.xxl` | 0.25rem – 3rem              |

One width (65ch) across all pages — consistency over variety.

## 6. Do's and don'ts (verbatim from `DESIGN.md` § 6)

### Do:

- **Do** use copper exclusively for primary actions (download buttons, step numbers). Its warmth is the brand.
- **Do** use the display font for h1 and h2 headings only. Keep body text in system-ui.
- **Do** use `aria-current="page"` for active nav links, not `class="active"`.
- **Do** constrain body text to `max-width: 65ch` for comfortable reading.
- **Do** include `@media (prefers-reduced-motion: reduce)` for every animation.
- **Do** use `100dvh` with `100vh` fallback for viewport-height elements.
- **Do** use `--ease-out-expo` (`cubic-bezier(0.16, 1, 0.3, 1)`) for entrances and `--ease-out-quart` (`cubic-bezier(0.25, 1, 0.5, 1)`) for state transitions.

### Don't:

- **Don't** use Tailwind Slate colours (#0f172a, #1e293b, #2563eb). That's the old palette and the explicit anti-reference ("corporate SaaS dashboards").
- **Don't** use blue for any accent or link colour. Moss green is the wayfinding colour; blue is the SaaS reflex.
- **Don't** add uppercase tracked text (the AI eyebrow pattern). Normal case with font-weight 600.
- **Don't** use hero-metric stat cards (big number + small label). Use inline summary sentences.
- **Don't** add side-stripe borders, gradient text, or glassmorphism. Absolute bans.
- **Don't** use bounce or elastic easing curves. Ease-out-expo/quart only.
- **Don't** animate page sections on scroll reveal. The breathing hero background is the one motion moment; the rest of the page is still.
- **Don't** use neon green, pixel fonts, or "JOIN NOW" energy. This isn't a public MC server listing.
- **Don't** use pastel palettes, rounded-everything, or soft/cutesy aesthetics. This is an adventure server with Incendium's Nether.

## Where the tokens are physically duplicated

Every surface below carries its own copy of the CSS variables — there is no shared stylesheet and no build step that keeps them in sync:

- `modpack/template/index.html` — `:root { --bg: #0c1319; ... }` block, comment explicitly says "single source of truth: DESIGN.md"
- `scripts/check-updates.sh` (`--html` heredoc) — hard-coded hex values inline (no CSS custom properties, just literal colours in the `<style>` block)
- `config/uptime-kuma/kuma-config.json` `statusPage.customCSS` — hard-coded hex values inline, `!important`-heavy to override Kuma's own Bootstrap-derived styles
- `config/nginx/nav-proxy.conf.template` — the injected `.site-nav` CSS block, hard-coded hex, duplicated once per `server` block (see `SKILL.md` § nav injection)

A palette change (e.g. swapping copper for another hue) means editing all four locations by hand. Grep for the hex value to find every copy: `grep -rn "b05540" --include="*.html" --include="*.template" --include="*.json" .`
