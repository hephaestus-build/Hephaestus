import { isKnownValue } from "@/components/common/status-def";
import { PROVIDER_ICONS } from "@/components/icons/integration-provider-icons";
import { type ItemKindIcon, itemKindIcon, type ItemProvider } from "@/lib/item-kinds";

/**
 * The provider's mark where the caller has one — a run records its provider — falling back to the
 * kind's. Beside the work's label (`#1423`, `!88`) a kind glyph would say the same thing twice and
 * leave the reader no way to tell a GitHub request from a GitLab one.
 */
export function reviewedWorkIcon(kind: string | undefined, provider?: ItemProvider): ItemKindIcon {
	// A provider that this build does not know has no mark, so the kind's glyph stands in.
	return provider !== undefined && isKnownValue(PROVIDER_ICONS, provider)
		? PROVIDER_ICONS[provider]
		: itemKindIcon(kind);
}
