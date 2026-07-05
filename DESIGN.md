---
name: Adventure Server
description: Example design system for a modded Minecraft adventure server - adapt to your own brand
colors:
  slate-bg: '#0c1319'
  slate-surface: '#141d27'
  slate-surface-deep: '#111922'
  slate-border: '#1c2835'
  slate-border-strong: '#2a3a4c'
  copper-primary: '#b05540'
  copper-hover: '#c06550'
  moss-accent: '#5a9a70'
  moss-hover: '#70b088'
  ink: '#e8ecf1'
  text: '#c5cdd8'
  muted: '#7a8999'
  faint: '#546478'
  code-bg: '#1c2835'
  white: '#ffffff'
  warn-amber: '#d4950a'
  nav-bg: '#080d12'
typography:
  display:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: '2.25rem'
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: '0.04em'
  heading:
    fontFamily: "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif"
    fontSize: '1.25rem'
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: '0.02em'
  body:
    fontFamily: 'system-ui, -apple-system, sans-serif'
    fontSize: '1rem'
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: 'system-ui, -apple-system, sans-serif'
    fontSize: '0.85rem'
    fontWeight: 600
    lineHeight: 1.4
  mono:
    fontFamily: "ui-monospace, 'SF Mono', 'Cascadia Code', monospace"
    fontSize: '0.85em'
    fontWeight: 400
rounded:
  xs: '3px'
  sm: '4px'
  nav: '6px'
  md: '8px'
  lg: '14px'
  full: '50%'
spacing:
  xs: '0.25rem'
  sm: '0.5rem'
  md: '1rem'
  lg: '1.5rem'
  xl: '2rem'
  xxl: '3rem'
components:
  button-primary:
    backgroundColor: '{colors.copper-primary}'
    textColor: '#ffffff'
    rounded: '{rounded.md}'
    padding: '0.65rem 1.5rem'
  button-primary-hover:
    backgroundColor: '{colors.copper-hover}'
    textColor: '#ffffff'
  button-secondary:
    backgroundColor: 'transparent'
    textColor: '{colors.text}'
    rounded: '{rounded.md}'
    padding: '0.65rem 1.5rem'
  nav-link:
    textColor: '{colors.muted}'
  nav-link-active:
    textColor: '{colors.moss-accent}'
  details-summary:
    textColor: '{colors.moss-accent}'
    padding: '0.55rem 0.85rem'
    height: '44px'
---

# Design system: Adventure Server

> **This is an example design system.** It ships as a starting point you can adapt to your own server's brand and identity. Replace the colours, name, and imagery with your own; the structure and principles are the reusable part.

## 1. Overview

**Creative North Star: "The Slate Hearth"**

Warm ember glow against cold stone. The copper primary is the fire; the slate is the mountain around it. Every surface draws from quarry materials: dark mineral stone for backgrounds, weathered copper for action, moss green for wayfinding. The landscape breathes behind the content through a slow-zooming hero image, but the UI itself is still and precise.

This system serves a product register: design disappears into the task. Friends land on this page to download a modpack and join a Minecraft server. The emotional register is "your mate's considered adventure server," not "software product" and not "gaming storefront." The atmosphere comes from materials and light, not from decoration or effects.

The system explicitly rejects: generic Minecraft server aesthetics (neon green, pixel fonts, "JOIN NOW" energy), corporate SaaS dark mode (Tailwind Slate, blue accents, could-be-anything neutrality), overstimulating gaming sites (Hypixel/CurseForge ad-laden density), and twee/cutesy indie palettes (pastels, rounded everything).

**Characteristics:**

- Dark theme only. The scene: a friend at their gaming desk at night, screen glowing, clicking a Discord link.
- Restrained colour strategy: tinted neutrals + copper accent on ≤10% of the surface.
- System sans throughout. Replace with a display serif (e.g. EB Garamond) for your own brand if desired.
- Motion conveys atmosphere (hero breathes) and state (button feedback), never decoration.
- One width (65ch) across all pages. Consistency over variety; `ch`-based measure keeps body text in the comfortable reading band regardless of font.

