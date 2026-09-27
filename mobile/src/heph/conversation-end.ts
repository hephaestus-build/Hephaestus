/**
 * Whether the end of a conversation is out of sight. Neither list library can say: LegendList's
 * `isAtEnd` adds the end inset to the content size and subtracts it again, and keyboard-controller's
 * `onEndVisible` ignores the inset, so both measure against the whole frame — and the composer, with
 * the keyboard under it, covers the bottom of that frame. This measures against what covers it.
 */

/** Where the list is scrolled and how big it is, as the native scroll view reports it. */
export interface ScrollGeometry {
	/** The scroll offset: how far the top of the frame is into the content. */
	offset: number;
	/** The height of the list's frame, which runs to the bottom of the screen. */
	viewport: number;
	/** The height of the content itself; insets are not in it. */
	content: number;
}

/**
 * How much of the bottom of the frame the composer covers. It floats over the list: its measured
 * height, the safe area its dock pads below it while the keyboard is closed, and, once the keyboard
 * is open, however far the keyboard lifts it past that safe area.
 */
export function coveredBottom({
	composer,
	dockPadding,
	keyboard,
	safeBottom,
}: {
	composer: number;
	dockPadding: number;
	keyboard: number;
	safeBottom: number;
}): number {
	return composer + dockPadding + Math.max(0, keyboard - safeBottom);
}

/**
 * Less than this below the visible edge still counts as seen: the list's own bottom padding, which
 * holds no text.
 */
export const END_TOLERANCE = 24;

/** Whether the last line sits below the part of the frame nothing covers. */
export function endHidden(geometry: ScrollGeometry, covered: number): boolean {
	const visibleBottom = geometry.offset + geometry.viewport - covered;
	return geometry.content - visibleBottom > END_TOLERANCE;
}

/**
 * Whether the conversation keeps following a reply as it grows. It follows whenever its end is in
 * sight, however it got there, and stops only when the reader's own scrolling takes the end out of
 * sight. Scrolls the app makes itself — keeping up with a growing reply, the keyboard lifting the
 * list — never stop it, so a reply that grows faster than one scroll cannot shake it off.
 */
export function follows({
	following,
	readerScrolling,
	hidden,
}: {
	following: boolean;
	/** The reader is dragging the list, or it is still moving from their fling. */
	readerScrolling: boolean;
	/** The end is out of sight, as `endHidden` judges it. */
	hidden: boolean;
}): boolean {
	if (!hidden) {
		return true;
	}
	return readerScrolling ? false : following;
}
