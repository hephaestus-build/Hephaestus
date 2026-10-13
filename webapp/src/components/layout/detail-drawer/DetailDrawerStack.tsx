import { type ReactNode, useEffect, useLayoutEffect, useRef, useState } from "react";

import { Drawer, DrawerContent } from "@/components/ui/drawer";

import { type DetailStackEntry, detailStackKey } from "./detail-stack";
import { useArrived } from "./use-arrived";

export interface DetailDrawerLevel {
	depth: number;
	/** Whether a level is covering another, which is what decides "back" against "close". */
	nested: boolean;
}

export interface DetailDrawerStackProps<TKind extends string = string> {
	/** The open levels, outermost first. */
	stack: DetailStackEntry<TKind>[];
	/**
	 * Kinds whose close goes straight to the URL, skipping the exit animation the others get.
	 *
	 * For a level holding a draft, where `useUnsavedChanges` blocks the navigation to ask about it:
	 * animating out first unmounts the form while the prompt is still on screen, and a refused
	 * navigation then leaves the level closed with the URL still holding it open.
	 */
	guardedKinds?: readonly TKind[];
	/** Called with the depth to close down to — `close(0)` dismisses the whole stack. */
	onClose: (depth: number) => void;
	children: (entry: DetailStackEntry<TKind>, level: DetailDrawerLevel) => ReactNode;
	/**
	 * What a level shows under its body, after whatever `children` draws for it: one place for a
	 * footer every level of a surface shares, so no level has to carry it.
	 */
	footer?: (entry: DetailStackEntry<TKind>) => ReactNode;
}

/**
 * Each level renders the next *inside* its own content, so the child's portal nests in the parent's.
 * Base UI keeps nested portals out of the set it hides when a popup opens, which is what lets a
 * stack reach the accessibility tree at every depth.
 *
 * A press on a visible layer brings that layer to the front: the page's backdrop dismisses every
 * level, the strip a covered level leaves showing dismisses everything over it, and the front
 * panel is untouched. Escape, the header control and a swipe still close one level. The layer is
 * read off the press's position, because the front level's viewport covers the whole screen and
 * is what every outside press lands on.
 *
 * Every level of every stack is the same panel, the `detail` preset: one width, so a level over
 * another covers it to the same depth and leaves the same column of it showing, whichever surface
 * and kind it is. A width a level picked for itself would move that edge, and the Practice profile,
 * Practices across the workspace and Practice reviews would each step back differently.
 *
 * A dismissal shuts the drawer first and navigates when the exit animation ends, so the URL lags it:
 * dropping the level first would render it with an entry the caller no longer has data for. Clearing
 * the close before the stack catches up re-opens the level that just left.
 */
export function DetailDrawerStack<TKind extends string>({
	stack,
	guardedKinds,
	onClose,
	footer,
	children,
}: DetailDrawerStackProps<TKind>) {
	const [closing, setClosing] = useState<{ depth: number; key: string } | null>(null);

	// Cleared when the level that was closing leaves its depth, not on the completion frame — see the
	// JSDoc above. Held by identity, not depth alone: a level the caller swaps in at that depth mid-close
	// would otherwise mount shut and never open, and its unmounted predecessor never finishes the close.
	if (closing !== null && keyAt(stack, closing.depth) !== closing.key) {
		setClosing(null);
	}
	const closingDepth = closing?.depth ?? null;
	const setClosingDepth = (depth: number) => {
		const key = keyAt(stack, depth);
		setClosing(key === undefined ? null : { depth, key });
	};

	useFocusReturn(stack.length > 0);

	// Mounting is the arrival `useArrived` needs; a component left mounted on `null` has already
	// spent its first render.
	if (stack.length === 0) {
		return null;
	}
	return (
		<DetailDrawerLevelView
			// The entry owns the complete level lifecycle, including initial focus and nested arrival.
			key={keyAt(stack, 0)}
			depth={0}
			parentEntered
			stack={stack}
			guardedKinds={guardedKinds}
			closingDepth={closingDepth}
			setClosingDepth={setClosingDepth}
			onClose={onClose}
			footer={footer}
		>
			{children}
		</DetailDrawerLevelView>
	);
}

interface DetailDrawerLevelViewProps<TKind extends string> extends DetailDrawerStackProps<TKind> {
	depth: number;
	parentEntered: boolean;
	closingDepth: number | null;
	setClosingDepth: (depth: number) => void;
}

