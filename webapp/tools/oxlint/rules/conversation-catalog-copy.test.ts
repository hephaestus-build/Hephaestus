import { ruleTester } from "../rule-tester.ts";
import { conversationCatalogCopy } from "./conversation-catalog-copy.ts";

ruleTester.run("conversation-catalog-copy", conversationCatalogCopy, {
	valid: [
		{ code: '<p className="ps-2 text-muted-foreground">{conversationMessage("checkInTime")}</p>' },
	],
	invalid: [
		{ code: "<p>This check-in is ready.</p>", errors: [{ messageId: "catalog" }] },
		{ code: '<button aria-label="Stop" />', errors: [{ messageId: "catalog" }] },
		{ code: '<p>{done && "Done."}</p>', errors: [{ messageId: "catalog" }] },
		{ code: 'const label = "Stop answer";', errors: [{ messageId: "catalog" }] },
		{ code: 'const label = "stop this answer.";', errors: [{ messageId: "catalog" }] },
		{ code: "<p>{`Ready`}</p>", errors: [{ messageId: "catalog" }] },
		{
			code: 'const copy = "This check-in is ready."; const view = <p>{copy}</p>;',
			errors: [{ messageId: "catalog" }],
		},
	],
});
