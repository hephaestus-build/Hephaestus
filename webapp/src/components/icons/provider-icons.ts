import {
	GitMergeIcon,
	GitPullRequestClosedIcon,
	GitPullRequestDraftIcon,
	GitPullRequestIcon,
	IssueClosedIcon,
	IssueOpenedIcon,
} from "@primer/octicons-react";
import type { ComponentType } from "react";

import type { ProviderType } from "@/lib/provider/provider-terms";
import {
	GitLabIssueClosedIcon,
	GitLabIssueOpenIcon,
	GitLabMergeIcon,
	GitLabMergeRequestClosedIcon,
	GitLabMergeRequestDraftIcon,
	GitLabMergeRequestIcon,
} from "./gitlab-icons";

/**
 * Minimal icon component interface satisfied by both octicons and GitLab SVG wrappers. Both hide
 * themselves from assistive technology unless given an `aria-label`, which makes them an image.
 */
export type IconComponent = ComponentType<{
	size?: number;
	className?: string;
	"aria-label"?: string;
}>;

/** Pull request / merge request lifecycle state. */
export type PullRequestState = "OPEN" | "CLOSED" | "MERGED";

export interface PullRequestStateIconResult {
	icon: IconComponent;
	colorClass: string;
}

type IconState = "open" | "draft" | "merged" | "closed";

type IconMap = Record<IconState, PullRequestStateIconResult>;

const PROVIDER_ICONS: Record<ProviderType, IconMap> = {
	GITHUB: {
		open: { icon: GitPullRequestIcon, colorClass: "text-provider-open-foreground" },
		draft: { icon: GitPullRequestDraftIcon, colorClass: "text-provider-muted-foreground" },
		merged: { icon: GitMergeIcon, colorClass: "text-provider-done-foreground" },
		closed: { icon: GitPullRequestClosedIcon, colorClass: "text-provider-closed-foreground" },
	},
	GITLAB: {
		open: { icon: GitLabMergeRequestIcon, colorClass: "text-provider-open-foreground" },
		draft: { icon: GitLabMergeRequestDraftIcon, colorClass: "text-provider-muted-foreground" },
		merged: { icon: GitLabMergeIcon, colorClass: "text-provider-done-foreground" },
		closed: { icon: GitLabMergeRequestClosedIcon, colorClass: "text-provider-closed-foreground" },
	},
};

/**
 * Returns the correct icon component and Tailwind color class for a
 * pull request / merge request state, based on the provider type.
 */
export function getPullRequestStateIcon(
	provider: ProviderType,
	state: PullRequestState,
	isDraft?: boolean,
): PullRequestStateIconResult {
	const icons = PROVIDER_ICONS[provider];

	if (state === "OPEN" && isDraft === true) {
		return icons.draft;
	}
	if (state === "MERGED") {
		return icons.merged;
	}
	if (state === "CLOSED") {
		return icons.closed;
	}
	return icons.open;
}

const ISSUE_ICONS: Record<ProviderType, { open: IconComponent; closed: IconComponent }> = {
	GITHUB: { open: IssueOpenedIcon, closed: IssueClosedIcon },
	GITLAB: { open: GitLabIssueOpenIcon, closed: GitLabIssueClosedIcon },
};

/**
 * An issue's state in its provider's icons and colours. The state enum is the one pull requests
 * use, and an issue is never MERGED.
 */
export function getIssueStateIcon(
	provider: ProviderType,
	state: PullRequestState,
): PullRequestStateIconResult {
	return state === "OPEN"
		? { icon: ISSUE_ICONS[provider].open, colorClass: "text-provider-open-foreground" }
		: { icon: ISSUE_ICONS[provider].closed, colorClass: "text-provider-done-foreground" };
}
