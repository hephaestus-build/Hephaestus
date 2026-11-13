import { ruleTester } from "../rule-tester.ts";
import { noRawDocsUrl } from "./no-raw-docs-url.ts";

ruleTester.run("no-raw-docs-url", noRawDocsUrl, {
	valid: [
		'const href = docUrl("upgradeGuide");',
		'const element = <DocLink doc="upgradeGuide">Read the upgrade guide</DocLink>;',
		// Another site, such as the release notes on GitHub, is not the docs.
		'const notes = "https://github.com/hephaestus-build/Hephaestus/releases";',
	],
	invalid: [
		{
			code: 'const guide = "https://docs.hephaestus.build/admin/upgrade";',
			errors: [{ messageId: "raw", line: 1, column: 15 }],
		},
		{
			code: "const guide = `https://docs.hephaestus.build/admin/upgrade`;",
			errors: [{ messageId: "raw" }],
		},
		{
			code: 'const element = <a href="https://docs.hephaestus.build/user/overview">Guide</a>;',
			errors: [{ messageId: "raw" }],
		},
		{
			code: "const element = <p>Read docs.hephaestus.build/admin/upgrade first.</p>;",
			errors: [{ messageId: "raw" }],
		},
	],
});
