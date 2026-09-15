import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Progress, ProgressIndicator, ProgressTrack } from "@/components/ui/progress";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";

/**
 * The axes the design system added to the registry primitives, each pinned on what it does rather
 * than on the class that does it — so the assertions still hold if the implementation moves.
 */
const meta = {
	title: "Tests/Design system axes",
	parameters: { layout: "padded", controls: { disable: true } },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

/** Figures share one advance width, so a column of numbers cannot jog as its values change. */
export const NumericCellsAlign: Story = {
	render: () => (
		<Table>
			<TableBody>
				<TableRow>
					<TableCell numeric>1,204,118</TableCell>
				</TableRow>
				<TableRow>
					<TableCell>Ad hoc</TableCell>
				</TableRow>
			</TableBody>
		</Table>
	),
	play: async ({ canvas }) => {
		// The resolved property, not the rendered width: the suite's fallback face carries no tabular
		// figures, so digits measure the same either way here and only the style tells them apart.
		const numeric = canvas.getByText("1,204,118");
		const plain = canvas.getByText("Ad hoc");
		await expect(getComputedStyle(numeric).fontVariantNumeric).toBe("tabular-nums");
		await expect(getComputedStyle(plain).fontVariantNumeric).toBe("normal");
	},
};

/** The outlined empty state draws an edge. Upstream's dashed style had no width to draw one with. */
export const OutlinedEmptyDrawsItsEdge: Story = {
	render: () => (
		<div className="flex flex-col gap-4">
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyTitle>No practices yet</EmptyTitle>
					<EmptyDescription>Connect a repository to start reviewing work.</EmptyDescription>
				</EmptyHeader>
			</Empty>
			<Empty>
				<EmptyHeader>
					<EmptyTitle>Nothing here yet</EmptyTitle>
				</EmptyHeader>
			</Empty>
		</div>
	),
	play: async ({ canvas }) => {
		const panelOf = (title: string) => {
			const panel = canvas.getByText(title).closest('[data-slot="empty"]');
			if (!panel) throw new Error(`No empty panel around ${title}`);
			return panel;
		};
		const outlined = getComputedStyle(panelOf("Connect a repository to start reviewing work."));
		await expect(outlined.borderStyle).toBe("dashed");
		await expect(Number.parseFloat(outlined.borderTopWidth)).toBeGreaterThan(0);
		const plain = getComputedStyle(panelOf("Nothing here yet"));
		await expect(Number.parseFloat(plain.borderTopWidth)).toBe(0);
	},
};

/** A pill is round-ended whatever the size chose, because `shape` is applied after `size`. */
export const PillOverridesTheSizeRadius: Story = {
	render: () => (
		<div className="flex items-center gap-2">
			<Button size="sm">Default radius</Button>
			<Button size="sm" shape="pill">
				Pill
			</Button>
		</div>
	),
	play: async ({ canvas }) => {
		const box = canvas.getByRole("button", { name: "Pill" });
		const { borderTopLeftRadius } = getComputedStyle(box);
		await expect(Number.parseFloat(borderTopLeftRadius)).toBeGreaterThanOrEqual(
			box.getBoundingClientRect().height / 2,
		);
	},
};

/** A toggle that is on looks on: `aria-pressed` paints, the way `aria-expanded` already did. */
export const PressedTogglesLookSelected: Story = {
	render: () => (
		<div className="flex items-center gap-2">
			<Button variant="outline" aria-pressed={false}>
				Off
			</Button>
			<Button variant="outline" aria-pressed>
				On
			</Button>
		</div>
	),
	play: async ({ canvas }) => {
		const off = getComputedStyle(canvas.getByRole("button", { name: "Off" })).backgroundColor;
		const on = getComputedStyle(canvas.getByRole("button", { name: "On" })).backgroundColor;
		await expect(on).not.toBe(off);
	},
};

/** The quiet ghost rests below body contrast and comes up to it, so it never competes. */
export const QuietRestsBelowFullContrast: Story = {
	render: () => (
		<div className="flex items-center gap-2 text-foreground">
			<Button variant="ghost">Ghost</Button>
			<Button variant="quiet">Quiet</Button>
		</div>
	),
	play: async ({ canvas }) => {
		const ghost = getComputedStyle(canvas.getByRole("button", { name: "Ghost" })).color;
		const quiet = getComputedStyle(canvas.getByRole("button", { name: "Quiet" })).color;
		await expect(quiet).not.toBe(ghost);
	},
};

/** A dashed Card draws its edge. The edge is a ring, so a caller's `border-dashed` never did. */
export const DashedCardDrawsItsEdge: Story = {
	render: () => (
		<Card variant="dashed">
			<CardContent>Drop a repository here</CardContent>
		</Card>
	),
	play: async ({ canvas }) => {
		const card = canvas.getByText("Drop a repository here").closest('[data-slot="card"]');
		if (!card) throw new Error("No card");
		await expect(getComputedStyle(card).borderStyle).toBe("dashed");
		await expect(Number.parseFloat(getComputedStyle(card).borderTopWidth)).toBeGreaterThan(0);
	},
};

/** A banded header sits flush to the card's top edge: the card gives up its own top padding. */
export const BandedHeaderIsFlush: Story = {
	render: () => (
		<Card>
			<CardHeader band>
				<CardTitle>Reviewing</CardTitle>
			</CardHeader>
			<CardContent>Body</CardContent>
		</Card>
	),
	play: async ({ canvas }) => {
		const header = canvas.getByText("Reviewing").closest('[data-slot="card-header"]');
		const card = header?.closest('[data-slot="card"]');
		if (!header || !card) throw new Error("No card");
		await expect(header.getBoundingClientRect().top).toBeCloseTo(
			card.getBoundingClientRect().top,
			0,
		);
	},
};

/** Composing a track renders that track and no default one behind it. */
export const ComposedProgressHasOneTrack: Story = {
	render: () => (
		<Progress value={40} aria-label="Budget used">
			<ProgressTrack>
				<ProgressIndicator className="bg-warning" />
			</ProgressTrack>
		</Progress>
	),
	play: async ({ canvas }) => {
		const meter = canvas.getByRole("progressbar", { name: "Budget used" });
		await expect(meter.querySelectorAll('[data-slot="progress-track"]')).toHaveLength(1);
	},
};

/** A bordered table's edge belongs to the scroll container, so it rounds with the table. */
export const BorderedTableHasOneEdge: Story = {
	render: () => (
		<Table bordered aria-label="Spend">
			<TableBody>
				<TableRow variant="highlighted">
					<TableCell>You</TableCell>
					<TableCell numeric>1,204</TableCell>
				</TableRow>
				<TableRow>
					<TableCell>Team</TableCell>
					<TableCell numeric>980</TableCell>
				</TableRow>
			</TableBody>
		</Table>
	),
	play: async ({ canvas }) => {
		const container = canvas.getByRole("table", { name: "Spend" }).parentElement;
		if (!container) throw new Error("No container");
		await expect(Number.parseFloat(getComputedStyle(container).borderTopWidth)).toBeGreaterThan(0);
		const own = canvas.getByText("You").closest("tr");
		const other = canvas.getByText("Team").closest("tr");
		if (!own || !other) throw new Error("No rows");
		await expect(getComputedStyle(own).backgroundColor).not.toBe(
			getComputedStyle(other).backgroundColor,
		);
	},
};

/** A bare textarea has no edge of its own; the frame around it draws one. */
export const BareTextareaHasNoEdge: Story = {
	render: () => (
		<div className="rounded-xl border p-3">
			<Textarea variant="bare" aria-label="Message" defaultValue="Hello" />
		</div>
	),
	play: async ({ canvas }) => {
		const field = canvas.getByRole("textbox", { name: "Message" });
		await expect(Number.parseFloat(getComputedStyle(field).borderTopWidth)).toBe(0);
	},
};
