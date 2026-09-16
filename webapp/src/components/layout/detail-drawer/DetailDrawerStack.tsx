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
	children: (entry: DetailStackEntry<TKind>, level: DetailDrawerLevel) => ReactNode;
}

/**
 * Each level renders the next *inside* its own content, so the child's portal nests in the parent's.
 * Base UI keeps nested portals out of the set it hides when a popup opens, which is what lets a
 * stack reach the accessibility tree at every depth.
 *
 * A dismissal shuts the drawer first and navigates when the exit animation ends, so the URL lags it:
 * dropping the level first would render it with an entry the caller no longer has data for. Clearing
 * `closingDepth` before the stack catches up re-opens the level that just left.
 */
export function DetailDrawerStack<TKind extends string>({
	stack,
	guardedKinds,
	onClose,
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

/** A component, not a loop, because it owns `useArrived` and each level mounts at a different time. */
function DetailDrawerLevelView<TKind extends string>({
	depth,
	stack,
	guardedKinds,
	closingDepth,
	setClosingDepth,
	onClose,
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
			key={detailStackKey(entry)}
			// Base UI shuts a parent's children anyway; this keeps the React tree in step.
			open={arrived && (closingDepth === null || depth < closingDepth)}
			swipeDirection="right"
			onOpenChange={(next) => {
				if (next) {
					return;
				}
				// Every way out is the same way out: Escape, a press on the page, a swipe and the
				// panel's own controls all just close it. What protects a draft is the prompt
				// `useUnsavedChanges` raises on the navigation, not a gesture this refuses.
				if (guarded) {
					onClose(depth);
					return;
				}
				setClosingDepth(depth);
			}}
			onOpenChangeComplete={(next) => {
				if (next || closingDepth !== depth) {
					return;
				}
				onClose(depth);
			}}
		>
			<DrawerContent size="detail" dimWhenNested={false}>
				{children(entry, { depth, nested: depth > 0 })}
				{child && (
					<DetailDrawerLevelView
						depth={depth + 1}
						stack={stack}
						guardedKinds={guardedKinds}
						closingDepth={closingDepth}
						setClosingDepth={setClosingDepth}
						onClose={onClose}
					>
						{children}
					</DetailDrawerLevelView>
				)}
			</DrawerContent>
		</Drawer>
	);
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
