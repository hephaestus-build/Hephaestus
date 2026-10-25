/**
 * The keyboard focus ring `ui/button.tsx` wears, for a hand-made control — a row that is a
 * button, a name that is a link — so it focuses like a `Button` does: a solid 1 px line in the ring
 * colour at its edge, and a 3 px half-transparent ring outside it. A `Button` draws the line with its
 * border. Most hand-made controls have no border, and some wrap a card that would cover an inset
 * line, so the line here is the outline. Thus, it also stays in forced-colours mode, which removes
 * every box shadow. The half-transparent ring alone does not reach 3:1 against the ground (WCAG 2.2
 * SC 1.4.11), so the line is the part that makes the focus visible. `ui/button.tsx` keeps its own
 * literal: it is a registry install that re-vendoring overwrites, so it cannot import from here.
 */
export const FOCUS_RING =
	"focus-visible:outline-1 focus-visible:outline-ring focus-visible:ring-3 focus-visible:ring-ring/50";

/** The same ring drawn inside the element, for a full-width row whose outside edge is clipped. */
export const FOCUS_RING_INSET = `${FOCUS_RING} focus-visible:-outline-offset-1 focus-visible:ring-inset`;

/**
 * A pointer target of at least 24 px for a control drawn smaller — a pill, a status icon, a crumb
 * — as WCAG 2.2 SC 2.5.8 asks: an invisible pseudo-element widens the target without moving the
 * control. It adds 4 px on every side, enough from 16 px drawn; a control drawn smaller widens the
 * inset on top of this, as `StatusIcon` does for its 14 px icon.
 */
export const HIT_AREA_24 = "relative before:absolute before:-inset-1 before:content-['']";

/**
 * The ring a table row's own controls wear — its "Open …" link, Track and Trace — so the three
 * focus alike, in the accent that the practice surfaces palette gives a link on focus.
 */
export const ROW_CONTROL_FOCUS =
	"outline-none focus-visible:ring-2 focus-visible:ring-mentor focus-visible:ring-offset-2 focus-visible:ring-offset-background";
