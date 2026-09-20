import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty";
import { Progress, ProgressIndicator, ProgressTrack } from "@/components/ui/progress";
import {
	SidebarMenu,
	SidebarMenuButton,
	SidebarMenuItem,
	SidebarProvider,
} from "@/components/ui/sidebar";
import { Table, TableBody, TableCell, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";

/**
 * The `⚠️ Diverges` axes under `ui/` whose loss no page would surface — a dropped variant still
 * type-checks once the registry restores the prop, and the page renders the upstream look without a
 * failing test. Each story pins the rendered result so a re-vendor fails here instead. An axis a
 * page already asserts on, or whose loss is a type error, is not repeated here.
 */
const meta = {
	parameters: { layout: "padded", controls: { disable: true } },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

const style = (element: Element) => getComputedStyle(element);
const px = (value: string) => Number.parseFloat(value);

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
		// Asserted on the resolved property: whether `tabular-nums` changes a digit's advance depends
		// on which face the runner falls back to, so a width comparison would pass or fail on the font.
		await expect(style(canvas.getByText("1,204,118")).fontVariantNumeric).toBe("tabular-nums");
		await expect(style(canvas.getByText("Ad hoc")).fontVariantNumeric).toBe("normal");
	},
};

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
			if (!panel) {
				throw new Error(`No empty panel around ${title}`);
			}
			return style(panel);
		};
		const outlined = panelOf("Connect a repository to start reviewing work.");
		await expect(outlined.borderStyle).toBe("dashed");
		await expect(px(outlined.borderTopWidth)).toBeGreaterThan(0);
		await expect(px(panelOf("Nothing here yet").borderTopWidth)).toBe(0);
	},
};

export const PillOverridesTheSizeRadius: Story = {
	render: () => (
		<Button size="sm" shape="pill">
			Pill
		</Button>
	),
	play: async ({ canvas }) => {
		const pill = canvas.getByRole("button", { name: "Pill" });
		await expect(px(style(pill).borderTopLeftRadius)).toBeGreaterThanOrEqual(
			pill.getBoundingClientRect().height / 2,
		);
	},
};

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
		const off = style(canvas.getByRole("button", { name: "Off" })).backgroundColor;
		const on = style(canvas.getByRole("button", { name: "On" })).backgroundColor;
		await expect(on).not.toBe(off);
	},
};

export const QuietRestsBelowFullContrast: Story = {
	render: () => (
		<div className="flex items-center gap-2 text-foreground">
			<Button variant="ghost">Ghost</Button>
			<Button variant="quiet">Quiet</Button>
		</div>
	),
	play: async ({ canvas }) => {
		const ghost = canvas.getByRole("button", { name: "Ghost" });
		const quiet = canvas.getByRole("button", { name: "Quiet" });
		await expect(style(quiet).color).not.toBe(style(ghost).color);
	},
};

export const InlineButtonInheritsSizeNotColour: Story = {
	render: () => (
		<p className="text-lg text-muted-foreground">
			No review activity yet.{" "}
			<Button size="inline" variant="link">
				View repositories
			</Button>
		</p>
	),
	play: async ({ canvas }) => {
		const button = canvas.getByRole("button", { name: "View repositories" });
		const prose = style(canvas.getByText("No review activity yet."));
		await expect(style(button).fontSize).toBe(prose.fontSize);
		await expect(style(button).color).not.toBe(prose.color);
	},
};

export const OutlinedTonesShareOneSurface: Story = {
	render: () => (
		<div className="flex items-center gap-2">
			<Button variant="warning-outline">Enable writes</Button>
			<Button variant="destructive-outline">Stop impersonating</Button>
		</div>
	),
	play: async ({ canvas }) => {
		const warning = style(canvas.getByRole("button", { name: "Enable writes" }));
		const destructive = style(canvas.getByRole("button", { name: "Stop impersonating" }));
		await expect(warning.backgroundColor).toBe(destructive.backgroundColor);
		await expect(warning.borderTopWidth).toBe(destructive.borderTopWidth);
	},
};

