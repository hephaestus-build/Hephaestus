import type { LlmConnection } from "@/api/types.gen";
import { definitionFor } from "@/components/common/status-def";
import {
	UNKNOWN_AGENT_PURPOSE_DEF,
	AGENT_PURPOSE_DEFS,
} from "@/components/practice-vocabulary/agent-purpose-defs";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import { LLM_API_PROTOCOL_LABELS } from "@/lib/llm-api-protocol-labels";
import { andList } from "@/lib/text";

export interface LlmConnectionApiProps {
	connection: Pick<LlmConnection, "apiProtocol" | "purposes">;
}

// Both consoles share these marks; screen readers need one phrase for the model kinds.
export function LlmConnectionApi({ connection: { apiProtocol, purposes } }: LlmConnectionApiProps) {
	return (
		<span className="inline-flex min-w-0 items-center gap-2">
			<span aria-hidden className="inline-flex shrink-0 gap-1">
				{purposes.map((purpose) => (
					<ModelKindMark key={purpose} purpose={purpose} label="sr-only" />
				))}
			</span>
			<span className="min-w-0 truncate">{LLM_API_PROTOCOL_LABELS[apiProtocol].label}</span>
			<span className="sr-only">
				, for{" "}
				{andList.format(
					purposes.map(
						(purpose) =>
							definitionFor(AGENT_PURPOSE_DEFS, purpose, UNKNOWN_AGENT_PURPOSE_DEF).title,
					),
				)}
			</span>
		</span>
	);
}
