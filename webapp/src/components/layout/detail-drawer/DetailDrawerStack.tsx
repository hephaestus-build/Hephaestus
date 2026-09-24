import { type ReactNode, useLayoutEffect, useState } from "react";

import { Drawer, DrawerContent } from "@/components/ui/drawer";

import { type DetailStackEntry, detailStackKey } from "./detail-stack";

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
	/**
	 * Panel width preset for every level; "detailWide" for surfaces that need most of the viewport.
	 */
	size?: "detail" | "detailWide";
	children: (entry: DetailStackEntry<TKind>, level: DetailDrawerLevel) => ReactNode;
}

/**
 * Each level renders the next *inside* its own content, so the child's portal nests in the
 * parent's. Base UI keeps nested portals out of the set it hides when a popup opens, which is what
 * lets a stack reach the accessibility tree at every depth.
 *
 * A press on a visible layer brings that layer to the front: the page's backdrop dismisses every
 * level, the strip a covered level leaves showing dismisses everything over it, and the front
 * panel is untouched. Escape, the header control and a swipe still close one level. The layer is
 * read off the press's position, because the front level's viewport covers the whole screen and
 * is what every outside press lands on.
 *
 * A dismissal shuts the drawer first and navigates when the exit animation ends, so the URL lags
 * it: dropping the level first would render it with an entry the caller no longer has data for.
 * Clearing `closingDepth` before the stack catches up re-opens the level that just left.
 */
export function DetailDrawerStack<TKind extends string>({
	stack,
	guardedKinds,
	onClose,
	size = "detail",
	children,
}: DetailDrawerStackProps<TKind>) {
	const [closingDepth, setClosingDepth] = useState<number | null>(null);

	// Cleared when the stack catches up, not on the completion frame — see the JSDoc above.
	if (closingDepth !== null && stack.length <= closingDepth) {
		setClosingDepth(null);
	}

	// Mounting is the arrival `useArrived` needs; a component left mounted on `null` has already
	// spent its first render.
	if (stack.length === 0) {
		return null;
	}
	return (
		<DetailDrawerLevelView
			depth={0}
			stack={stack}
			guardedKinds={guardedKinds}
			closingDepth={closingDepth}
			setClosingDepth={setClosingDepth}
			onClose={onClose}
			size={size}
		>
			{children}
		</DetailDrawerLevelView>
	);
}

interface DetailDrawerLevelViewProps<TKind extends string> extends DetailDrawerStackProps<TKind> {
	depth: number;
	closingDepth: number | null;
	setClosingDepth: (depth: number | null) => void;
}

/**
 * A component, not a loop, because it owns `useArrived` and each level mounts at a different time.
 */
function DetailDrawerLevelView<TKind extends string>({
	depth,
	stack,
	guardedKinds,
	closingDepth,
	setClosingDepth,
	onClose,
	size = "detail",
	children,
}: DetailDrawerLevelViewProps<TKind>) {
	const entry = stack[depth];
	const arrived = useArrived();
	if (!entry) {
		return null;
	}

	const child = stack[depth + 1];
	const guarded = guardedKinds?.includes(entry.kind) ?? false;
	return (
		<Drawer
			// The entry is the level: another id at the same depth is another level, mounted fresh, so
			// a form seeded from the entry starts over instead of showing the last id's draft under
			// this one's title (`webapp/AGENTS.md` § Seeding a form from props). Keying on the kind
			// alone was tried and left that reset to each editor's mount site, where none did it.
			key={detailStackKey(entry)}
			// Base UI shuts a parent's children anyway; this keeps the React tree in step.
			open={arrived && (closingDepth === null || depth < closingDepth)}
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
				if (next || closingDepth !== depth) {
					return;
				}
				onClose(depth);
			}}
		>
			<DrawerContent size={size} dimWhenNested={false} data-detail-depth={depth}>
				{children(entry, { depth, nested: depth > 0 })}
				{child && (
					<DetailDrawerLevelView
						depth={depth + 1}
						stack={stack}
						guardedKinds={guardedKinds}
						closingDepth={closingDepth}
						setClosingDepth={setClosingDepth}
						onClose={onClose}
						size={size}
					>
						{children}
					</DetailDrawerLevelView>
				)}
			</DrawerContent>
		</Drawer>
	);
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
 * False on the first render, true from the commit onwards. Base UI's `useTransitionStatus` seeds
 * `mounted` from `open`, so a drawer that mounts already open never runs the branch that sets
 * `starting`, gets no `data-starting-style` frame, and appears at rest. Levels mount from the URL,
 * so all of them do.
 *
 * A layout effect, not a frame: `requestAnimationFrame` can land after a caller has looked for the
 * panel and found nothing.
 */
function useArrived(): boolean {
	const [arrived, setArrived] = useState(false);
	// oxlint-disable-next-line react/set-state-in-effect -- The extra render is the point: the closed frame has to be committed before the level opens, which is the only way Base UI runs its enter transition.
	useLayoutEffect(() => setArrived(true), []);
	return arrived;
}
