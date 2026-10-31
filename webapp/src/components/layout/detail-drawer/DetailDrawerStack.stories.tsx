import type { Meta, StoryObj } from "@storybook/react-vite";
import { useState } from "react";
import { flushSync } from "react-dom";
import { expect, fn, screen, userEvent, waitFor, within } from "storybook/test";

import { DetailDrawerHeader } from "@/components/layout/detail-drawer/DetailDrawerHeader";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerDescription, DrawerFooter, DrawerTitle } from "@/components/ui/drawer";
import { Input } from "@/components/ui/input";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { withAnimationTime } from "@/stories/animation-time";
import { withPageBehind } from "@/stories/decorators";
import { expectSettledVisible } from "@/stories/overlay";
import { Stateful } from "@/stories/stateful";
import { nextFrame } from "@/test/async";

import type { DetailStackEntry } from "./detail-stack";
import { DetailDrawerStack } from "./DetailDrawerStack";

const popups = () => [...document.querySelectorAll<HTMLElement>('[data-slot="drawer-popup"]')];

/** The level at `depth`, so a story that has fewer open than it expects says which. */
const popupAt = (depth: number): HTMLElement => {
	const popup = popups()[depth];
	if (!popup) {
		throw new Error(`Expected at least ${depth + 1} open drawer level(s).`);
	}
	return popup;
};

/** The page beside the panel, which is what a dismissing press lands on. */
const pageBesideThePanel = (): Element => {
	const page = document.elementFromPoint(20, 200);
	if (!page) {
		throw new Error("Nothing is under the point beside the panel.");
	}
	return page;
};

const meta = {
	component: DetailDrawerStack,
	parameters: { layout: "fullscreen" },
	decorators: [withPageBehind],
	args: {
		stack: [{ kind: "practice", id: "describe-what-and-why" }],
		onClose: fn(),
		children: (entry, level) => (
			<>
				<DetailDrawerHeader nested={level.nested}>
					<div className="min-w-0 flex-1 space-y-0.5">
						<DrawerTitle>{`${entry.kind} · ${entry.id}`}</DrawerTitle>
						<DrawerDescription>{`Level ${level.depth + 1}`}</DrawerDescription>
					</div>
				</DetailDrawerHeader>
				<DrawerBody>
					<p className="text-sm text-muted-foreground">
						Whatever this level is about. The page that opened it is still mounted behind, and stays
						readable in the column the stack leaves showing.
					</p>
				</DrawerBody>
				<DrawerFooter>
					<Button>Primary action</Button>
				</DrawerFooter>
			</>
		),
	},
	argTypes: {
		// Neither takes a useful control: one is an array of records, the other a render prop that
		// the story would break by editing.
		stack: { control: false },
		children: { control: false },
	},
	// Stateful so a dismiss actually dismisses. A frozen `stack` with an `fn()` leaves Escape, an
	// outside press and the header control all looking broken while nothing is wrong.
	render: (args) => (
		<Stateful initial={args.stack}>
			{(stack, setStack) => (
				<DetailDrawerStack
					{...args}
					stack={stack}
					onClose={(depth) => {
						args.onClose(depth);
						setStack(stack.slice(0, depth));
					}}
				/>
			)}
		</Stateful>
	),
	tags: ["autodocs"],
} satisfies Meta<typeof DetailDrawerStack>;

export default meta;
type Story = StoryObj<typeof meta>;

export const OneLevel: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		await expect(popups()).toHaveLength(1);
	},
};

export const DismissedLevelSlidesOut: Story = {
	play: async () => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		const popup = popupAt(0);
		await userEvent.click(screen.getByRole("button", { name: "Close" }));
		// Still mounted and still carrying its content, animating out. Dropping it on the URL change
		// makes a dismissal vanish in one frame instead.
		await expect(popup).toHaveAttribute("data-ending-style");
		await expect(popup.textContent).toContain("practice · describe-what-and-why");
		await waitFor(async () => expect(popups()).toHaveLength(0));
	},
};

