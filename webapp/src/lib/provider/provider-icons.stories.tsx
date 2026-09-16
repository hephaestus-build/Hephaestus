import type { Meta, StoryObj } from "@storybook/react";

import { GitLabMergeRequestIcon } from "./gitlab-icons";
import { getPullRequestStateIcon, type PullRequestState } from "./provider-icons";
import { getProviderSlug, getProviderTerms, type ProviderType } from "./provider-terms";

/**
 * Visual reference for all provider-specific pull request / merge request icons.
 * Shows every state (open, draft, merged, closed) for each provider
 * with their correct icons and color tokens.
 */
const meta = {
	tags: ["autodocs"],
	parameters: {
		layout: "centered",
		docs: {
			description: {
				component:
					"Gallery of all pull request / merge request state icons. Each provider uses its own native icons with provider-aware color tokens.",
			},
		},
	},
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

const STATES = [
	{ state: "OPEN", isDraft: false, label: "Open" },
	{ state: "OPEN", isDraft: true, label: "Draft" },
	{ state: "MERGED", isDraft: false, label: "Merged" },
	{ state: "CLOSED", isDraft: false, label: "Closed" },
] as const satisfies readonly { state: PullRequestState; isDraft: boolean; label: string }[];

function IconRow({ provider, size = 20 }: { provider: ProviderType; size?: number }) {
	const terms = getProviderTerms(provider);
	return (
		<div className="flex flex-col gap-3">
			<h3 className="text-sm font-semibold">
				{terms.displayName} — {terms.pullRequest}
			</h3>
			<div className="flex gap-6">
				{STATES.map(({ state, isDraft, label }) => {
					const { icon: Icon, colorClass } = getPullRequestStateIcon(provider, state, isDraft);
					return (
						<div key={label} className="flex flex-col items-center gap-2">
							<div
								className={`flex items-center justify-center rounded-md border p-3 ${colorClass}`}
							>
								<Icon size={size} />
							</div>
							<span className="text-xs text-muted-foreground">{label}</span>
						</div>
					);
				})}
			</div>
		</div>
	);
}

/**
 * GitHub pull request icons in all four states: open, draft, merged, closed.
 * Uses `@primer/octicons-react` icons with GitHub color tokens.
 */
export const GitHub: Story = {
	render: () => <IconRow provider="GITHUB" />,
};

/**
 * GitLab merge request icons in all four states: open, draft, merged, closed.
 * Uses custom GitLab SVG wrappers with Pajamas color tokens.
 */
export const GitLab: Story = {
	render: () => (
		<div data-provider={getProviderSlug("GITLAB")}>
			<IconRow provider="GITLAB" />
		</div>
	),
};

/**
 * Side-by-side comparison of icons at the default size.
 * Highlights the visual differences between providers — different icons and
 * different color palettes (e.g. GitHub purple vs GitLab blue for merged).
 */
export const Comparison: Story = {
	render: () => (
		<div className="flex flex-col gap-8">
			<IconRow provider="GITHUB" />
			<div data-provider={getProviderSlug("GITLAB")}>
				<IconRow provider="GITLAB" />
			</div>
		</div>
	),
	parameters: {
		docs: {
			description: {
				story:
					"Side-by-side comparison showing how the same states render differently per provider.",
			},
		},
	},
};

interface ColorToken {
	token: string;
	label: string;
	bgClass: string;
	fgClass: string;
}

const COLOR_TOKENS: readonly ColorToken[] = [
	{
		token: "open",
		label: "Open",
		bgClass: "bg-provider-open",
		fgClass: "text-provider-open-foreground",
	},
	{
		token: "done",
		label: "Merged/Done",
		bgClass: "bg-provider-done",
		fgClass: "text-provider-done-foreground",
	},
	{
		token: "closed",
		label: "Closed",
		bgClass: "bg-provider-closed",
		fgClass: "text-provider-closed-foreground",
	},
	{
		token: "danger",
		label: "Danger",
		bgClass: "bg-provider-danger",
		fgClass: "text-provider-danger-foreground",
	},
	{
		token: "success",
		label: "Success",
		bgClass: "bg-provider-success",
		fgClass: "text-provider-success-foreground",
	},
	{
		token: "muted",
		label: "Muted/Draft",
		bgClass: "bg-provider-muted",
		fgClass: "text-provider-muted-foreground",
	},
];

function ColorSwatches({ provider }: { provider: ProviderType }) {
	const { displayName } = getProviderTerms(provider);
	return (
		<div className="flex flex-col gap-3">
			<h3 className="text-sm font-semibold">{displayName}</h3>
			<div className="flex gap-4">
				{COLOR_TOKENS.map(({ token, label, bgClass, fgClass }) => (
					<div key={token} className="flex flex-col items-center gap-2">
						<div className={`h-12 w-12 rounded-md ${bgClass}`} />
						<div className={`text-sm font-semibold ${fgClass}`}>Aa</div>
						<span className="text-xs text-muted-foreground">{label}</span>
					</div>
				))}
			</div>
		</div>
	);
}

/**
 * Provider color tokens side by side. Shows the background and foreground colors
 * for each semantic token (open, merged, closed, danger, success, muted).
 */
export const ColorTokens: Story = {
	render: () => (
		<div className="flex flex-col gap-8">
			<ColorSwatches provider="GITHUB" />
			<div data-provider={getProviderSlug("GITLAB")}>
				<ColorSwatches provider="GITLAB" />
			</div>
		</div>
	),
	parameters: {
		docs: {
			description: {
				story:
					"Provider-aware CSS custom properties side by side. The most visible difference is merged/done: GitHub purple vs GitLab blue.",
			},
		},
	},
};

/**
 * Icons rendered at multiple sizes (12, 16, 20, 24, 32) for both providers.
 * Useful for verifying icon clarity at different scales.
 */
export const Sizes: Story = {
	render: () => {
		const sizes = [12, 16, 20, 24, 32];
		return (
			<div className="flex flex-col gap-8">
				{(["GITHUB", "GITLAB"] as const).map((provider) => {
					const { displayName } = getProviderTerms(provider);
					return (
						<div
							key={provider}
							data-provider={provider === "GITLAB" ? getProviderSlug(provider) : undefined}
						>
							<h3 className="mb-3 text-sm font-semibold">{displayName}</h3>
							<div className="flex items-end gap-6">
								{sizes.map((size) => {
									const { icon: Icon, colorClass } = getPullRequestStateIcon(provider, "OPEN");
									return (
										<div key={size} className="flex flex-col items-center gap-2">
											<div className={colorClass}>
												<Icon size={size} />
											</div>
											<span className="text-xs text-muted-foreground">{size}px</span>
										</div>
									);
								})}
							</div>
						</div>
					);
				})}
			</div>
		);
	},
	parameters: {
		docs: {
			description: {
				story: "Icons at different sizes to verify rendering quality across scales.",
			},
		},
	},
};

/**
 * Demonstrates the accessibility behavior of GitLab icons.
 * Decorative icons (no label) get `aria-hidden="true"`.
 * Labelled icons get `role="img"` and the provided `aria-label`.
 */
export const Accessibility: Story = {
	render: () => (
		<div className="flex flex-col gap-6" data-provider={getProviderSlug("GITLAB")}>
			<div className="flex items-center gap-3">
				<GitLabMergeRequestIcon size={20} className="text-provider-open-foreground" />
				<span className="text-sm text-muted-foreground">
					Decorative — <code>aria-hidden=&quot;true&quot;</code>, no role
				</span>
			</div>
			<div className="flex items-center gap-3">
				<GitLabMergeRequestIcon
					size={20}
					className="text-provider-open-foreground"
					aria-label="Open merge request"
				/>
				<span className="text-sm text-muted-foreground">
					Labelled — <code>role=&quot;img&quot;</code>, <code>aria-label</code> set
				</span>
			</div>
			<div className="flex items-center gap-3">
				<button
					type="button"
					className="inline-flex items-center gap-1.5 rounded border px-2 py-1 text-sm"
				>
					<GitLabMergeRequestIcon size={16} className="text-provider-open-foreground" />
					Open
				</button>
				<span className="text-sm text-muted-foreground">
					Inside a labelled button — icon is decorative, button provides the label
				</span>
			</div>
		</div>
	),
	parameters: {
		docs: {
			description: {
				story:
					'GitLab icons support conditional accessibility: decorative icons get aria-hidden, labelled icons get role="img". When used inside buttons, the icon should remain decorative and the button provides the accessible name.',
			},
		},
	},
};
