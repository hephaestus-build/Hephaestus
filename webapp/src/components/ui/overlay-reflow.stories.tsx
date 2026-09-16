import type { Meta, StoryObj } from "@storybook/react-vite";
import type { ReactElement, ReactNode } from "react";
import { expect, screen, userEvent } from "storybook/test";

import {
	Combobox,
	ComboboxContent,
	ComboboxItem,
	ComboboxList,
	ComboboxTrigger,
	ComboboxValue,
} from "@/components/ui/combobox";
import {
	Dialog,
	DialogBody,
	DialogContent,
	DialogHeader,
	DialogTitle,
} from "@/components/ui/dialog";
import {
	DropdownMenu,
	DropdownMenuContent,
	DropdownMenuItem,
	DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { HoverCard, HoverCardContent, HoverCardTrigger } from "@/components/ui/hover-card";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ScrollArea } from "@/components/ui/scroll-area";
import {
	Select,
	SelectContent,
	SelectItem,
	SelectTrigger,
	SelectValue,
} from "@/components/ui/select";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { settledPopup } from "@/test/overlay";
import { expectNoPageOverflow, expectOverlayFollowsTrigger } from "@/test/reflow";

/**
 * A cross-cutting regression suite, not a component's own stories: six primitives in this kit hang
 * their popup off a Base UI `Positioner`, and all six share one reflow hazard. It lives under
 * `Tests/` rather than beside the design-system entries because there is no `overlay-reflow`
 * component to document — the subject is the hazard, and the six primitives are its cases.
 *
 * Base UI positions with `position: absolute` by default: the positioner is laid out in the
 * document at `left: 0` and moved into place by a transform. Its box is shrink-to-fit, so it only
 * grows to the width of its containing block once the popup asks for the whole viewport — and then
 * the collision shift, which wants padding on each side, has nowhere to put it and clamps the popup
 * flush left, spilling past the right edge *of the document*. A few pixels of `scrollWidth` over
 * `clientWidth` is enough to drag the whole page sideways: WCAG 2.2 SC 1.4.10 (Reflow).
 *
 * Tooltips, popovers, hover cards and menus reach that width in this app — a tooltip's own
 * `max-w-xs` is the narrowest viewport exactly, and the others are handed `w-80` or a label carrying
 * somebody's name — so those four carry `positionMethod="fixed"` and position against the viewport.
 * `Select` and `Combobox` size their popup from the anchor, which is itself laid out inside the page
 * and so can never be wider than it; they keep Base UI's default, and are covered here so that a
 * caller who changes that finds out.
 *
 * Each story renders its overlay at the widest shape the app asks for and *opens* it before
 * measuring: the closed state never overflowed, which is exactly why this shipped.
 */
