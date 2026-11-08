import type { ScriptContext } from "../../lib/core/practice-script.ts";
import { threadRecordSchema } from "./records.ts";

export function chat<C extends Pick<ScriptContext, "kind" | "item" | "evidence">>(ctx: C) {
	if (ctx.kind !== "chat.thread") {
		throw new Error("the script uses the wrong domain kit");
	}
	return { ...ctx, kind: "chat.thread" as const, item: threadRecordSchema.parse(ctx.item) };
}
