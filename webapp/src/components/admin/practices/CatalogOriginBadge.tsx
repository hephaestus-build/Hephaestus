import type { CatalogOrigin } from "@/api/types.gen";
import { Badge } from "@/components/ui/badge";
import { Item, ItemContent, ItemDescription, ItemTitle } from "@/components/ui/item";

export interface CatalogOriginProps {
	origin?: CatalogOrigin | null;
	kind: "practice" | "group";
}

/**
 * What the badge says, and the sentence that goes with it. The badge sits inside accordion triggers
 * and list rows, where a focusable control cannot nest, so only the label travels there. The
 * sentence is written out where the thing is opened, the practice's panel and the group's edit dialog,
 * where it is read rather than hovered.
 */
function describeOrigin({ origin, kind }: CatalogOriginProps) {
	if (!origin) {
		return null;
	}
	// `kind` is the code word; these are the two words the reader sees.
	const subject = kind === "practice" ? "practice definition" : "group details";
	const noun = kind === "practice" ? "practice" : "group";

	if (!origin.sourceOffered) {
		return {
			label: "No longer in the catalog",
			explanation: `New workspaces no longer receive this ${noun}. Yours keeps working exactly as it is.`,
		};
	}
	if (origin.link === "UPDATE_AVAILABLE") {
		return {
			label: "Catalog changed, yours did not",
			explanation:
				kind === "practice"
					? "The catalog changed. Your copy is untouched. Review the proposed fields in Practice updates."
					: `The catalog now has different ${subject}. Your copy is untouched — bring anything you want across by editing it.`,
		};
	}
	if (origin.link === "DECLINED") {
		return {
			label: "Update declined",
			explanation:
				"You declined this catalog version. Your copy is unchanged. A different version can be offered later.",
		};
	}
	if (origin.link === "IN_SYNC") {
		return {
			label: "Same as the catalog",
			explanation: `These ${subject} match the catalog now. A later catalog change will not edit your copy without your decision.`,
		};
	}
	return {
		label: "Edited here",
		explanation:
			kind === "practice"
				? "This workspace changed the practice. A separate catalog update may also be waiting in Practice updates."
				: `The ${subject} differ from the version copied into this workspace.`,
	};
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

/** The badge's label as a heading and its explanation in full. Renders nothing without provenance. */
export function CatalogOriginNote(props: CatalogOriginProps) {
	const text = describeOrigin(props);
	if (text === null) {
		return null;
	}
	return (
		<Item variant="muted" size="sm" role="listitem">
			<ItemContent>
				<ItemTitle>{text.label}</ItemTitle>
				<ItemDescription className="line-clamp-none">{text.explanation}</ItemDescription>
			</ItemContent>
		</Item>
	);
}
