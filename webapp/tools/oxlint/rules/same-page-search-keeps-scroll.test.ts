import { ruleTester } from "../rule-tester.ts";
import { samePageSearchKeepsScroll } from "./same-page-search-keeps-scroll.ts";

ruleTester.run("same-page-search-keeps-scroll", samePageSearchKeepsScroll, {
	valid: [
		"void setSearch((previous) => ({ ...previous, q }), { replace: true });",
		'void navigate({ to: ".", search: update, resetScroll: false });',
		// A section that should open at its top says so.
		'void navigate({ to: ".", search: { tab: "a" }, resetScroll: true });',
		// Another page, or the same route for another workspace, opens at its top.
		'void navigate({ to: "/w/$workspaceSlug", search: {} });',
		'void navigate({ to: ".", params: { workspaceSlug }, search: () => ({}), replace: true });',
		"void navigate({ href: returnTo, replace: true });",
		// A spread may carry `resetScroll`, so the rule cannot know.
		'void navigate({ to: ".", search: update, ...options });',
		'const link = <Link to="/settings" search={{ chromeWorkspace }} />;',
		'const link = <Link to="." search={next} resetScroll={false} />;',
		'const link = <Link to="." search={next} {...props} />;',
		// No search at all is no search change.
		"void navigate({ to: '.', replace: true });",
	],
	invalid: [
		{
			code: "void navigate({ search: { q }, replace: true });",
			errors: [{ messageId: "reset", line: 1, column: 6 }],
		},
		{
			code: 'void navigate({ to: ".", search: (previous) => ({ ...previous, tab }) });',
			errors: [{ messageId: "reset" }],
		},
		{
			code: "void router.navigate({ search: { requestId } });",
			errors: [{ messageId: "reset" }],
		},
		{
			code: 'const link = <Link to="." search={(previous) => ({ ...previous, tab })} />;',
			errors: [{ messageId: "reset" }],
		},
	],
});