export const PerLevelDataSurvivesDismissal: Story = {
	render: (args) => (
		<Stateful initial={args.stack}>
			{(stack, setStack) => {
				const perLevel = stack.map((entry) => ({ heading: entry.id }));
				return (
					<DetailDrawerStack
						stack={stack}
						onClose={(depth) => {
							args.onClose(depth);
							setStack(stack.slice(0, depth));
						}}
					>
						{(_entry, level) => (
							<>
								<DetailDrawerHeader nested={level.nested}>
									<DrawerTitle>{perLevel[level.depth]?.heading}</DrawerTitle>
								</DetailDrawerHeader>
								<DrawerBody>
									<p className="text-sm text-muted-foreground">Level content.</p>
								</DrawerBody>
							</>
						)}
					</DetailDrawerStack>
				);
			}}
		</Stateful>
	),
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("describe-what-and-why"));
		await userEvent.click(screen.getByRole("button", { name: "Close" }));
		await waitFor(async () => expect(popups()).toHaveLength(0));
		await expect(args.onClose).toHaveBeenCalledWith(0);
	},
};

/** A level's own state, seeded once at mount the way an editor seeds its form from the entry. */
function DraftField() {
	const [draft, setDraft] = useState("");
	return (
		<Input aria-label="Draft" value={draft} onChange={(event) => setDraft(event.target.value)} />
	);
}

/**
 * The entry is the level. The same kind at the same depth with another id is another level,
 * mounted fresh, so a draft typed into the first never appears under the second's title — the
 * trap `webapp/AGENTS.md` § Seeding a form from props describes, which every editor level relies
 * on the stack to prevent rather than keying itself on the id.
 */
export const AnotherIdIsAnotherLevel: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	render: (args) => (
		<Stateful initial={args.stack}>
			{(stack, setStack) => (
				<DetailDrawerStack
					{...args}
					stack={stack}
					onClose={(depth) => {
						args.onClose(depth);
						setStack(stack.slice(0, depth));
					}}
				>
					{(entry, level) => (
						<>
							<DetailDrawerHeader nested={level.nested}>
								<DrawerTitle>{`${entry.kind} · ${entry.id}`}</DrawerTitle>
							</DetailDrawerHeader>
							<DrawerBody>
								<DraftField />
							</DrawerBody>
							<DrawerFooter>
								{/* Inside the level, since a press on the page would dismiss it instead. */}
								<Button
									variant="outline"
									onClick={() =>
										setStack([
											...stack.slice(0, level.depth),
											{ kind: entry.kind, id: "review-ready-work" },
										])
									}
								>
									Open a sibling
								</Button>
							</DrawerFooter>
						</>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	play: async () => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		await userEvent.type(screen.getByRole("textbox", { name: "Draft" }), "the first level's draft");
		await userEvent.click(screen.getByRole("button", { name: "Open a sibling" }));
		await expectSettledVisible(await screen.findByText("practice · review-ready-work"));
		await expect(popups()).toHaveLength(1);
		await expect(screen.getByRole("textbox", { name: "Draft" })).toHaveValue("");
	},
};

export const PressingThePageDismisses: Story = {
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		// A press on the page beside the panel is the fastest way out, and the reason the panel is
		// narrower than the viewport.
		await userEvent.pointer({
			target: pageBesideThePanel(),
			coords: { clientX: 20, clientY: 200 },
			keys: "[MouseLeft]",
		});
		// Both `waitFor`: the level animates out first and the URL follows, so neither the popup
		// leaving nor the callback firing happens on the click itself.
		await waitFor(async () => expect(popups()).toHaveLength(0));
		await expect(args.onClose).toHaveBeenCalledWith(0);
	},
};

export const GuardedLevelLeavesTheSameWays: Story = {
	args: { guardedKinds: ["practice"] },
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));

		// A guarded level is not a level that refuses to be dismissed — refusing silently is
		// indistinguishable from a broken drawer. It closes on a press on the page like every other
		// level; what protects a draft is the prompt the navigation raises, which this level has none
		// of. It skips the exit animation, so `onClose` lands without one.
		await userEvent.pointer({
			target: pageBesideThePanel(),
			coords: { clientX: 20, clientY: 200 },
			keys: "[MouseLeft]",
		});
		await waitFor(async () => expect(args.onClose).toHaveBeenCalledWith(0));
	},
};

