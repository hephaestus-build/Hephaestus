import { cn } from "cn";
import type { CatalogOrigin } from "@/api/types.gen";
import { badgeVariants } from "@/components/ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";

export interface CatalogOriginBadgeProps {
	origin?: CatalogOrigin | null;
	kind: "practice" | "group";
	className?: string;
}

/**
 * A badge summarizes the relationship; the release inbox owns the field-by-field decision.
 */
export function CatalogOriginBadge({ origin, kind, className }: CatalogOriginBadgeProps) {
	if (!origin) {
		return null;
	}
	// `kind` is the code word; these are the two words the reader sees.
	const subject = kind === "practice" ? "practice definition" : "group details";
	const noun = kind === "practice" ? "practice" : "group";

	if (!origin.sourceOffered) {
		return (
			<OriginBadge
				className={className}
				label="No longer in the catalog"
				explanation={`New workspaces no longer receive this ${noun}. Yours keeps working exactly as it is.`}
			/>
		);
	}
	if (origin.link === "UPDATE_AVAILABLE") {
		return (
			<OriginBadge
				className={className}
				label="Catalog changed, yours did not"
				explanation={
					kind === "practice"
						? "The catalogue changed. Your copy is untouched. Review the proposed fields in Practice updates."
						: `The catalog now has different ${subject}. Your copy is untouched — bring anything you want across by editing it.`
				}
			/>
		);
	}
	if (origin.link === "IN_SYNC") {
		return (
			<OriginBadge
				className={className}
				label="Same as the catalog"
				explanation={`These ${subject} match the catalog now. A later catalog change will not edit your copy without your decision.`}
			/>
		);
	}
	return (
		<OriginBadge
			className={className}
			label="Edited here"
			explanation={
				kind === "practice"
					? "This workspace changed the practice. A separate catalogue update may also be waiting in Practice updates."
					: `The ${subject} differ from the version copied into this workspace.`
			}
		/>
	);
}

function OriginBadge({
	label,
	explanation,
	className,
}: {
	label: string;
	explanation: string;
	className?: string;
}) {
	return (
		<Tooltip>
			<TooltipTrigger className={cn(badgeVariants({ variant: "outline" }), className)}>
				{label}
			</TooltipTrigger>
			<TooltipContent>{explanation}</TooltipContent>
		</Tooltip>
	);
}