/** A component, not a loop, because it owns `useArrived` and each level mounts at a different time. */
function DetailDrawerLevelView<TKind extends string>({
	depth,
	parentEntered,
	stack,
	guardedKinds,
	closingDepth,
	setClosingDepth,
	onClose,
	footer,
	children,
}: DetailDrawerLevelViewProps<TKind>) {
	const entry = stack[depth];
	const arrived = useArrived();
	// URL state can mount every level together. The parent takes native initial focus before its
	// child opens, so the child has a return target even when no control opened it.
	const [entered, setEntered] = useState(false);
	if (!entry) {
		return null;
	}

	const child = stack[depth + 1];
	const guarded = guardedKinds?.includes(entry.kind) ?? false;
	return (
		<Drawer
			// Base UI shuts a parent's children anyway; this keeps the React tree in step.
			open={arrived && parentEntered && (closingDepth === null || depth < closingDepth)}
			swipeDirection="right"
			onOpenChange={(next, details) => {
				if (next) {
					return;
				}
				// Every way out is the same way out: Escape, a press outside, a swipe and the panel's
				// own controls all just close it. What protects a draft is the prompt
				// `useUnsavedChanges` raises on the navigation, not a gesture this refuses.
				const target = details.reason === "outside-press" ? pressedDepth(details.event) : depth;
				if (guarded) {
					onClose(target);
					return;
				}
				setClosingDepth(target);
			}}
			onOpenChangeComplete={(next) => {
				if (next) {
					setEntered(true);
					return;
				}
				if (closingDepth === depth) {
					onClose(depth);
				}
			}}
		>
			<DrawerContent size="detail" dimWhenNested={false} data-detail-depth={depth}>
				{children(entry, { depth, nested: depth > 0 })}
				{footer?.(entry)}
				{child && (
					<DetailDrawerLevelView
						key={detailStackKey(child)}
						depth={depth + 1}
						parentEntered={entered}
						stack={stack}
						guardedKinds={guardedKinds}
						closingDepth={closingDepth}
						setClosingDepth={setClosingDepth}
						onClose={onClose}
						footer={footer}
					>
						{children}
					</DetailDrawerLevelView>
				)}
			</DrawerContent>
		</Drawer>
	);
}

function keyAt(stack: DetailStackEntry[], depth: number): string | undefined {
	const entry = stack[depth];
	return entry === undefined ? undefined : detailStackKey(entry);
}

/**
 * The depth to close down to for a press outside the front panel: one above the deepest covered
 * level under the pointer, or the whole stack when nothing but the page is there.
 */
function pressedDepth(event: MouseEvent | PointerEvent | TouchEvent): number {
	const point = "clientX" in event ? event : (event.changedTouches[0] ?? event.touches[0]);
	if (!point) {
		return 0;
	}
	const depths = document
		.elementsFromPoint(point.clientX, point.clientY)
		.flatMap((element) =>
			element instanceof HTMLElement && element.dataset.detailDepth !== undefined
				? [Number(element.dataset.detailDepth)]
				: [],
		);
	return depths.length > 0 ? Math.max(...depths) + 1 : 0;
}

/**
 * Gives focus back to what opened the stack once the last level has gone: the row, link or button
 * the reader left from, so a keyboard reader continues where they were. URL-controlled navigation
 * can blur that control before the popup mounts; this covers that case only when the framework
 * has not already returned focus to a connected target.
 *
 * The opener is the last element focused before the first level opened. Focus while a level is
 * open is never the opener, even outside every level: a Select, menu or popover inside a level is
 * portalled beside its dialog, not into it. An opener the page has since removed, or a reader who
 * already moved focus elsewhere, is left alone.
 */
function useFocusReturn(open: boolean) {
	const opener = useRef<HTMLElement | null>(null);
	const isOpen = useRef(open);
	useLayoutEffect(() => {
		isOpen.current = open;
	}, [open]);
	useEffect(() => {
		const remember = ({ target }: FocusEvent) => {
			// The dialog check covers focus a level takes before the effect above has run.
			if (
				!isOpen.current &&
				target instanceof HTMLElement &&
				target.closest('[role="dialog"]') === null
			) {
				opener.current = target;
			}
		};
		document.addEventListener("focusin", remember);
		return () => document.removeEventListener("focusin", remember);
	}, []);
	const wasOpen = useRef(open);
	useEffect(() => {
		const closed = wasOpen.current && !open;
		wasOpen.current = open;
		if (!closed) {
			return;
		}
		const target = opener.current;
		const active = document.activeElement;
		const unclaimed = active === null || active === document.body || !active.isConnected;
		if (target?.isConnected === true && unclaimed) {
			target.focus({ preventScroll: true });
		}
	}, [open]);
}