export const ArrivesWithAnEnterTransition: Story = {
	args: { stack: [] },
	parameters: { chromatic: { disableSnapshot: true } },
	render: (args) => (
		<Stateful initial={args.stack}>
			{(stack, setStack) => (
				<>
					<Button onClick={() => setStack([{ kind: "practice", id: "describe-what-and-why" }])}>
						Open a level
					</Button>
					<DetailDrawerStack
						{...args}
						stack={stack}
						onClose={(depth) => setStack(stack.slice(0, depth))}
					/>
				</>
			)}
		</Stateful>
	),
	play: async () => {
		// Observed rather than polled: the starting style lasts one frame, which `waitFor` steps over.
		// It is also what has to be asserted — a level mounts when its entry appears, so it mounts
		// already open, and Base UI seeds `mounted` from `open`, which means the branch that sets
		// `starting` never runs and the panel is simply *there*, at rest, with no transition at all.
		let started = false;
		const observer = new MutationObserver((records) => {
			for (const record of records) {
				for (const node of record.addedNodes) {
					if (!(node instanceof HTMLElement)) {
						continue;
					}
					const popup = node.matches('[data-slot="drawer-popup"]')
						? node
						: node.querySelector<HTMLElement>('[data-slot="drawer-popup"]');
					if (popup?.dataset.startingStyle !== undefined) {
						started = true;
					}
				}
			}
		});
		observer.observe(document.body, { childList: true, subtree: true });

		await userEvent.click(screen.getByRole("button", { name: "Open a level" }));
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		observer.disconnect();
		await expect(started).toBe(true);
	},
};

