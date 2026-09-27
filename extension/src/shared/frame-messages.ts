import { z } from "zod";

/** The frame viewport stops growing here; its natural document scrolls beyond it. */
export const MAX_FRAME_HEIGHT = 4000;

/**
 * The collapsed report's height, the same in every state — loading, signed out, not followed, no
 * observations, twenty — so the page, which can read the frame's size, learns nothing from it. Only
 * the reader's own expansion changes it, which the frame keeps with the worker: nothing the page
 * passes to the frame can open it.
 */
export const REPORT_ROW_HEIGHT = 48;

/**
 * A list row's preview: one line that never wraps, filled as soon as the reader presses the row's
 * button, at least this tall and in every state the same height. The page can frame a preview itself
 * and measure it, so nothing the preview says may change its size; only the reader's font size does.
 */
export const LIST_STRIP_HEIGHT = 32;

/**
 * The only things the inline frame and the page's content script say to each other, over
 * `postMessage`. None of it is private: the page can already see the frame's size, and a theme is a
 * colour scheme. The page side checks
 * `event.origin` — only the extension's own frame can post from it — and `open`, the opaque token of
 * the frame it mounted, so a message from an earlier frame cannot resize or mark ready the current
 * one. The token authorises nothing.
 */
export const openTokenSchema = z.string().regex(/^[A-Za-z0-9-]{8,64}$/u);

export const frameToPageSchema = z.discriminatedUnion("type", [
	z.object({ type: z.literal("hephaestus:ready"), open: openTokenSchema }),
	z.object({
		type: z.literal("hephaestus:size"),
		open: openTokenSchema,
		height: z.number().int().min(0).max(MAX_FRAME_HEIGHT),
	}),
]);

export type FrameToPage = z.infer<typeof frameToPageSchema>;

/**
 * The palette the inline frame paints, from a closed list the frame has reviewed CSS for; never a
 * provider's own CSS, tokens or theme names. The page's content script resolves which one its page
 * shows (GitHub's selected light or dark theme, dimmed and high contrast included; GitLab's light or
 * dark) and sends it again whenever the page or the system changes it. `system` only when nothing
 * says otherwise: the frame then follows the system itself.
 */
export const FRAME_PALETTES = [
	"light",
	"light_high_contrast",
	"dark",
	"dark_dimmed",
	"dark_high_contrast",
	"dark_dimmed_high_contrast",
] as const;

export type FramePalette = (typeof FRAME_PALETTES)[number];

export const frameThemeSchema = z.enum([...FRAME_PALETTES, "system"]);

export type FrameTheme = z.infer<typeof frameThemeSchema>;

export function isDarkPalette(palette: FramePalette): boolean {
	return palette.startsWith("dark");
}

export const pageToFrameSchema = z.object({
	type: z.literal("hephaestus:theme"),
	theme: frameThemeSchema,
});

export type PageToFrame = z.infer<typeof pageToFrameSchema>;

/** Which provider's page the frame sits in, so it can take that page's look. */
export const frameProviderSchema = z.enum(["github", "gitlab"]);

export type FrameProvider = z.infer<typeof frameProviderSchema>;

/**
 * The query parameters the page opens the frame with: its token, and the look to paint first so the
 * frame does not flash another theme. Nothing here is private, and none of it grants anything.
 */
export const OPEN_PARAMETER = "open";
/**
 * For a list row's preview: the row's canonical work address. Only a selector — the worker accepts it
 * solely against the tab's actual list — so it decides nothing the frame may show.
 */
export const WORK_PARAMETER = "work";
export const THEME_PARAMETER = "theme";
export const PROVIDER_PARAMETER = "provider";
