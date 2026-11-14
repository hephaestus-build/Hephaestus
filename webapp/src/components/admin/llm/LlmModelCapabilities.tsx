import { Button } from "@/components/ui/button";
import { FieldDescription, FieldError, FieldLegend, FieldSet } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import type { ToolChoiceTestState } from "@/lib/llm-tool-choice-test";

export interface LlmModelCapabilitiesProps {
	supported?: boolean;
	test: ToolChoiceTestState;
	onProbe: () => void;
}

/** The same saved capability and immediate test serve both catalogs. */
export function LlmModelCapabilities({ supported, test, onProbe }: LlmModelCapabilitiesProps) {
	const isPending = test.status === "testing";
	let label = "Not tested. Reviews let the model decide when to call tools.";
	if (isPending) {
		label = "Testing tool choice…";
	} else if (supported === true) {
		label = "Supported. Reviews can require the model to submit its result through a tool.";
	} else if (supported === false) {
		label = "Not supported. Reviews let the model decide when to call tools.";
	}
	return (
		<FieldSet>
			<FieldLegend variant="label">Tool choice</FieldLegend>
			<p className="text-sm" role="status">
				{label}
			</p>
			{test.status === "failed" && <FieldError>{test.message}</FieldError>}
			<FieldDescription>
				Tests whether reviews can require a tool call. Sends two short requests that your provider
				can charge for. The result is saved right away.
			</FieldDescription>
			<div>
				<Button type="button" variant="outline" size="sm" disabled={isPending} onClick={onProbe}>
					{isPending && <Spinner aria-hidden />}
					{isPending ? "Testing…" : "Test tool choice"}
				</Button>
			</div>
		</FieldSet>
	);
}