export const DetailSizeKeepsItsPeek: Story = {
	args: {
		stack: [
			{ kind: "group", id: "review-ready-work" },
			{ kind: "practice", id: "describe-what-and-why" },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async () => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		// The class, not the resolved width: `motion-reduce:[--peek:0px]` wins under this suite's
		// forced `prefers-reduced-motion`, so the computed value proves nothing. The bug was upstream
		// of the cascade anyway — `cn()` dedupes an arbitrary custom property by name and keeps the
		// last, so a `--peek` default declared after the size variant silently replaced it.
		await expect(popupAt(0).className).toContain("[--peek:6rem]");
	},
};

/**
 * The Practice profile is the reference every stack follows: each level is the same panel, so a
 * level over another covers it to the same depth and leaves the same column showing, whatever kind
 * either level is. A wide trace under a narrower sheet left twice the column showing, half of it
 * scaled-down trace, and a sheet kept on a GPU layer at rest drew its text soft.
 */
export const EveryLevelCoversToTheSameDepth: Story = {
	args: {
		stack: [
			{ kind: "trace", id: "review:42" },
			{ kind: "feedback", id: "33333333" },
			{ kind: "practice", id: "describe-what-and-why" },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	play: async () => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		const levels = popups();
		await expect(levels).toHaveLength(3);
		// Layout widths, which the stack's step-back transform leaves alone: the depth a level covers
		// to is its width, so equal widths are equal depths.
		const widths = levels.map((popup) => popup.offsetWidth);
		await expect(new Set(widths).size).toBe(1);
		for (const popup of levels) {
			// The class, not the resolved value, for the reason `DetailSizeKeepsItsPeek` gives.
			await expect(popup.className).toContain("[--peek:6rem]");
			await expect(popup.className).toContain("will-change-auto");
		}
		for (const overlay of document.querySelectorAll('[data-slot="drawer-overlay"]')) {
			await expect(getComputedStyle(overlay).backdropFilter).toBe("none");
		}
	},
};

export const DismissedLevelDoesNotComeBack: Story = {
	args: {
		stack: [
			{ kind: "group", id: "review-ready-work" },
			{ kind: "practice", id: "describe-what-and-why" },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	render: (args) => (
		<Stateful initial={args.stack}>
			{(stack, setStack) => (
				<DetailDrawerStack
					{...args}
					stack={stack}
					// The app's `onClose` navigates, so the stack shrinks a frame or more after the exit
					// completes. Synchronous state hides the bug this story exists for.
					onClose={(depth) => {
						args.onClose(depth);
						requestAnimationFrame(() => {
							requestAnimationFrame(() => setStack(stack.slice(0, depth)));
						});
					}}
				/>
			)}
		</Stateful>
	),
	play: async () => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		const parent = popupAt(0);
		await userEvent.click(screen.getByRole("button", { name: "Back" }));

		// Watch every frame until the level is gone. Clearing the closing depth on the completion
		// frame re-opened it while the navigation was still in flight: it popped back in, and this
		// level snapped to its stepped-back position and animated forward a second time.
		const nested: string[] = [];
		for (let frame = 0; frame < 40 && popups().length > 1; frame += 1) {
			await nextFrame();
			nested.push(getComputedStyle(parent).getPropertyValue("--nested-drawers").trim());
		}
		await waitFor(async () => expect(popups()).toHaveLength(1));
		await expect(nested.indexOf("1")).toBe(-1);
	},
};

/**
 * A caller may swap the level in front while it is closing — a queue whose decision lands after the
 * reader pressed Escape. The level swapped in is a level of its own and opens, rather than mounting
 * into a close it never took part in, where it stayed shut and the close never finished.
 */
export const LevelSwappedInWhileClosingOpens: Story = {
	parameters: { chromatic: { disableSnapshot: true } },
	// The decision lands inside the exit, so the exit needs its own time.
	decorators: [withAnimationTime()],
	render: (args) => (
		<Stateful initial={args.stack}>
			{(stack, setStack) => (
				<DetailDrawerStack
					{...args}
					stack={stack}
					onClose={(depth) => {
						args.onClose(depth);
						setStack(stack.slice(0, depth));
					}}
				>
					{(entry, level) => (
						<>
							<DetailDrawerHeader nested={level.nested}>
								<DrawerTitle>{`${entry.kind} · ${entry.id}`}</DrawerTitle>
							</DetailDrawerHeader>
							<DrawerFooter>
								{/* Answered later, as a request is: after the Escape below, inside the exit. */}
								<Button
									onClick={() => {
										setTimeout(() => setStack([{ kind: entry.kind, id: "review-ready-work" }]), 80);
									}}
								>
									Decide
								</Button>
							</DrawerFooter>
						</>
					)}
				</DetailDrawerStack>
			)}
		</Stateful>
	),
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		await userEvent.click(screen.getByRole("button", { name: "Decide" }));
		await userEvent.keyboard("{Escape}");

		await expectSettledVisible(await screen.findByText("practice · review-ready-work"));
		await expect(popups()).toHaveLength(1);
		await expect(args.onClose).not.toHaveBeenCalled();
	},
};

/** A press at a point of the page, on whatever is drawn there. */
async function pressAt(clientX: number, clientY: number) {
	const target = document.elementFromPoint(clientX, clientY);
	if (!target) {
		throw new Error(`Nothing is under ${clientX},${clientY}.`);
	}
	await userEvent.pointer({ target, coords: { clientX, clientY }, keys: "[MouseLeft]" });
}

/**
 * A press on a visible layer brings that layer to the front. With three levels open, a press on
 * the strip the middle level leaves showing closes the front level only; a press on the page
 * beside the stack closes every level. The suite's reduced motion zeroes the peek, which would
 * leave no strip to press, so this story alone restores it in a plain stylesheet — unlayered, so
 * it outranks the utility.
 */
export const PressingALayerBringsItToTheFront: Story = {
	args: {
		stack: [
			{ kind: "practices", id: "all" },
			{ kind: "group", id: "review-ready-work" },
			{ kind: "practice", id: "describe-what-and-why" },
		],
	},
	parameters: { chromatic: { disableSnapshot: true } },
	decorators: [
		(Story) => (
			<>
				{/* oxlint-disable-next-line shadcn/no-inline-styles -- The popup is portaled and its peek is a utility the suite's reduced motion zeroes; only an unlayered rule outranks it, and only this story needs it. */}
				<style>{`[data-slot="drawer-popup"] { --peek: 6rem; }`}</style>
				<Story />
			</>
		),
	],
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		await expect(popups()).toHaveLength(3);

		// The middle level's strip: inside its panel, left of the front panel's edge.
		const middle = popupAt(1).getBoundingClientRect();
		const front = popupAt(2).getBoundingClientRect();
		await expect(front.left).toBeGreaterThan(middle.left);
		await pressAt(middle.left + (front.left - middle.left) / 2, 200);
		await waitFor(async () => expect(popups()).toHaveLength(2));
		await expect(args.onClose).toHaveBeenLastCalledWith(2);
		await expect(popupAt(1).textContent).toContain("group · review-ready-work");

		// The page beside the stack belongs to no level and clears it.
		await pressAt(20, 200);
		await waitFor(async () => expect(popups()).toHaveLength(0));
		await expect(args.onClose).toHaveBeenLastCalledWith(0);
	},
};

/**
 * Every region a panel is built from reaches both edges of it, with nothing left over underneath.
 * The footer is the one that goes wrong quietly: written as a sticky bar inside the scrolling body
 * it inherits that body's padding, so it floats short of each edge and leaves a strip of dead space
 * below itself at the end of the scroll.
 */
export const RegionsReachThePanelEdges: Story = {
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { disableSnapshot: true } },
	args: {
		children: (entry, level) => (
			<>
				<DetailDrawerHeader nested={level.nested}>
					<DrawerTitle>{entry.id}</DrawerTitle>
				</DetailDrawerHeader>
				<DrawerBody>
					{Array.from({ length: 20 }, (_, index) => (
						<p key={index}>Enough content that the body genuinely scrolls.</p>
					))}
				</DrawerBody>
				<DrawerFooter>
					<Button variant="outline">Cancel</Button>
					<Button>Save changes</Button>
				</DrawerFooter>
			</>
		),
	},
	play: async () => {
		await expectSettledVisible(await screen.findByText("describe-what-and-why"));
		const popup = popupAt(0);
		const panel = popup.getBoundingClientRect();
		const region = (slot: string) =>
			popup.querySelector<HTMLElement>(`[data-slot="${slot}"]`)?.getBoundingClientRect();

		// `abs`, and a tolerance only for the panel's own border: a region that hangs *outside* the
		// panel is the same defect as one that stops short of it, and a one-sided check passes it.
		const BORDER = 2;
		const flush = (value: number) => Math.abs(value) <= BORDER;
		for (const slot of ["drawer-header", "drawer-body", "drawer-footer"]) {
			const box = region(slot);
			await expect({
				slot,
				left: flush((box?.left ?? 0) - panel.left),
				right: flush(panel.right - (box?.right ?? 0)),
			}).toEqual({ slot, left: true, right: true });
		}
		await expect(flush(panel.bottom - (region("drawer-footer")?.bottom ?? 0))).toBe(true);

		// A form disables every control while it submits, and then nothing inside the scrolling
		// region is focusable — so the region itself has to be.
		await expect(popup.querySelector('[data-slot="drawer-body"]')).toHaveAttribute("tabindex", "0");
	},
};

