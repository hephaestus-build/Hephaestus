---
"hephaestus": patch
---

Improve light- and dark-mode consistency in mentor controls, attachments, development sign-in and feature flags. Keep message actions visible when navigating by keyboard or using a device without hover, restore larger action targets for coarse pointers, and give unranked league icons a defined neutral color.

Use theme-aware colors for environment indicators and merged-work previews. Let reviewed-work popovers grow with wrapped repository names instead of clipping them to an assumed row height, and give their copy action an accessible name.

Report review-link copying as successful only after it completes, show pending and failure feedback, and retain readable work with unusable links without making it clickable. Copy repository labels as text rather than interpreting them as HTML. Let long empty-state descriptions grow without losing their actions, and announce the selected mentor vote to assistive technology.

Keep action spacing consistent across forms, tables and mentor controls using shared button sizes. Restore the outlined sidebar action’s theme-colored border and preserve its visible keyboard focus ring.

Keep dropdown menus within the available screen width, including the feedback menu on narrow screens.

Set small labels, counters and badges on one shared type size instead of nine slightly different ones, so the smallest text — avatar initials and count badges among it — is legible everywhere it appears. Round small controls on one shared corner scale. Give the message editor the same surface as the composer below it in dark mode.

Draw the dashed edge on placeholder cards and on repositories hidden from contributions — it was declared but never rendered. Bring review-activity and issue cards onto the same card look as the rest of the app, and give every search field with a leading icon the same input group, so the icon, padding and focus ring match everywhere.