## 2. Colours: the Quarry palette

Drawn from slate quarry materials: dark mineral stone, weathered copper rail, moss in the cracks. All contrast ratios verified WCAG AA.

### Primary

- **Quarry Copper** (#b05540): Primary action surfaces, step numbers, download button. The hearth's ember. Used on ≤10% of any screen. Its warmth against the cold slate is the point.
- **Copper Glow** (#c06550): Hover state for copper elements. Slightly lifted, slightly warmer.

### Secondary

- **Moss Green** (#5a9a70): Links, active nav indicators, details summaries. The organic wayfinding colour: alive against the mineral palette. 5.6:1 contrast on slate-bg.
- **Moss Light** (#70b088): Hover state for moss elements. 7.4:1 contrast on slate-bg.

### Neutral

- **Deep Slate** (#0c1319): Page background. Near-black with a blue-green mineral tint, not pure black.
- **Slate Surface** (#141d27): Elevated surfaces, table headers, filter inputs.
- **Slate Surface Deep** (#111922): Details/accordion background. Between bg and surface.
- **Slate Border** (#1c2835): Default borders, code backgrounds. Also serves as `--code-bg`.
- **Slate Border Strong** (#2a3a4c): Emphasized borders, input outlines.
- **Ink** (#e8ecf1): High-emphasis text, headings. Near-white with a cool mineral tint.
- **Text** (#c5cdd8): Body text. 11.7:1 contrast on slate-bg.
- **Muted** (#7a8999): Secondary text, hints, descriptions. 5.2:1 contrast on slate-bg.
- **Faint** (#546478): Decorative accents only (dividers, disabled states). 3.1:1 contrast - fails AA for text, so footers, labels, and placeholders use Muted instead.
- **Nav Dark** (#080d12): Nav bar background. Darker than the page to anchor the top edge.
- **Amber Warning** (#d4950a): Functional warning colour (updates available, pending states). Not part of the brand palette.
- **Iron Red** (#c96a6a): Functional danger colour (down monitors, incompatible mods). Weathered iron oxide, not alarm red. Not part of the brand palette.

### Named rules

**The Hearth Rule.** Copper appears on primary actions and step markers only. Its rarity is the warmth. If copper is everywhere, the hearth has gone out.

**The Mineral Tint Rule.** Neutrals carry 0.015-0.02 chroma toward hue 240 (slate-blue). Never pure grey, never warm-tinted. The stone is always present.

## 3. Typography

**Display Font:** system-ui, -apple-system, sans-serif (placeholder - swap for your brand's display font) **Body Font:** system-ui (with -apple-system, sans-serif fallback) **Mono Font:** ui-monospace (with "SF Mono", "Cascadia Code", monospace fallback)

**Character:** The placeholder uses system fonts throughout for zero-dependency simplicity. To add a display font (e.g. EB Garamond for a cartographic feel), self-host the woff2 and update the display/heading entries above. No external fonts loaded (CSP constraint: `font-src 'self' data:`).

### Hierarchy

CSS custom properties define the type scale: `--text-display`, `--text-heading`, `--text-body`, `--text-sm`, `--text-xs`. Use these tokens rather than raw values.

- **Display** (700, `--text-display` = `clamp(1.5rem, 1rem + 2vw, 2.25rem)`, line-height 1.2, letter-spacing 0.04em): Server name heading only. System sans (or your brand's display font). `text-wrap: balance`. Fluid sizing scales gracefully from mobile to desktop without breakpoint overrides.
- **Heading** (700, `--text-heading` = 1.25rem, line-height 1.4, letter-spacing 0.02em): Section headings ("Need help?"). System sans (or your brand's display font). `text-wrap: balance`.
- **Body** (400, `--text-body` = 1rem, line-height 1.6): Running text, step titles. System sans.
- **Secondary** (400/600, `--text-sm` = 0.875rem, line-height 1.4–1.65): Step hints, details summaries, details body, prereq text. The workhorse small size.
- **Caption** (400/600, `--text-xs` = 0.8rem): Footer, URL copy inputs, filter labels.
- **Mono** (400, 0.85em): Inline code, server commands, URLs. On code-bg.

### Named rules

**The One Family Rule.** If you add a display font, use it on `h1` and `h2` only. Everything else is system-ui. No third font.

## 4. Elevation

Flat by default. Depth conveyed through tonal layering (bg → surface → surface-deep) and border, not shadow. The single exception: copper elements carry a warm glow on hover, as if catching firelight.

### Shadow vocabulary

- **Copper Glow** (`0 0 12px rgba(176, 85, 64, 0.3)`): Resting state on step number circles. The ember.
- **Copper Hover** (`0 4px 20px rgba(176, 85, 64, 0.25)`): Download button hover. The hearth flares.
- **Copper Pulse** (`0 0 20px rgba(176, 85, 64, 0.2), 0 0 40px rgba(176, 85, 64, 0.08)`): Download button glow-pulse animation peak. Draws the eye without demanding it.

### Named rules

**The Flat Stone Rule.** Surfaces are flat at rest. Shadows appear only on copper elements, and only as warm glow, never as structural elevation. If a surface needs to feel elevated, use a lighter tonal step, not a shadow.

## 5. Components

### Buttons

- **Shape:** Gently curved edges (8px / `--rounded-md`)
- **Primary (Copper):** #b05540 bg, white text, 0.65rem 1.5rem padding. Warm glow on rest (`box-shadow: 0 0 12px` copper at 0.3 opacity). Glow-pulse animation after 1.2s delay (3s cycle).
- **Hover:** Lifts 2px (`translateY(-2px)`), glow expands (`0 4px 20px` copper at 0.25). 200ms ease-out-quart.
- **Active:** Returns to rest position instantly. Glow collapses.
- **Secondary (Ghost):** Transparent bg, 1px slate-border-strong, text-coloured label. Hover: surface bg, white text, 1px lift, subtle shadow.

### Details / disclosure

- **Style:** surface-deep bg, 1px slate-border, 6px radius.
- **Summary:** Moss green text, 44px min-height (touch target), flex-centred. Unicode triangle marker (▸/▾) rotates 90° on open (200ms ease-out-expo).
- **Summary hover:** Moss-hover colour transition.
- **Body:** 0.75rem 0.85rem padding, muted text, 65ch max-width for readability. 1.65 line-height.

### Navigation

- **Style:** Fixed top bar, nav-dark bg (#080d12), 1px slate-border bottom, 44px height.
- **Brand:** Logo image (28×28, 6px radius) + Server name text (ink colour, 700 weight, 0.03em tracking). `margin-right: auto` pushes links right.
- **Links:** Muted colour default, text colour hover, 150ms transition. Active: `aria-current="page"` with moss-accent colour. No underlines.
- **Responsive:** `flex-wrap` for natural stacking on narrow screens.

### Prereq box

- **Style:** Surface bg, 1px slate-border, 8px radius, 65ch max-width.
- **Text:** Body text colour, bold ink for emphasis ("Invite only."). `role="note"` for screen readers.
- **Code elements:** Code-bg, ink colour, 4px radius.

### Hero background

- **Placeholder:** solid `--bg` background. To add a hero image, use a 1600×900 cover-fit image with a four-stop gradient overlay (55% → 75% → 92% → 100% opacity from bg colour).
- **Animation (optional):** 30s breathing zoom cycle (scale 1 → 1.04). Scroll-driven parallax via `animation-timeline: scroll()` (progressive enhancement; static fallback).

## 6. Do's and don'ts

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
