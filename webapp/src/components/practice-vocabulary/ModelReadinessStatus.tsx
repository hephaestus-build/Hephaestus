import { definitionFor } from "@/components/common/status-def";
import { StatusBadge } from "@/components/common/StatusBadge";

import { MODEL_READINESS_DEFS, type ModelReadiness } from "./model-readiness-defs";

export interface ModelReadinessStatusProps {
	readiness: ModelReadiness;
}

/** The status cell of a model row in either console. */
export function ModelReadinessStatus({ readiness }: ModelReadinessStatusProps) {
	if (readiness === "READY") {
		return <span className="text-muted-foreground">Ready</span>;
	}
	return <StatusBadge def={definitionFor(MODEL_READINESS_DEFS, readiness)} />;
}
