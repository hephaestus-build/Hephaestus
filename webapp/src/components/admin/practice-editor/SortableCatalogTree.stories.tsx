import type { Meta, StoryContext, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { Button } from "@/components/ui/button";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuItem,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { expectNoOverflowingElement } from "@/stories/reflow";

import {
	type SortableCatalogEntry,
	type SortableCatalogGroup,
	SortableCatalogTree,
} from "./SortableCatalogTree";

const groups: SortableCatalogGroup[] = [
	{ slug: "delivery", name: "Delivery", displayOrder: 0 },
	{ slug: "quality", name: "Quality", displayOrder: 1 },
];

const entries: SortableCatalogEntry[] = [
	{
		slug: "small-changes",
		name: "Small, reviewable changes",
		groupSlug: "delivery",
		displayOrder: 0,
	},
	{
		slug: "explain-why",
		name: "Explain what changed and why",
		groupSlug: "delivery",
		displayOrder: 1,
	},
	{
		slug: "no-stale-drafts",
		name: "Drafts are not left open",
		groupSlug: "delivery",
		displayOrder: 2,
	},
	{
		slug: "tests-with-changes",
		name: "Tests ship with the change",
		groupSlug: "quality",
		displayOrder: 0,
	},
	{ slug: "orphan", name: "Not filed anywhere yet", displayOrder: 0 },
];

interface HarnessProps {
	/** Groups whose own rows may not be reordered, while the groups stay valid destinations. */
	blockedOrderBuckets?: readonly string[];
	blockedDestinations?: readonly string[];
	/** Groups that may not change their own position, while everything inside them still moves. */
	disabledGroups?: readonly string[];
	/** Rows that may not move at all — not up, not down, and not into another group. */
	disabledEntries?: readonly string[];
	/** When set, only these rows are listed — the shape a search box produces. */
	visible?: readonly string[];
	onPlaceEntry: (entrySlug: string, groupSlug: string | null, position: number) => void;
	onReorderGroups: (orderedSlugs: string[]) => void;
}

const NONE: readonly string[] = [];

/**
 * Applies the moves the tree asks for, so focus restoration and the badge counts are observable.
 *
 * Its menu items are `aria-disabled` and still clickable rather than natively `disabled` — the
 * a11y-preferred shape for a menu item, and what lets a story click a refused item at all.
 */

function CatalogTreeHarness({
	blockedOrderBuckets = NONE,
	blockedDestinations = NONE,
	disabledGroups = NONE,
	disabledEntries = NONE,
	visible,
	onPlaceEntry,
	onReorderGroups,
}: HarnessProps) {
	const [rows, setRows] = useState(entries);

	const place = (entrySlug: string, groupSlug: string | null, position: number) => {
		onPlaceEntry(entrySlug, groupSlug, position);
		setRows((previous) => {
			const moved = previous.find((row) => row.slug === entrySlug);
			if (!moved) {
				return previous;
			}
			const rest = previous.filter((row) => row.slug !== entrySlug);
			const destination = rest
				.filter((row) => (row.groupSlug ?? null) === groupSlug)
				.sort((a, b) => a.displayOrder - b.displayOrder);
			destination.splice(position, 0, { ...moved, groupSlug: groupSlug ?? undefined });
			const renumbered = new Map(destination.map((row, index) => [row.slug, index]));
			return [...rest, moved].map((row) => {
				const order = renumbered.get(row.slug);
				return order == null
					? row
					: { ...row, groupSlug: groupSlug ?? undefined, displayOrder: order };
			});
		});
	};

	return (
		<SortableCatalogTree
			groups={groups}
			entries={rows}
			visibleEntrySlugs={visible ? new Set(visible) : undefined}
			groupReorderDisabled={false}
			disabledGroupSlugs={new Set(disabledGroups)}
			disabledEntrySlugs={new Set(disabledEntries)}
			blockedEntryOrderBuckets={new Set(blockedOrderBuckets)}
			blockedMoveDestinationSlugs={new Set(blockedDestinations)}
			showEntryReorderHandles
			onReorderGroups={onReorderGroups}
			onPlaceEntry={place}
			renderGroupActions={(group, move, actionTriggerRef) => (
				<Button
					variant="outline"
					size="sm"
					ref={actionTriggerRef}
					aria-disabled={!move.canMoveDown}
					aria-label={`Move ${group.name} down`}
					onClick={move.moveDown}
				>
					Group down
				</Button>
			)}
			renderEntryContent={(entry) => <span className="min-w-0 truncate">{entry.name}</span>}
			renderEntryActions={(entry, move, actionTriggerRef) => (
				<DropdownMenu>
					<DropdownMenuTrigger
						render={
							<Button
								ref={actionTriggerRef}
								variant="ghost"
								size="sm"
								aria-label={`More actions for ${entry.name}`}
							/>
						}
					>
						Actions
					</DropdownMenuTrigger>
					<DropdownMenuContent align="end">
						<DropdownMenuItem aria-disabled={!move.canMoveUp} onClick={move.moveUp}>
							Move up
						</DropdownMenuItem>
						<DropdownMenuItem aria-disabled={!move.canMoveDown} onClick={move.moveDown}>
							Move down
						</DropdownMenuItem>
						<DropdownMenuItem
							aria-disabled={move.currentGroupSlug !== null && !move.canMoveTo(null)}
							onClick={() => move.moveTo(null)}
						>
							Move to Unassigned
						</DropdownMenuItem>
						{groups.map((group) => (
							<DropdownMenuItem
								key={group.slug}
								aria-disabled={move.currentGroupSlug !== group.slug && !move.canMoveTo(group.slug)}
								onClick={() => move.moveTo(group.slug)}
							>
								Move to {group.name}
							</DropdownMenuItem>
						))}
					</DropdownMenuContent>
				</DropdownMenu>
			)}
			getEmptyLabel={(_groupSlug, total) =>
				total > 0 ? "No practice here matches the search." : "Nothing filed here yet."
			}
		/>
	);
}

/** No `autodocs`: these stories render a harness, whose props would be published as the API. */
const meta = {
	component: CatalogTreeHarness,
	parameters: { layout: "padded" },
	args: { onPlaceEntry: fn(), onReorderGroups: fn() },
} satisfies Meta<typeof CatalogTreeHarness>;

export default meta;
type Story = StoryObj<typeof meta>;

const openActions = async (canvas: StoryContext["canvas"], name: string) => {
	// A menu left over from a previous step outlives the click that dismissed it by a frame, and
	// `findByRole("menu")` would hand back that one.
	await waitFor(async () => expect(screen.queryByRole("menu")).toBeNull());
	await userEvent.click(canvas.getByRole("button", { name: `More actions for ${name}` }));
	return within(await screen.findByRole("menu"));
};

const announcement = () =>
	document.querySelector('[id^="DndLiveRegion"]')?.textContent.trim() ?? "";

const rowOf = (canvas: StoryContext["canvas"], name: string) => {
	const row = canvas.getByRole("button", { name: `Reorder ${name}` }).closest('[role="listitem"]');
	if (!(row instanceof HTMLElement)) {
		throw new Error(`No row for ${name}`);
	}
	return row;
};

/**
 * A pointer drag from a row's grip. The first move only has to clear the sensor's activation
 * distance; each step then waits on dnd-kit's own announcement rather than on a frame count, which
 * under a full suite run is never both enough and not wasteful.
 */
const dragTo = async (handle: HTMLElement, clientY: number) => {
	const box = handle.getBoundingClientRect();
	const clientX = Math.round(box.left + box.width / 2);
	const startY = Math.round(box.top + box.height / 2);
	const send = (type: string, y: number, target: EventTarget = document) =>
		target.dispatchEvent(
			new PointerEvent(type, {
				bubbles: true,
				cancelable: true,
				button: 0,
				isPrimary: true,
				pointerId: 1,
				clientX,
				clientY: y,
			}),
		);

	send("pointerdown", startY, handle);
	send("pointermove", startY + (clientY < startY ? -12 : 12));
	await waitFor(async () => expect(announcement()).toMatch(/^Picked up/u));
	send("pointermove", clientY);
	await waitFor(async () => expect(announcement()).toMatch(/^Moving/u));
	send("pointerup", clientY);
	await waitFor(async () => expect(announcement()).toMatch(/^(?:Moved|Move cancelled)/u));
};

export const Default: Story = {};

/**
 * A move unmounts the row from one bucket and mounts it in the other, so the menu's own focus
 * restoration has nothing to return to and focus falls to the document — dropping a keyboard reader
 * at the top of the page after every move (WCAG 2.2 SC 2.4.3 Focus Order).
 */
export const MovingBetweenGroupsKeepsFocusOnTheRow: Story = {
	play: async ({ args, canvas }) => {
		const menu = await openActions(canvas, "Small, reviewable changes");

		await userEvent.click(menu.getByRole("menuitem", { name: "Move to Quality" }));

		await expect(args.onPlaceEntry).toHaveBeenCalledWith("small-changes", "quality", 1);
		await expect(
			await canvas.findByRole("button", { name: "More actions for Small, reviewable changes" }),
		).toHaveFocus();
	},
};

export const AnGroupWithAMoveInFlightIsNotADestination: Story = {
	args: { blockedDestinations: ["quality"] },
	play: async ({ args, canvas }) => {
		const menu = await openActions(canvas, "Explain what changed and why");
		const blocked = menu.getByRole("menuitem", { name: "Move to Quality" });

		await expect(blocked).toHaveAttribute("aria-disabled", "true");
		// One blocked group, not a row that has stopped moving.
		await expect(menu.getByRole("menuitem", { name: "Move to Unassigned" })).toHaveAttribute(
			"aria-disabled",
			"false",
		);
		// Blocking a destination says nothing about ordering inside the row's own group.
		await expect(menu.getByRole("menuitem", { name: "Move up" })).toHaveAttribute(
			"aria-disabled",
			"false",
		);

		await userEvent.click(blocked);
		await expect(args.onPlaceEntry).not.toHaveBeenCalled();
	},
};

export const AnGroupWithAReorderInFlightStillAcceptsArrivals: Story = {
	args: { blockedOrderBuckets: ["delivery"] },
	play: async ({ args, canvas }) => {
		const menu = await openActions(canvas, "Explain what changed and why");

		await expect(menu.getByRole("menuitem", { name: "Move up" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(menu.getByRole("menuitem", { name: "Move down" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(menu.getByRole("menuitem", { name: "Move to Quality" })).toHaveAttribute(
			"aria-disabled",
			"false",
		);

		await userEvent.click(menu.getByRole("menuitem", { name: "Move up" }));
		await expect(args.onPlaceEntry).not.toHaveBeenCalled();
	},
};

/** The move itself refuses too, because the caller renders the control and may leave it reachable. */
export const TheEndsOfAnGroupHaveNowhereToGo: Story = {
	play: async ({ args, canvas }) => {
		const first = await openActions(canvas, "Small, reviewable changes");
		const up = first.getByRole("menuitem", { name: "Move up" });
		await expect(up).toHaveAttribute("aria-disabled", "true");
		await userEvent.click(up);
		await expect(args.onPlaceEntry).not.toHaveBeenCalled();

		const last = await openActions(canvas, "Drafts are not left open");
		const down = last.getByRole("menuitem", { name: "Move down" });
		await expect(down).toHaveAttribute("aria-disabled", "true");
		await userEvent.click(down);
		await expect(args.onPlaceEntry).not.toHaveBeenCalled();
	},
};

export const ReorderingInsideAnGroup: Story = {
	play: async ({ args, canvas }) => {
		const menu = await openActions(canvas, "Explain what changed and why");

		await userEvent.click(menu.getByRole("menuitem", { name: "Move down" }));

		await expect(args.onPlaceEntry).toHaveBeenCalledWith("explain-why", "delivery", 2);
	},
};

export const AnGroupWithItsOwnMoveInFlightHoldsItsPosition: Story = {
	args: { disabledGroups: ["delivery"] },
	play: async ({ args, canvas }) => {
		await expect(canvas.getByRole("button", { name: "Move Delivery down" })).toHaveAttribute(
			"aria-disabled",
			"true",
		);
		await expect(canvas.getByRole("button", { name: "Reorder Delivery" })).toBeDisabled();
		// Quality's own grip, not its "Group down": it is the last group, so down is refused by the
		// ends-of-list arithmetic whatever this prop does, and asserting that would prove nothing.
		await expect(canvas.getByRole("button", { name: "Reorder Quality" })).toBeEnabled();

		const menu = await openActions(canvas, "Explain what changed and why");
		await userEvent.click(menu.getByRole("menuitem", { name: "Move down" }));
		await expect(args.onPlaceEntry).toHaveBeenCalledWith("explain-why", "delivery", 2);
	},
};

export const ARowWithItsOwnMoveInFlightCannotLeaveEither: Story = {
	args: { disabledEntries: ["explain-why"] },
	play: async ({ args, canvas }) => {
		const pinned = await openActions(canvas, "Explain what changed and why");

		for (const name of ["Move up", "Move down", "Move to Quality", "Move to Unassigned"]) {
			await expect(pinned.getByRole("menuitem", { name })).toHaveAttribute("aria-disabled", "true");
		}
		await userEvent.click(pinned.getByRole("menuitem", { name: "Move to Quality" }));
		await expect(args.onPlaceEntry).not.toHaveBeenCalled();

		await expect(
			canvas.getByRole("button", { name: "Reorder Explain what changed and why" }),
		).toBeDisabled();

		// A guard applied to the bucket rather than to the slug would look identical on the pinned row.
		const sibling = await openActions(canvas, "Drafts are not left open");
		await expect(sibling.getByRole("menuitem", { name: "Move to Quality" })).toHaveAttribute(
			"aria-disabled",
			"false",
		);
	},
};

export const FilteringHidesRowsWithoutShrinkingTheCounts: Story = {
	args: { visible: ["explain-why", "tests-with-changes"] },
	play: async ({ canvas }) => {
		const delivery = canvas.getByRole("button", { name: /^Delivery/u });
		await expect(delivery).toHaveTextContent("3");
		await expect(canvas.getByText("Explain what changed and why")).toBeVisible();
		await expect(canvas.queryByText("Small, reviewable changes")).toBeNull();

		// Unassigned is a bucket rather than a group, and counts the same way.
		await expect(canvas.getByText("Unassigned").parentElement).toHaveTextContent("1");
		await expect(canvas.getByText("No practice here matches the search.")).toBeVisible();
	},
};

/** Every drop that reaches the server costs a round trip and a re-render of the whole catalog. */
export const DroppingARowWhereItAlreadyIsIsNotAMove: Story = {
	play: async ({ args, canvas }) => {
		const next = rowOf(canvas, "Explain what changed and why").getBoundingClientRect();

		// Onto the next row but short of its midpoint, which resolves to "before it" — the position
		// the dragged row is already in.
		await dragTo(
			canvas.getByRole("button", { name: "Reorder Small, reviewable changes" }),
			Math.round(next.top + 2),
		);

		// The drop resolved a destination, so this is the guard refusing a no-op rather than a drag
		// that never found anywhere to land and would refuse everything.
		await expect(announcement()).toBe(
			"Moved Small, reviewable changes to position 1 of 3 in Delivery.",
		);
		await expect(args.onPlaceEntry).not.toHaveBeenCalled();
	},
};

export const DraggingARowPastTheNextRowsMidpoint: Story = {
	play: async ({ args, canvas }) => {
		const next = rowOf(canvas, "Explain what changed and why").getBoundingClientRect();

		await dragTo(
			canvas.getByRole("button", { name: "Reorder Small, reviewable changes" }),
			Math.round(next.bottom - 2),
		);

		await expect(args.onPlaceEntry).toHaveBeenCalledWith("small-changes", "delivery", 1);
	},
};

export const NarrowViewport: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async ({ canvasElement }) => {
		await expectNoOverflowingElement(canvasElement);
	},
};
