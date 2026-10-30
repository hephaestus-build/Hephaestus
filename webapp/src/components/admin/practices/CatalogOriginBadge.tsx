import type { CatalogOrigin } from "@/api/types.gen";
import { Badge } from "@/components/ui/badge";
import { Item, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";

export interface CatalogOriginProps {
	origin: CatalogOrigin;
	kind: "practice" | "group";
}

// Badges sit inside other controls; keep explanatory actions outside the badge.
function describeOrigin({ origin, kind }: CatalogOriginProps) {
	const noun = kind === "practice" ? "practice" : "group";
	switch (origin.state) {
		case "YOURS": {
			return null;
		}
		case "FROM_HEPHAESTUS": {
			return {
				label: "Same as the catalog",
				explanation: `${kind === "practice" ? "This practice matches" : "These group details match"} the catalog. A later catalog change does not edit your copy without your decision.`,
			};
		}
		case "EDITED_HERE": {
			return {
				label: "Edited here",
				explanation: `This workspace changed the ${noun}. Reset to catalog brings back the catalog’s version.`,
			};
		}
		case "UPDATE_WAITING": {
			return {
				label: "Catalog update waiting",
				explanation:
					kind === "practice"
						? "The catalog has a newer version. Your copy is untouched until you decide in Catalog updates."
						: "The catalog now has different group details. Your copy is untouched. Reset to catalog brings them across.",
			};
		}
		case "NO_LONGER_SHIPPED": {
			return {
				label: "No longer in the catalog",
				explanation: `New workspaces no longer receive this ${noun}. Yours keeps working exactly as it is.`,
			};
		}
	}
}

export function canResetToCatalog(origin: CatalogOrigin): boolean {
	return origin.state === "EDITED_HERE" || origin.state === "UPDATE_WAITING";
}

export function CatalogOriginBadge({
	className,
	...props
}: CatalogOriginProps & { className?: string }) {
	const text = describeOrigin(props);
	if (text === null) {
		return null;
	}
	return (
		<Badge variant="outline" className={className}>
			{text.label}
		</Badge>
	);
}

export function CatalogOriginNote({
	action,
	...props
}: CatalogOriginProps & { action?: React.ReactNode }) {
	const text = describeOrigin(props);
	if (text === null) {
		return null;
	}
	return (
		<Item variant="muted" size="sm" role="listitem">
			<ItemContent>
				<ItemTitle>{text.label}</ItemTitle>
				<ItemDescription className="line-clamp-none">{text.explanation}</ItemDescription>
				{action}
			</ItemContent>
		</Item>
	);
}
