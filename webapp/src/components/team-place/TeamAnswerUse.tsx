import { AnswerUseDisclosure, UseGroup } from "@/components/conversation/AnswerUseDisclosure";

import type { TeamAnswerUseData } from "./team-place-entries";

function count(value: number, one: string, many: string): string | undefined {
	if (value === 0) {
		return undefined;
	}
	return value === 1 ? one : `${value} ${many}`;
}

export interface TeamAnswerUseProps {
	used: TeamAnswerUseData;
	answeredAt: Date;
	/** The message's words, which name this disclosure apart from the others in the thread. */
	text: string;
}

/**
 * What Hephaestus used for a team message (ADR 0157 decision 8), in the same disclosure as the private conversation.
 * The server counts it from rows; a team message never reads a member's private conversation.
 */
export function TeamAnswerUse({ used, answeredAt, text }: TeamAnswerUseProps) {
	const read = [
		count(used.observations, "One observation", "observations"),
		count(used.decisions, "One team decision", "team decisions"),
		count(used.messages, "One message in this thread", "messages in this thread"),
	].filter((item) => item !== undefined);
	return (
		<AnswerUseDisclosure answeredAt={answeredAt} text={text}>
			{read.length > 0 ? (
				<UseGroup title="Read" items={read} />
			) : (
				<p className="text-muted-foreground">
					Hephaestus read no observation, team decision or message.
				</p>
			)}
			<p className="text-muted-foreground">
				It read nothing from any member’s private conversation.
			</p>
		</AnswerUseDisclosure>
	);
}
