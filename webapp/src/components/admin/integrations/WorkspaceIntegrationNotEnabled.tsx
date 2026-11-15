import { TriangleAlertIcon } from "lucide-react";

import { DocLink } from "@/components/common/DocLink";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import type { DocKey } from "@/lib/doc-targets";

export interface WorkspaceIntegrationNotEnabledProps {
	/** The integration's name as the catalog gives it, such as "Outline". */
	displayName: string;
	/** The operator guide that says how to enable it. */
	doc: DocKey;
}

/**
 * Stands in for the connect action of an integration that this instance has not enabled: the server
 * has no way to connect it, so a connect form here could only fail.
 */
export function WorkspaceIntegrationNotEnabled({
	displayName,
	doc,
}: WorkspaceIntegrationNotEnabledProps) {
	return (
		<Alert variant="warning">
			<TriangleAlertIcon />
			<AlertTitle>{displayName} is not enabled on this instance</AlertTitle>
			<AlertDescription>
				You cannot connect it until an instance operator{" "}
				<DocLink doc={doc}>enables the {displayName} integration</DocLink>.
			</AlertDescription>
		</Alert>
	);
}
