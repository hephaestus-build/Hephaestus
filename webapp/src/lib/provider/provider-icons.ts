import {
	GitMergeIcon,
	GitPullRequestClosedIcon,
	GitPullRequestDraftIcon,
	GitPullRequestIcon,
} from "@primer/octicons-react";
import type { ComponentType } from "react";

import {
	GitLabMergeIcon,
	GitLabMergeRequestClosedIcon,
	GitLabMergeRequestDraftIcon,
	GitLabMergeRequestIcon,
} from "./gitlab-icons";
import type { ProviderType } from "./provider-terms";

/** Minimal icon component interface satisfied by both octicons and GitLab SVG wrappers. */
export type IconComponent = ComponentType<{ size?: number; className?: string }>;

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