const meta = {
	tags: ["autodocs"],
	parameters: {
		layout: "fullscreen",
		viewport: { defaultViewport: "reflow" },
		chromatic: { disableSnapshot: true },
	},
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

const cardBody =
	"Reviews run against the head of the pull request, and the author is notified once.";
const menuItemLabel = "Remove felix.dietrich@tum.de from this workspace";
const optionLabel = "Deliver feedback automatically";

/** Anchored hard right, where the collision shift has to act. */
function Page({ children }: { children: ReactNode }) {
	return (
		<div className="w-full p-4">
			<div className="flex justify-end">{children}</div>
		</div>
	);
}

function reflowStory(render: () => ReactElement, open: () => Promise<unknown>): Story {
	return {
		render,
		play: async () => {
			await expectNoPageOverflow();
			await open();
			await settledPopup();
			await expectNoPageOverflow();
		},
	};
}

export const PopoverOverlay = reflowStory(
	() => (
		<Page>
			<Popover>
				<PopoverTrigger>Open popover</PopoverTrigger>
				{/* `w-80` is 320px — the whole viewport — and is what the app passes. */}
				<PopoverContent className="w-80" aria-label="Review settings">
					{cardBody}
				</PopoverContent>
			</Popover>
		</Page>
	),
	async () => {
		await userEvent.click(screen.getByRole("button", { name: "Open popover" }));
		return screen.findByText(cardBody);
	},
);

export const HoverCardOverlay = reflowStory(
	() => (
		<Page>
			<HoverCard>
				{/* No delay: this is about where the card lands, not how long a hover has to last. */}
				<HoverCardTrigger
					closeDelay={0}
					delay={0}
					render={<button type="button">Show details</button>}
				/>
				<HoverCardContent className="w-80">{cardBody}</HoverCardContent>
			</HoverCard>
		</Page>
	),
	async () => {
		await userEvent.hover(screen.getByRole("button", { name: "Show details" }));
		return screen.findByText(cardBody);
	},
);

export const TooltipOverlay = reflowStory(
	() => (
		<Page>
			<Tooltip>
				<TooltipTrigger>Why is this off?</TooltipTrigger>
				{/* Long enough to reach `max-w-xs`, which is the viewport width at 320px. */}
				<TooltipContent>{cardBody}</TooltipContent>
			</Tooltip>
		</Page>
	),
	async () => {
		await userEvent.hover(screen.getByRole("button", { name: "Why is this off?" }));
		return screen.findByText(cardBody);
	},
);

export const DropdownMenuOverlay = reflowStory(
	() => (
		<Page>
			<DropdownMenu>
				<DropdownMenuTrigger>Open menu</DropdownMenuTrigger>
				{/* Content-sized: one label carrying a name is enough to reach the viewport width. */}
				<DropdownMenuContent align="end">
					<DropdownMenuItem>{menuItemLabel}</DropdownMenuItem>
				</DropdownMenuContent>
			</DropdownMenu>
		</Page>
	),
	async () => {
		await userEvent.click(screen.getByRole("button", { name: "Open menu" }));
		return screen.findByRole("menuitem", { name: menuItemLabel });
	},
);

export const SelectOverlay = reflowStory(
	() => (
		<Page>
			<Select
				items={[
					{ value: "propose", label: "Propose feedback for a reviewer" },
					{ value: "deliver", label: optionLabel },
				]}
				defaultValue="propose"
			>
				<SelectTrigger aria-label="Autonomy" className="w-full">
					<SelectValue />
				</SelectTrigger>
				<SelectContent aria-label="Autonomy">
					<SelectItem value="propose">Propose feedback for a reviewer</SelectItem>
					<SelectItem value="deliver">{optionLabel}</SelectItem>
				</SelectContent>
			</Select>
		</Page>
	),
	async () => {
		await userEvent.click(screen.getByRole("combobox", { name: "Autonomy" }));
		return screen.findByRole("option", { name: optionLabel });
	},
);

export const ComboboxOverlay = reflowStory(
	() => (
		<Page>
			<Combobox items={["Propose feedback for a reviewer", optionLabel]}>
				<ComboboxTrigger aria-label="Autonomy">
					<ComboboxValue />
				</ComboboxTrigger>
				{/* `min-w-72` is the widest the app asks for. */}
				<ComboboxContent align="start" className="min-w-72" aria-label="Autonomy modes">
					<ComboboxList aria-label="Autonomy modes">
						{(item: string) => (
							<ComboboxItem key={item} value={item}>
								{item}
							</ComboboxItem>
						)}
					</ComboboxList>
				</ComboboxContent>
			</Combobox>
		</Page>
	),
	async () => {
		await userEvent.click(screen.getByRole("combobox", { name: "Autonomy" }));
		return screen.findByRole("option", { name: optionLabel });
	},
);

/** The trade the fix makes: a viewport-positioned popup is not carried along by a scroll for free. */
export const FollowsTriggerOnPageScroll: Story = {
	parameters: { viewport: { defaultViewport: "mobile" } },
	render: () => (
		<div className="p-4">
			<div className="h-[150vh]" />
			<Popover>
				<PopoverTrigger>Open popover</PopoverTrigger>
				<PopoverContent className="w-80" aria-label="Review settings">
					{cardBody}
				</PopoverContent>
			</Popover>
			<div className="h-[150vh]" />
		</div>
	),
	play: async () => {
		const trigger = screen.getByRole("button", { name: "Open popover" });
		trigger.scrollIntoView({ block: "center" });
		await userEvent.click(trigger);
		const popup = await settledPopup();

		await expectOverlayFollowsTrigger(trigger, popup, () => window.scrollBy(0, 60));
	},
};

/** And inside a dialog, whose body is its own scroller and whose popup is itself `fixed`. */
export const FollowsTriggerInDialogBody: Story = {
	parameters: { viewport: { defaultViewport: "mobile" } },
	render: () => (
		<Dialog defaultOpen>
			<DialogContent>
				<DialogHeader>
					<DialogTitle>Review settings</DialogTitle>
				</DialogHeader>
				<DialogBody>
					<div className="h-96" />
					<Popover>
						<PopoverTrigger>Open popover</PopoverTrigger>
						<PopoverContent className="w-80" aria-label="Review settings">
							{cardBody}
						</PopoverContent>
					</Popover>
					<div className="h-96" />
				</DialogBody>
			</DialogContent>
		</Dialog>
	),
	play: async () => {
		const body = document.querySelector<HTMLElement>("[data-slot='dialog-body']");
		if (body == null) {
			throw new Error("The dialog rendered no body, so nothing here can scroll.");
		}
		const trigger = screen.getByRole("button", { name: "Open popover" });
		await userEvent.click(trigger);
		const popup = await settledPopup();

		await expectOverlayFollowsTrigger(trigger, popup, () => {
			body.scrollTop += 40;
		});
	},
};

/** The same trade inside a scroll container, which is how the sidebar and every long list behave. */
export const FollowsTriggerInScrollArea: Story = {
	parameters: { viewport: { defaultViewport: "mobile" } },
	render: () => (
		<ScrollArea className="h-64 w-full border">
			<div className="p-4">
				<div className="h-40" />
				<DropdownMenu>
					<DropdownMenuTrigger>Open menu</DropdownMenuTrigger>
					<DropdownMenuContent align="end">
						<DropdownMenuItem>{menuItemLabel}</DropdownMenuItem>
					</DropdownMenuContent>
				</DropdownMenu>
				<div className="h-96" />
			</div>
		</ScrollArea>
	),
	play: async ({ canvasElement }) => {
		const viewport = canvasElement.querySelector<HTMLElement>("[data-slot='scroll-area-viewport']");
		if (viewport == null) {
			throw new Error("The scroll area rendered no viewport, so nothing here can scroll.");
		}
		const trigger = screen.getByRole("button", { name: "Open menu" });
		await userEvent.click(trigger);
		const popup = await settledPopup();

		await expectOverlayFollowsTrigger(trigger, popup, () => {
			viewport.scrollTop += 40;
		});
	},
};

export const OversizedDropdownMenu: Story = {
	render: () => (
		<Page>
			<DropdownMenu>
				<DropdownMenuTrigger>Open wide menu</DropdownMenuTrigger>
				<DropdownMenuContent className="w-[200vw]" align="end">
					<DropdownMenuItem>View workspace settings</DropdownMenuItem>
				</DropdownMenuContent>
			</DropdownMenu>
		</Page>
	),
	play: async () => {
		await userEvent.click(screen.getByRole("button", { name: "Open wide menu" }));
		const popup = await settledPopup();
		const bounds = popup.getBoundingClientRect();
		await expect(bounds.left).toBeGreaterThanOrEqual(0);
		await expect(bounds.right).toBeLessThanOrEqual(window.innerWidth);
		await expectNoPageOverflow();
	},
};
