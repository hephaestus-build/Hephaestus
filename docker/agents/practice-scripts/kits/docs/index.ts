import type { ScriptContext } from "../../lib/core/practice-script.ts";
import { documentRecordSchema } from "./records.ts";

export function docs<C extends Pick<ScriptContext, "kind" | "item" | "evidence">>(ctx: C) {
	if (ctx.kind !== "docs.document") {
		throw new Error("the script uses the wrong domain kit");
	}
	return {
		...ctx,
		kind: "docs.document" as const,
		item: documentRecordSchema.parse(ctx.item),
		body: async () => ctx.evidence.record("document.md"),
		cite: async (startLine: number, endLine?: number) =>
			ctx.evidence.citeRecord("document.md", startLine, endLine),
	};
}
