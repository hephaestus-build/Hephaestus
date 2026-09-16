import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import {
	type DeliveryFacts,
	deliveryOutcome,
} from "@/components/practice-vocabulary/delivery-outcome-defs";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { hasText } from "@/lib/text";

export type FeedbackBodyFeedback = DeliveryFacts & { body?: string };

export interface FeedbackBodyProps {
	feedback: FeedbackBodyFeedback;
	className?: string;
}

export function FeedbackBody({ feedback, className }: FeedbackBodyProps) {
	const { body } = feedback;
	const unsent = feedback.deliveryState !== "DELIVERED";

	if (!hasText(body)) {
		return (
			<Card flush className={className}>
				<CardContent className="py-4">
					<p className="text-muted-foreground">No feedback text was composed for this record.</p>
				</CardContent>
			</Card>
		);
	}

	return (
		<Card flush className={className}>
			<Tabs defaultValue="rendered" className="gap-0">
				<CardHeader className="flex flex-wrap items-center justify-between gap-2 pt-3 pb-2">
					<TabsList aria-label="How to show the feedback">
						<TabsTrigger value="rendered" className="px-3">
							Rendered
						</TabsTrigger>
						<TabsTrigger value="source" className="px-3">
							Source
						</TabsTrigger>
					</TabsList>
					{unsent && <StatusBadge def={deliveryOutcome(feedback)} />}
				</CardHeader>
				<CardContent className="pb-4">
					<TabsContent value="rendered" className={UNTRUSTED_MARKDOWN_PROSE}>
						<UntrustedMarkdown>{body}</UntrustedMarkdown>
					</TabsContent>
					<TabsContent value="source">
						<pre className="rounded-md bg-muted p-3 text-xs break-words whitespace-pre-wrap">
							{body}
						</pre>
					</TabsContent>
				</CardContent>
			</Tabs>
		</Card>
	);
}
