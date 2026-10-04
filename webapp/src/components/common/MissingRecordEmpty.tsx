import { CloudOffIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";

export interface MissingRecordEmptyProps {
	/** What did not arrive, as a sentence: "This feedback has not loaded". */
	title: string;
	onRetry?: () => void;
	className?: string;
}

/**
 * The detail-page branch where there is no record *and* no error — which is not the same thing as a
 * record that is gone.
 *
 * A deleted record arrives as a 404, which is an error and reads as one: the generated query
 * functions pass `throwOnError: true` (`src/api/@tanstack/react-query.gen.ts`), so every status the
 * server answers with lands in `error`. What lands here instead is a query that is still pending
 * with nothing in flight — offline, where TanStack Query pauses the fetch rather than failing it, or
 * a fetch cancelled by navigating away and back.
 *
 * So this is deliberately not a `QueryErrorAlert`: that classifies by HTTP status, and an
 * absent status is read as a connection failure. Here there is no status to read, because nothing
 * ever answered. Saying so is more use than guessing at a cause.
 */
export function MissingRecordEmpty({ title, onRetry, className }: MissingRecordEmptyProps) {
	return (
		<Empty className={className}>
			<EmptyHeader>
				<EmptyMedia variant="icon">
					<CloudOffIcon />
				</EmptyMedia>
				<EmptyTitle>{title}</EmptyTitle>
				<EmptyDescription>
					Nothing reported a failure — the request never came back. You may be offline.
				</EmptyDescription>
			</EmptyHeader>
			{onRetry && (
				<EmptyContent>
					<Button type="button" variant="outline" size="sm" onClick={onRetry}>
						Try again
					</Button>
				</EmptyContent>
			)}
		</Empty>
	);
}
