import { LinkIcon, type LucideIcon } from "lucide-react";

import {
	type BrandIcon,
	GitHubIcon,
	GitLabIcon,
	OutlineIcon,
	SlackIcon,
} from "@/components/icons/brand";
import type { WorkProvider } from "@/lib/artifact-kinds";

export const PROVIDER_ICONS = {
	GITHUB: GitHubIcon,
	GITLAB: GitLabIcon,
	SLACK: SlackIcon,
	OUTLINE: OutlineIcon,
} satisfies Record<WorkProvider, BrandIcon>;

function hasProviderIcon(providerType: string): providerType is WorkProvider {
	return Object.hasOwn(PROVIDER_ICONS, providerType);
}

export function getProviderIcon(providerType?: string): LucideIcon | BrandIcon {
	const key = providerType?.toUpperCase();
	return key !== undefined && hasProviderIcon(key) ? PROVIDER_ICONS[key] : LinkIcon;
}