/**
 * Closing the last level gives focus back to the control that opened it, even after a Select inside
 * the level was used: its listbox is portalled beside the level's dialog, not into it, so its options
 * must never stand in for the opener.
 */
export const FocusReturnsToTheOpener: Story = {
	args: { stack: [] },
	parameters: { chromatic: { disableSnapshot: true } },
	render: (args) => (
		<Stateful initial={args.stack}>
			{(stack, setStack) => (
				<>
					<Button
						className="m-4"
						onClick={(event) => {
							// As a level opened from the address, the drawer finds nothing focused to return to.
							event.currentTarget.blur();
							setStack([{ kind: "practice", id: "describe-what-and-why" }]);
						}}
					>
						Open level
					</Button>
					<DetailDrawerStack
						{...args}
						stack={stack}
						onClose={(depth) => setStack(stack.slice(0, depth))}
					>
						{(entry, level) => (
							<>
								<DetailDrawerHeader nested={level.nested}>
									<DrawerTitle>{`${entry.kind} · ${entry.id}`}</DrawerTitle>
								</DetailDrawerHeader>
								<DrawerBody>
									<Select
										items={[
											{ value: "all", label: "All" },
											{ value: "met", label: "Met" },
										]}
										defaultValue="all"
									>
										<SelectTrigger aria-label="Outcome">
											<SelectValue />
										</SelectTrigger>
										<SelectContent aria-label="Outcome">
											<SelectItem value="all">All</SelectItem>
											<SelectItem value="met">Met</SelectItem>
										</SelectContent>
									</Select>
								</DrawerBody>
							</>
						)}
					</DetailDrawerStack>
				</>
			)}
		</Stateful>
	),
	play: async () => {
		const opener = screen.getByRole("button", { name: "Open level" });
		await userEvent.click(opener);
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		await userEvent.click(screen.getByRole("combobox", { name: "Outcome" }));
		await userEvent.click(await screen.findByRole("option", { name: "Met" }));
		await waitFor(async () => expect(screen.queryByRole("listbox")).toBeNull());
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => expect(popups()).toHaveLength(0));
		await waitFor(async () => expect(opener).toHaveFocus());
	},
};

