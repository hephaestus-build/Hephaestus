import type { IconComponent } from "@/components/icons/provider-icons";
import type { ProviderType } from "@/lib/provider/provider-terms";

/**
 * The provider colour roles activity wears: Primer's on GitHub, Pajamas' on GitLab, through the
 * `--color-provider-*` tokens that `[data-provider]` switches. Colour marks state — open, done,
 * closed, approved, changes requested — and goes on icons and bars, never on a count.
 */
export type ActivityTone =
	| "open"
	| "done"
	| "closed"
	| "success"
	| "danger"
	| "attention"
	| "accent"
	| "muted";

interface ToneDef {
	/** The icon's colour. */
	text: string;
	/** The same colour as a chart mark's `fill`: a theme token, which answers to `.dark` itself. */
	fill: string;
}

export const ACTIVITY_TONES = {
	open: {
		text: "text-provider-open-foreground",
		fill: "var(--color-provider-open-foreground)",
	},
	done: {
		text: "text-provider-done-foreground",
		fill: "var(--color-provider-done-foreground)",
	},
	closed: {
		text: "text-provider-closed-foreground",
		fill: "var(--color-provider-closed-foreground)",
	},
	success: {
		text: "text-provider-success-foreground",
		fill: "var(--color-provider-success-foreground)",
	},
	danger: {
		text: "text-provider-danger-foreground",
		fill: "var(--color-provider-danger-foreground)",
	},
	attention: {
		text: "text-provider-attention-foreground",
		fill: "var(--color-provider-attention-foreground)",
	},
	accent: {
		text: "text-provider-accent-foreground",
		fill: "var(--color-provider-accent-foreground)",
	},
	muted: {
		text: "text-provider-muted-foreground",
		fill: "var(--color-provider-muted-foreground)",
	},
} as const satisfies Record<ActivityTone, ToneDef>;

/** The icon each provider draws for one thing: an octicon on GitHub, a Pajamas icon on GitLab. */
export function providerIcon(
	github: IconComponent,
	gitlab: IconComponent,
): (provider: ProviderType) => IconComponent {
	return (provider) => (provider === "GITLAB" ? gitlab : github);
}

/**
 * The previous range's figures, shown while the range just chosen loads, in an `aria-busy` region
 * drained of its state colours, so a number is never read as the new range's before it is. Not
 * faded: faded text falls below the contrast the figures need to stay readable.
 */
export const STALE = "grayscale transition-[filter] motion-reduce:transition-none";

/**
 * The tone a kind's bars take. A neutral kind — a comment, a comment-only review — is grey on its
 * icon, beside the state colours of the kinds around it, but grey bars read as disabled, so its
 * bars take the accent: information, no state.
 */
export function markTone(tone: ActivityTone): ActivityTone {
	return tone === "muted" ? "accent" : tone;
}
