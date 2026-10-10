import type { LlmConnection } from "@/api/types.gen";
import { AGENT_PURPOSE_DEFS } from "@/components/practice-vocabulary/agent-purpose-defs";
import { ModelKindMark } from "@/components/practice-vocabulary/ModelKindMark";
import { LLM_API_PROTOCOL_LABELS } from "@/lib/llm-api-protocol-labels";
import { andList } from "@/lib/text";

export interface LlmConnectionApiProps {
	/** The server names the purposes its API can serve. */
	connection: Pick<LlmConnection, "apiProtocol" | "purposes">;
}

/**
 * The API a connection speaks, with the mark of each kind of model it can serve, so a connection
 * named by its admin still says what it is for. Both consoles show it under a connection's name.
 * The marks are one picture; a screen reader hears the kinds once, as a phrase.
 */
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
				, for {andList.format(purposes.map((purpose) => AGENT_PURPOSE_DEFS[purpose].title))}
			</span>
		</span>
	);
}