export const Closed: Story = {
	args: { stack: [] },
	play: async () => {
		await expect(popups()).toHaveLength(0);
	},
};

export const TwoLevels: Story = {
	args: {
		stack: [
			{ kind: "group", id: "review-ready-work" },
			{ kind: "practice", id: "describe-what-and-why" },
		],
	},
	play: async ({ args }) => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		const parent = popupAt(0);
		const frontmost = popupAt(1);
		// The level behind knows it is behind, which is what drives the step-back and dim.
		await expect(parent).toHaveAttribute("data-nested-drawer-open");
		await expect(getComputedStyle(parent).getPropertyValue("--nested-drawers").trim()).toBe("1");
		await expect(getComputedStyle(frontmost).getPropertyValue("--nested-drawers").trim()).toBe("0");
		const back = screen.getByRole("button", { name: "Back" });
		await userEvent.click(back);
		await waitFor(async () => expect(args.onClose).toHaveBeenCalledWith(1));
		await expect(await screen.findByText("group · review-ready-work")).toBeVisible();
		await waitFor(async () => expect(popups()).toHaveLength(1));
		await waitFor(async () => expect(parent.contains(document.activeElement)).toBe(true));
		await userEvent.tab();
		await expect(parent.contains(document.activeElement)).toBe(true);
	},
};

