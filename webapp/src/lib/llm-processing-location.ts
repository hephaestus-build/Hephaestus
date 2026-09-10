import type { LlmModel } from "@/api/types.gen";

export const LLM_PROCESSING_LOCATIONS = [
	{ value: "UNCLASSIFIED", label: "Unclassified" },
	{ value: "ON_PREMISES", label: "On-premises" },
	{ value: "PRIVATE_CLOUD", label: "Private cloud" },
] satisfies { value: LlmModel["processingLocation"]; label: string }[];
