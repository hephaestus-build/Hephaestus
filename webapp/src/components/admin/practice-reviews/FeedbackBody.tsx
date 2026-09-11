import { cn } from "cn";
import { UNTRUSTED_MARKDOWN_PROSE, UntrustedMarkdown } from "@/components/common/UntrustedMarkdown";
import {
	type DeliveryFacts,
	deliveryOutcome,
} from "@/components/practice-vocabulary/delivery-outcome-defs";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

export type FeedbackBodyFeedback = DeliveryFacts & { body?: string };

export interface FeedbackBodyProps {
	feedback: FeedbackBodyFeedback;
	className?: string;
}

export function FeedbackBody({ feedback, className }: FeedbackBodyProps) {
	const { body } = feedback;
	const unsent = feedback.deliveryState !== "DELIVERED";

	if (!body) {
		return (
			<Card className={cn("gap-0 border py-0", className)}>
				<CardContent className="py-4 text-sm text-muted-foreground">
					No feedback text was composed for this record.
				</CardContent>
			</Card>
		);
	}

	return (
		<Card className={cn("gap-0 border py-0", className)}>
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
						<pre className="whitespace-pre-wrap break-words rounded-md bg-muted p-3 text-xs">
							{body}
						</pre>
					</TabsContent>
				</CardContent>
			</Tabs>
		</Card>
	);
}
