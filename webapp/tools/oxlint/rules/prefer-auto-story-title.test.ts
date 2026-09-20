import { ruleTester } from "../rule-tester.ts";
import { preferAutoStoryTitle } from "./prefer-auto-story-title.ts";

const component = "const Button = () => null;";

ruleTester.run("prefer-auto-story-title", preferAutoStoryTitle, {
	valid: [
		{
			code: `${component}\nconst meta = { component: Button } satisfies Meta<typeof Button>;`,
			filename: "webapp/src/components/ui/Button.stories.tsx",
		},
		{
			// Only the story meta is a title; fixture data keeps its own fields.
			code: `${component}\nconst issue = { title: "Fix login" };\nconst meta = { component: Button, args: { issue } } satisfies Meta<typeof Button>;`,
			filename: "webapp/src/components/ui/Button.stories.tsx",
		},
		{
			code: `const meta = { title: "Anything" };`,
			filename: "webapp/src/components/ui/Button.tsx",
		},
	],
	invalid: [
		{
			code: `${component}\nconst meta = { title: "components/ui/Button", component: Button } satisfies Meta<typeof Button>;`,
			filename: "webapp/src/components/ui/Button.stories.tsx",
			errors: [{ messageId: "explicit" }],
		},
		{
			code: `${component}\nconst meta = { title: "Workspace admin/Practices/Review/Overview", component: Button } satisfies Meta<typeof Button>;`,
			filename: "webapp/src/components/admin/practices/review/ReviewPage.stories.tsx",
			errors: [{ messageId: "explicit" }],
		},
		{
			code: `${component}\nconst meta = { title: prefix + "/Button", component: Button } satisfies Meta<typeof Button>;`,
			filename: "webapp/src/components/ui/Button.stories.tsx",
			errors: [{ messageId: "explicit" }],
		},
	],
});
