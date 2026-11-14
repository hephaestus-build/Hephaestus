import { CircleAlert } from "lucide-react";
import type { ReactNode } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { formatNumber } from "@/lib/numbers";

export interface UnavailableCostAlertProps {
	uses: number;
	/** Where this reader sets the missing rates. Each console names its own place. */
	children: ReactNode;
}

/** Spend totals leave out usage whose cost is unavailable, so both consoles say how much is missing. */
export function UnavailableCostAlert({ uses, children }: UnavailableCostAlertProps) {
	return (
		<Alert variant="warning" role="note">
			<CircleAlert aria-hidden />
			<AlertTitle>
				{uses === 1
					? "1 use has unavailable cost"
					: `${formatNumber(uses)} uses have unavailable cost`}
			</AlertTitle>
			<AlertDescription>
				<p>
					Usage with unavailable cost is not counted in these totals. A rate may be missing or a
					measurement unverified. Real spend may be higher. Adding rates repairs only verified
					usage. {children}
				</p>
			</AlertDescription>
		</Alert>
	);
}