export const BadgeSizesStep: Story = {
	render: () => (
		<div className="flex items-center gap-2">
			<Badge size="xs">12</Badge>
			<Badge>Open</Badge>
			<Badge size="lg">Works with GitHub and GitLab</Badge>
		</div>
	),
	play: async ({ canvas }) => {
		const height = (text: string) => canvas.getByText(text).getBoundingClientRect().height;
		await expect(height("12")).toBeLessThan(height("Open"));
		await expect(height("Open")).toBeLessThan(height("Works with GitHub and GitLab"));
	},
};

export const DashedCardDrawsItsEdge: Story = {
	render: () => (
		<Card variant="dashed">
			<CardContent>Drop a repository here</CardContent>
		</Card>
	),
	play: async ({ canvas }) => {
		const card = canvas.getByText("Drop a repository here").closest('[data-slot="card"]');
		if (!card) {
			throw new Error("No card");
		}
		await expect(style(card).borderStyle).toBe("dashed");
		await expect(px(style(card).borderTopWidth)).toBeGreaterThan(0);
	},
};

export const FlushCardDropsItsPadding: Story = {
	render: () => (
		<Card flush>
			<CardContent>Rows</CardContent>
		</Card>
	),
	play: async ({ canvas }) => {
		const card = canvas.getByText("Rows").closest('[data-slot="card"]');
		if (!card) {
			throw new Error("No card");
		}
		await expect(style(card).paddingTop).toBe("0px");
	},
};

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
		if (!header || !card) {
			throw new Error("No card");
		}
		await expect(header.getBoundingClientRect().top).toBeCloseTo(
			card.getBoundingClientRect().top,
			0,
		);
	},
};

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

export const BorderedTableWithOwnRow: Story = {
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
		// The edge is on the scroll container, not the <table>.
		const container = canvas.getByRole("table", { name: "Spend" }).parentElement;
		if (!container) {
			throw new Error("No container");
		}
		await expect(px(style(container).borderTopWidth)).toBeGreaterThan(0);
		const own = canvas.getByText("You").closest("tr");
		const other = canvas.getByText("Team").closest("tr");
		if (!own || !other) {
			throw new Error("No rows");
		}
		await expect(style(own).backgroundColor).not.toBe(style(other).backgroundColor);
	},
};

export const BareTextareaHasNoEdgeOrRing: Story = {
	render: () => (
		<div className="rounded-xl border p-3">
			<Textarea variant="bare" aria-label="Message" defaultValue="Hello" />
		</div>
	),
	play: async ({ canvas, userEvent }) => {
		const field = canvas.getByRole("textbox", { name: "Message" });
		await expect(px(style(field).borderTopWidth)).toBe(0);
		await userEvent.tab();
		await expect(field).toHaveFocus();
		// A zero-width ring still serialises as a shadow layer; what matters is that no layer has extent.
		const extents = style(field).boxShadow.match(/-?\d*\.?\d+px/gu) ?? [];
		await expect(extents.every((length) => px(length) === 0)).toBe(true);
	},
};

export const OutlineMenuButtonRingsOnFocus: Story = {
	render: () => (
		<SidebarProvider className="min-h-0 w-64">
			<SidebarMenu>
				<SidebarMenuItem>
					<SidebarMenuButton variant="outline">Workspace overview</SidebarMenuButton>
				</SidebarMenuItem>
			</SidebarMenu>
		</SidebarProvider>
	),
	play: async ({ canvas, userEvent }) => {
		const button = canvas.getByRole("button", { name: "Workspace overview" });
		// Tailwind composes a ring from five shadow slots, and a slot not in use serialises as a
		// zero-extent layer, so the layers that paint are the ones with any length above 0px. Layers
		// and the channels inside an `rgb(…)` are both comma-separated.
		const paintedLayers = (shadow: string) =>
			shadow
				.split(/,(?![^(]*\))/u)
				.filter((layer) =>
					(layer.match(/-?\d*\.?\d+px/gu) ?? []).some((length) => px(length) !== 0),
				);
		const rest = style(button).boxShadow;
		await expect(paintedLayers(rest)).toHaveLength(1);
		await userEvent.tab();
		await expect(button).toHaveFocus();
		const focused = style(button).boxShadow;
		await expect(focused).not.toBe(rest);
		await expect(paintedLayers(focused)).toHaveLength(1);
	},
};
