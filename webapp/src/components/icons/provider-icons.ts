import { LinkIcon, type LucideIcon } from "lucide-react";

import {
	type BrandIcon,
	GithubIcon,
	GitlabIcon,
	OutlineIcon,
	SlackIcon,
} from "@/components/icons/brand";

/** Both types are named because `BrandIcon` is a plain component and `LucideIcon` is not. */
export const PROVIDER_ICONS: Record<string, LucideIcon | BrandIcon> = {
	GITHUB: GithubIcon,
	GITLAB: GitlabIcon,
	SLACK: SlackIcon,
	OUTLINE: OutlineIcon,
};

/**
 * Resolve a brand icon from a provider type (e.g. "GITHUB", "GITLAB"). Falls back
 * to a generic link icon for unknown providers so new IdPs render gracefully.
 */
export function getProviderIcon(providerType?: string): LucideIcon | BrandIcon {
	if (!providerType) return LinkIcon;
	return PROVIDER_ICONS[providerType.toUpperCase()] ?? LinkIcon;
}
