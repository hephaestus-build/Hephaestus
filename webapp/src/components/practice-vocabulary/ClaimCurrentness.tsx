import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { type Currentness, NON_CURRENT } from "./claim-currentness";

export function ClaimCurrentnessBadge({ currentness }: { currentness: Currentness }) {
	if (currentness === "CURRENT") {
		return null;
	}
	const config = NON_CURRENT[currentness];
	return <Badge variant={config.badgeVariant}>{config.badge}</Badge>;
}

export function ClaimCurrentnessAlert({ currentness }: { currentness: Currentness }) {
	if (currentness === "CURRENT") {
		return null;
	}
	const { Icon, title, description } = NON_CURRENT[currentness];
	return (
		<Alert variant="warning">
			<Icon />
			<AlertTitle>{title}</AlertTitle>
			<AlertDescription>{description}</AlertDescription>
		</Alert>
	);
}
