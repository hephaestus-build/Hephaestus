import { Eye, EyeOff } from "lucide-react";

import type { StatusDefs } from "@/components/common/status-def";

export type DashboardVisibility = "VISIBLE" | "HIDDEN";

/**
 * Whether a group's practices appear on the dashboards developers read.
 *
 * A registry rather than a bare `<Badge>` because this sits inches from `CatalogOriginBadge` on the
 * same row, and the two mean unrelated things: one is a setting an administrator chose, the other is
 * a relationship to the catalog. Rendered as two identical outline chips they read as one family.
 * The icon is what separates them when colour cannot (WCAG 2.2 SC 1.4.1).
 */
export const DASHBOARD_VISIBILITY_DEFS: StatusDefs<DashboardVisibility> = {
	VISIBLE: {
		label: "On dashboards",
		icon: Eye,
		badgeVariant: "outline",
		description: "This group's practices appear on the dashboards developers read.",
	},
	HIDDEN: {
		label: "Off dashboards",
		icon: EyeOff,
		badgeVariant: "secondary",
		description:
			"Reviews still run and still record what they find. Only the dashboard display is off.",
	},
};

export function dashboardVisibilityOf(visible: boolean): DashboardVisibility {
	return visible ? "VISIBLE" : "HIDDEN";
}
