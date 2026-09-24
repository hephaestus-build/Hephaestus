/**
 * The keyboard focus ring `ui/button.tsx` wears, for a hand-made control — a row that is a
 * button, a name that is a link — so it focuses like a `Button` does: the border takes the ring
 * colour and a 3 px half-transparent ring sits outside it. `outline-none` is part of it, because
 * the ring replaces the browser outline rather than adding to it. `ui/button.tsx` keeps the same
 * classes as its own literal: it is a registry install that re-vendoring overwrites, so it cannot
 * import from here.
 */
export const FOCUS_RING =
	"outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";

/** The same ring drawn inside the element, for a full-width row whose outside edge is clipped. */
export const FOCUS_RING_INSET = `${FOCUS_RING} focus-visible:ring-inset`;

/**
 * A pointer target of at least 24 px for a control drawn smaller — a pill, a status icon, a crumb
 * — as WCAG 2.2 SC 2.5.8 asks: an invisible pseudo-element widens the target without moving the
 * control. It adds 4 px on every side, enough from 16 px drawn; a control drawn smaller widens the
 * inset on top of this, as `HephFeedbackCard` does for its 14 px tick.
 */
export const HIT_AREA_24 = "relative before:absolute before:-inset-1 before:content-['']";
