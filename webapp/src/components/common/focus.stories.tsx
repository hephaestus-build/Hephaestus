import type { Meta, StoryObj } from "@storybook/react-vite";
import { expect } from "storybook/test";

import { cn } from "cn";

/**
 * The solid focus line on every surface a control sits on: the ring colour that a `Button` draws as
 * its edge and `FOCUS_RING` as its outline. The half-transparent halo outside it only adds to that
 * line. WCAG 2.2 SC 1.4.11 asks 3:1 between the indicator and what is next to it, in both themes.
 */
const meta = {
	parameters: { layout: "padded", controls: { disable: true } },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

const SURFACES = [
	["Page", "bg-background"],
	["Card", "bg-card"],
	["Sidebar", "bg-sidebar"],
	["Muted", "bg-muted"],
] as const;

/** One pixel of a computed colour in sRGB: the canvas resolves `oklch(…)` as the page paints it. */
function srgb(color: string): [number, number, number] {
	const context = document.createElement("canvas").getContext("2d", { willReadFrequently: true });
	if (context === null) {
		throw new Error("No 2D canvas");
	}
	context.fillStyle = color;
	context.fillRect(0, 0, 1, 1);
	const [r = 0, g = 0, b = 0] = context.getImageData(0, 0, 1, 1).data;
	return [r, g, b];
}

/** Relative luminance, WCAG 2.2 § Relative luminance. */
function luminance(color: string): number {
	const [r, g, b] = srgb(color).map((channel) => {
		const c = channel / 255;
		return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
	});
	return 0.2126 * (r ?? 0) + 0.7152 * (g ?? 0) + 0.0722 * (b ?? 0);
}

const contrast = (a: string, b: string) => {
	const [high, low] = [luminance(a), luminance(b)].sort((x, y) => y - x);
	return ((high ?? 0) + 0.05) / ((low ?? 0) + 0.05);
};

export const FocusLineOnEverySurface: Story = {
	render: () => (
		<div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
			{SURFACES.map(([name, surface]) => (
				<div key={name} className={cn("rounded-lg border p-4", surface)}>
					<div
						role="img"
						aria-label={`Focus line on ${name}`}
						className="h-8 rounded-md border-2 border-ring"
					/>
				</div>
			))}
		</div>
	),
	play: async ({ canvas }) => {
		for (const [name] of SURFACES) {
			const ring = canvas.getByRole("img", { name: `Focus line on ${name}` });
			const surface = ring.parentElement;
			if (surface === null) {
				throw new Error(`No surface for ${name}`);
			}
			const ratio = contrast(
				getComputedStyle(ring).borderTopColor,
				getComputedStyle(surface).backgroundColor,
			);
			await expect(ratio, `${name}: ${ratio.toFixed(2)}:1`).toBeGreaterThanOrEqual(3);
		}
	},
};

export const FocusLineOnEverySurfaceInDarkMode: Story = {
	...FocusLineOnEverySurface,
	globals: { theme: "dark" },
};
