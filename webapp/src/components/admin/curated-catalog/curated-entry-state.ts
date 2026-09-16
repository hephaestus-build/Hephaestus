import type { CatalogEntryStatus } from "@/api/types.gen";

export type CatalogEntryState = CatalogEntryStatus["state"];
export type CatalogChangeKind = CatalogEntryStatus["changeKind"];

export interface CuratedEntryCopy {
	label: string;
	tone: "neutral" | "info" | "attention";
	detail: string;
}

export function curatedEntryCopy(
	status: CatalogEntryStatus,
	kind: "practice" | "group",
): CuratedEntryCopy {
	switch (status.state) {
		case "YOURS": {
			return {
				label: "No Hephaestus default",
				tone: "info",
				detail: status.offered
					? `This ${kind} has no Hephaestus default and is maintained on this instance.`
					: `This ${kind} has no Hephaestus default and is excluded from new workspaces.`,
			};
		}
		case "EDITED_HERE": {
			return {
				label: "Customized on this instance",
				tone: "info",
				detail: status.offered
					? `This ${kind} is customized on this instance. Updates to the Hephaestus default need review before they apply.`
					: `This customized ${kind} is excluded from new workspaces.`,
			};
		}
		case "UPDATE_WAITING": {
			if (kind === "group") {
				return {
					label: "Hephaestus update available",
					tone: "info",
					detail:
						"Applying this update would change the group's name, description, icon, or color. Your saved version stays in place until you decide.",
				};
			}
			return {
				// The label, not just the tone, has to say which kind of update this is: colour alone
				// cannot carry the distinction (WCAG 2.2 SC 1.4.1).
				label:
					status.changeKind === "WORDING"
						? "Hephaestus update available: wording"
						: "Hephaestus update available: review rules",
				tone: status.changeKind === "WORDING" ? "info" : "attention",
				detail:
					status.changeKind === "WORDING"
						? "Applying this update would change wording or developer guidance only. Review rules would stay the same."
						: "Applying this update would change review rules. Your saved version stays in place until you decide.",
			};
		}
		case "NO_LONGER_SHIPPED": {
			return {
				label: "Removed from Hephaestus defaults",
				tone: "attention",
				detail: status.offered
					? `This ${kind} is no longer a Hephaestus default. Keep it as a custom ${kind}, or exclude it from new workspaces.`
					: `This ${kind} is no longer a Hephaestus default and is excluded from new workspaces. Existing workspaces do not change.`,
			};
		}
		// Named rather than defaulted: a state added by the API later must become a type error, not
		// silently render the most reassuring answer.
		case "FROM_HEPHAESTUS": {
			return {
				label: "Uses Hephaestus default",
				tone: "neutral",
				detail: status.offered
					? `This ${kind} uses the Hephaestus default. Future updates apply automatically until you customize it.`
					: `This ${kind} uses the Hephaestus default but is excluded from new workspaces.`,
			};
		}
	}
}

export function canUseHephaestusVersion(status: CatalogEntryStatus): boolean {
	return status.state === "EDITED_HERE" || status.state === "UPDATE_WAITING";
}

export function canKeepCurrentDefinition(status: CatalogEntryStatus): boolean {
	return status.state === "UPDATE_WAITING" || status.state === "NO_LONGER_SHIPPED";
}

export function isOrdinary(status: CatalogEntryStatus): boolean {
	return status.state === "FROM_HEPHAESTUS" && status.offered;
}