export const NestedFocusReturnsToTheOpener: Story = {
	render: (args) => (
		<Stateful<DetailStackEntry[]> initial={[]}>
			{(stack, setStack) => (
				<>
					<Button onClick={() => setStack([{ kind: "group", id: "review-ready-work" }])}>
						Open group
					</Button>
					<DetailDrawerStack
						{...args}
						stack={stack}
						onClose={(depth) => setStack(stack.slice(0, depth))}
					>
						{(entry, level) => (
							<>
								<DetailDrawerHeader nested={level.nested}>
									<DrawerTitle>{`${entry.kind} · ${entry.id}`}</DrawerTitle>
								</DetailDrawerHeader>
								<DrawerBody>
									{level.depth === 0 ? (
										<Button
											onClick={() =>
												setStack([...stack, { kind: "practice", id: "describe-what-and-why" }])
											}
										>
											Open practice
										</Button>
									) : (
										<Button
											onClick={() => {
												flushSync(() =>
													setStack([
														{ kind: "group", id: "other-group" },
														{ kind: "practice", id: "describe-what-and-why" },
													]),
												);
												setStack([
													{ kind: "group", id: "review-ready-work" },
													{ kind: "practice", id: "describe-what-and-why" },
												]);
											}}
										>
											Replace parent and return
										</Button>
									)}
								</DrawerBody>
							</>
						)}
					</DetailDrawerStack>
				</>
			)}
		</Stateful>
	),
	play: async () => {
		const opener = screen.getByRole("button", { name: "Open group" });
		await userEvent.click(opener);
		await expectSettledVisible(await screen.findByText("group · review-ready-work"));
		const practice = screen.getByRole("button", { name: "Open practice" });
		await userEvent.click(practice);
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		await userEvent.click(within(popupAt(1)).getByRole("button", { name: "Back" }));
		await waitFor(async () => expect(popups()).toHaveLength(1));
		await waitFor(async () => expect(practice).toHaveFocus());
		await userEvent.click(practice);
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		await userEvent.keyboard("{Escape}");
		await waitFor(async () => expect(popups()).toHaveLength(1));
		await waitFor(async () => expect(practice).toHaveFocus());
		await userEvent.click(practice);
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		let replaced = false;
		const observer = new MutationObserver((records) => {
			for (const record of records) {
				for (const node of record.addedNodes) {
					if (node instanceof HTMLElement && node.textContent.includes("group · other-group")) {
						replaced = true;
					}
				}
			}
		});
		observer.observe(document.body, { childList: true, subtree: true });
		try {
			await userEvent.click(screen.getByRole("button", { name: "Replace parent and return" }));
			await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
			await expect(replaced).toBe(true);
			const parent = popupAt(0);
			await userEvent.click(within(popupAt(1)).getByRole("button", { name: "Back" }));
			await waitFor(async () => expect(popups()).toHaveLength(1));
			await waitFor(async () => expect(parent.contains(document.activeElement)).toBe(true));
			await userEvent.tab();
			await expect(parent.contains(document.activeElement)).toBe(true);
		} finally {
			observer.disconnect();
		}
		await userEvent.click(within(popupAt(0)).getByRole("button", { name: "Close" }));
		await waitFor(async () => expect(popups()).toHaveLength(0));
		await waitFor(async () => expect(opener).toHaveFocus());
	},
};

export const NarrowViewport: Story = {
	args: {
		stack: [
			{ kind: "group", id: "review-ready-work" },
			{ kind: "practice", id: "describe-what-and-why" },
		],
	},
	parameters: { viewport: { defaultViewport: "reflow" }, chromatic: { viewports: [320] } },
	play: async () => {
		await expectSettledVisible(await screen.findByText("practice · describe-what-and-why"));
		const frontmost = popupAt(1);
		await expect(frontmost.getBoundingClientRect().width).toBe(window.innerWidth);
	},
};

export const DarkMode: Story = {
	args: {
		stack: [
			{ kind: "group", id: "review-ready-work" },
			{ kind: "practice", id: "describe-what-and-why" },
		],
	},
	globals: { theme: "dark" },
};
