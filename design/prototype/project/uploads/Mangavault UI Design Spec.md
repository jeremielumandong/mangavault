# Mangavault UI Design Spec

Oct 17, 2026 · @Jeremie

## Overview

Mangavault is a manga, manhwa and webtoon reader whose whole experience is built around one thing: opening a chapter and reading it without waiting. Design the browse screens to get out of the way and the reader to feel instant and immersive.

- **Platforms:** two native apps, iOS (SwiftUI, iOS 17+) and Android (Jetpack Compose, Android 9+). Same product and layout logic on both, but each follows its platform's conventions (see Platform differences).
- **Content:** titles come from several sources (MangaDex, MangaPill, AsuraScans, WeebCentral, more later) through one backend API. Each title has a cover, details and chapters; chapters are lists of page image links loaded from the source sites.
- **Accounts:** sign in to sync favorites and reading progress across devices. Browsing and reading work without signing in.
- **Audience:** frequent readers who read many chapters in one sitting, often vertical-scroll manhwa on a phone, one-handed.

**Design goals, in priority order**

1. **Speed you can feel.** A tap on a chapter shows the first page right away; the next pages are already loaded. Skeletons over spinners, never a blank screen.
2. **Immersive reading.** Edge to edge, no chrome while reading, true-black background, controls one tap away.
3. **Continue in one tap.** The last chapter read is always one tap from launch.
4. **Easy to scan.** Big covers, clear new-chapter badges, source shown quietly.

## Visual language

Dark-first and content-first: covers and pages carry the color, the interface stays neutral and quiet.

**Themes**

