import { CircleCheck, CircleDashed, CircleX, Eye, EyeOff, Split } from "lucide-react";

import type { ProductFeedbackDestinations } from "@/api/types.gen";
import type { StatusDefs } from "@/components/common/status-def";

/** What the server found when it checked one destination repository. */
export const REPOSITORY_CHECK_DEFS: StatusDefs<ProductFeedbackDestinations["publicCheck"]> = {
	UNSET: {
		label: "Not set",
		icon: CircleDashed,
		badgeVariant: "outline",
		description: "No repository is set.",
	},
	INSTALLED: {
		label: "Ready",
		icon: CircleCheck,
		badgeVariant: "success",
		description: "The GitHub App is installed, and the visibility fits.",
	},
	MISSING: {
		label: "Not reachable",
		icon: CircleX,
		badgeVariant: "destructive",
		description:
			"The GitHub App cannot reach the repository. It is not installed or suspended, or the repository is gone.",
	},
	NOT_PRIVATE: {
		label: "Not private",
		icon: Eye,
		badgeVariant: "destructive",
		description: "The private repository is public. Hephaestus files nothing there.",
	},
	NOT_PUBLIC: {
		label: "Not public",
		icon: EyeOff,
		badgeVariant: "warning",
		description:
			"The public repository is private, so its issues are not public. This is fine for a test instance.",
	},
	DIFFERENT_OWNER: {
		label: "Different owners",
		icon: Split,
		badgeVariant: "warning",
		description:
			"The two repositories have different owners, so an issue cannot move from one to the other.",
	},
};