- Dark (default), Light, and OLED Black (pure #000 everywhere, recommended for the reader).
- The reader always uses its own background setting (Black, Dark gray, White, Sepia), independent of the app theme.

**Color tokens** (starting values; Claude Design may refine)

| Token | Dark | Light | Use |
| --- | --- | --- | --- |
| background | #0E0E11 | #FAFAFB | App background |
| surface | #17171C | #FFFFFF | Cards, sheets |
| surface-raised | #202027 | #F1F1F4 | Bars, menus |
| text-primary | #F2F2F5 | #121216 | Titles, body |
| text-secondary | #A0A0AB | #5C5C66 | Metadata |
| accent | #FF5A5F | #E8484D | Primary actions, progress, new badges |
| success | #3DD68C | #1FA463 | Downloaded, completed |
| warning | #F5B841 | #C98A0B | Source issues |

One accent color only. Optionally tint the details screen header with a color sampled from the cover.

**Typography**

- iOS: SF Pro, using Dynamic Type styles. Android: Roboto / system font, Material 3 type scale.
- Title Large 22/28 semibold, Title 17/22 semibold, Body 15/20, Caption 12/16, Badge 11/13 bold.
- Manga titles wrap to 2 lines on cards, never truncate mid-word on details.

**Shape and spacing**

- 4 pt grid; screen margins 16; card gap 12.
- Cover aspect ratio 2:3, corner radius 8. Sheets radius 16. Buttons radius 12.

**Motion**

- 200–250 ms standard transitions with platform easing.
- Shared-element transition from a cover to the details header.
- Reader controls fade in and out in 150 ms. No motion that delays reading.
- Honor Reduce Motion: replace slides with fades.

## Navigation

Five bottom tabs: Home, Search, Library, History, Settings. The reader opens full screen over everything and hides the tab bar.

&#91;embedded content: screen map · 5 tabs, details, reader\]

Home and History resume straight into the reader; everything else goes through Manga details. Sign in is a sheet, never a gate.

## Screens

Each screen below lists what it must show and do; layout is open to Claude Design.

### Home

- **Continue reading** row at the top: wide cards with cover, title, "Ch. 112 · page 14 of 38", a progress bar, and one tap to resume in the reader.
- **Updated favorites:** favorites with new chapters, newest first, with a "3 new" badge.
- **Discover rows:** Popular, Latest updates, New titles, and one row per genre the user reads most. Horizontal cover carousels with "See all".
- Pull to refresh. Source filter chip at the top (All sources, or one source).

### Search

- Search field focused on open, with recent searches below it.
- Results from all sources merged, as a cover grid; each result shows a small source label.
- Filters in a sheet: source, genre (include/exclude), status (ongoing, completed, hiatus), type (manga, manhwa, manhua), sort (relevance, popularity, latest update, title).
- Results appear as you type (debounced); skeleton covers while loading.

### Manga details

- Header: large cover, title, alternative titles (collapsed), author/artist, status, type, source, genre chips.
- Primary button: **Read Ch. 1** for new titles, **Continue Ch. 112** for started ones.
- Secondary actions: favorite (heart), share, open on source website.
- Synopsis, 4 lines with "More".
- Chapter list: newest first with a sort toggle; each row shows chapter number, title, release date, and read state (read rows dimmed, the current one marked with progress). Long-press or swipe to mark read/unread, and "mark all previous as read".
- Jump-to-chapter control for titles with hundreds of chapters.
- Same title on other sources (when known) shown as a switch.

### Library (favorites)

- Cover grid (2, 3 or 4 columns, user choice) or list view.
- Badges: unread count, new-chapter dot, last-read progress.
- Sort: last read, last updated, title, date added. Filter: unread only, by source, by status.
- Custom categories (e.g. Reading, Plan to read, Completed) as tabs.
- Sync state shown subtly; nothing blocks while syncing.

### History

- Reading history grouped by day (Today, Yesterday, This week...).
- Each row: cover, title, chapter, time, resume button. Swipe to remove; clear all in a menu.

### Settings

- Account: signed-in user, sync status, sign out.
- Appearance: app theme, grid columns.
- Reader defaults (the same options as the reader settings sheet).
- Sources: enable/disable and order sources.
- Data: image cache size and clear cache, prefetch amount, data saver on cellular.
- About, version, feedback.

### Sign in

- Shown from Settings, or when the user favorites something while signed out (as a sheet, not a blocking screen).
- Sign in with Apple and Sign in with Google buttons; short line on what syncs (favorites, progress, history).
- After first sign-in: merge local favorites and progress into the account, with a confirmation.

### Onboarding (first launch)

- At most 2 screens: welcome, then pick favorite genres (optional, skippable). No sign-in required to start.

## Reader

The reader is the product: full screen, page content edge to edge, and every control hidden until one tap in the center of the screen.

### Reading modes

| Mode | Best for | Behavior |
| --- | --- | --- |
| Vertical scroll (webtoon) | Manhwa, webtoons | Pages stacked with no gap (optional gap setting), smooth fling scrolling, chapters flow into each other |
| Paged, right to left | Japanese manga | Swipe or tap edges to turn; double-page spreads on tablets and landscape |
| Paged, left to right | Western comics, some manhua | Same as above, reversed |
| Paged, vertical | Users who prefer it | Swipe up/down one page at a time |

Default mode is picked per title from its type (manhwa: vertical; manga: right to left), and the user's choice is remembered per title.

### Controls overlay (tap center to toggle)

- **Top bar:** back, manga title and chapter title, chapter list button, more menu (share, open on source, report broken page).
- **Bottom bar:** previous/next chapter buttons, page slider with "14 / 38" label and thumbnail preview while dragging, reader settings button.
- Translucent bars over the page (blur on iOS, scrim on Android); system status and navigation bars hidden while reading, shown with the controls.

### Gestures

- Tap center: toggle controls. Tap left/right edge: previous/next page (paged modes; configurable zones and an "invert" option).
- Pinch and double-tap to zoom (double-tap zooms to the tapped point); pan while zoomed.
- Long-press a page: save image, share, reload page.
- Volume keys turn pages (Android, optional).

### Reader settings sheet

- Mode, background (Black, Dark gray, White, Sepia), fit (width, height, screen, original), page gap, keep screen on, brightness slider with an optional dim overlay, show page number, tap zones.
- Changes apply live behind a half-height sheet so the user sees the effect.

### Chapter transitions

- Vertical mode: at the end of a chapter, a slim divider card ("End of Ch. 112 · Next: Ch. 113") and the next chapter continues below without a tap.
- Paged modes: a transition page between chapters showing finished/next chapter, with the next chapter's first pages already loaded.
- Last available chapter: a clear end card with "You're caught up", favorite button if not favorited, and back to details.

### Loading and speed (what the user sees)

- Opening a chapter: the page area shows immediately; each page has a placeholder sized to its expected height (no layout jumps), with a subtle shimmer.
- Pages ahead are prefetched (default 5, and the next chapter's first pages near the end), so the user rarely sees a placeholder.
- A page that fails shows an inline "Tap to retry" in its own slot; the rest of the chapter keeps loading.
- Progress is saved continuously (current page) and synced in the background; never a save button.
- Thin progress line at the very top or bottom when controls are hidden (optional).

## Components, states and platforms

### Core components

- **Cover card:** 2:3 cover, title (2 lines), optional source label, badges (new count, unread, progress bar along the bottom edge).
- **Continue card:** wide card with cover, title, chapter and page, progress bar, resume button.
- **Chapter row:** number, title, date, read state, progress indicator for the current chapter.
- **Chips:** genre, filter, source. **Badges:** "NEW", count, source.
- **Sheets:** filters, reader settings, sign in, chapter list (opened from the reader).
- **Skeletons** for every list and grid.

### States every screen needs

| State | What to show |
| --- | --- |
| Loading | Skeletons in the final layout, never a lone spinner |
| Empty | Friendly line and one action (e.g. empty Library: "Browse popular titles") |
| Error | What went wrong in plain words and a Retry button |
| Offline | Banner at the top; cached content stays usable |
| Source unavailable | Warning chip on the title, "Try another source" when one exists |

### Accessibility

- Dynamic Type / font scaling on all non-reader screens; layouts must survive the largest sizes.
- Contrast at least 4.5:1 for text; touch targets at least 44 pt (iOS) / 48 dp (Android).
- VoiceOver and TalkBack labels for covers, badges and reader controls; reader announces page changes.
- Honor Reduce Motion and system dark/light setting.

### Platform differences

| Area | iOS | Android |
| --- | --- | --- |
| Navigation | Bottom tab bar, large titles, swipe-back | Material 3 navigation bar, predictive back |
| Materials | Blur materials for bars and sheets | Tonal surfaces and scrims; optional dynamic color off by default |
| Sheets | Native detented sheets | Modal bottom sheets |
| Feedback | Haptics on favorite and chapter change | Ripple; haptics on favorite |
| Tablet | iPad: wider grids, side-by-side details and chapter list | Same with large-screen layout |
| Extras | Widgets: Continue reading (later) | Widgets: Continue reading (later); volume-key page turns |

## What to design

Please produce high-fidelity mockups for phone size (iPhone 15 and a 6.5 inch Android phone), dark theme first, then light.

1. Design tokens and component sheet (colors, type, cover card, continue card, chapter row, chips, badges, buttons, sheets, skeletons).
2. Home, Search (empty, typing, results, filter sheet), Manga details (new and in-progress title), Library, History, Settings, Sign in sheet, Onboarding.
3. Reader: vertical mode with controls hidden and shown, paged mode with controls shown, settings sheet, chapter transition (both modes), "caught up" end card, page loading and retry states.
4. Empty, error and offline states for Home and Library.
5. One iOS and one Android version of Home and the Reader to show platform differences.
6. Optional: an iPad/tablet layout of Library and Details, and a clickable prototype of Home → Details → Reader.

Open questions for the design: app icon and logo direction, and whether the accent stays red or picks up from cover colors.
