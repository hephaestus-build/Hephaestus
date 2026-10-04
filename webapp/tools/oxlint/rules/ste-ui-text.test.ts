import { ruleTester } from "../rule-tester.ts";
import { steUiText } from "./ste-ui-text.ts";

const options = [{ allPaths: true }];
ruleTester.run("ste-ui-text", steUiText, {
	valid: [
		{ code: "<p>Fish &amp; chips</p>", options },
		{ code: '<Input title="Fish &amp; chips" />', options },
		{ code: '<p>{"utilize" && "Use it"}</p>', options },
		{ code: "<p>Select the workspace.</p>", options },
		{ code: '<input placeholder="Select a workspace" aria-label="Workspace" />', options },
		{ code: '<div className="utilize" id="ensure" data-testid="via" />', options },
		{ code: '<Link to="/utilize" href="/ensure" />', options },
		{ code: 'const data = "utilize"; const a = <p>{data}</p>;', options },
		{ code: '<p>{format("utilize")}</p>', options },
		{ code: "<p>Use the workspace. Use the server.</p>", options },
		{ code: "<p>Leverage</p>", filename: "/unlisted.tsx" },
		{ code: "<style>{`a { color: red; }`}</style>", options },
		{ code: 'toast.success("Saved the group.");', options },
		{ code: "toast.error(message, { description: detail });", options },
		{ code: 'toast.dismiss("Utilize");', options },
		{ code: 'notify.error("Utilize it");', options },
		{ code: 'const group = { slug: "utilize", label: "Group" };', options },
		{ code: 'const [label] = ["Utilize"];', options },
		{
			code: "<p>Use the server to check the workspace and then use the server\n to check the workspace.</p>",
			options,
		},
		{
			code: "<p>Heph can record an observation.</p>",
			options: [{ allPaths: true, vocabulary: true }],
		},
	],
	invalid: [
		{ code: "<p>Don&#39;t stop it.</p>", options, errors: [{ messageId: "word" }] },
		{ code: "<p>Utili&#122;e it.</p>", options, errors: [{ messageId: "word" }] },
		{ code: '<Input title="Utili&#122;e it" />', options, errors: [{ messageId: "word" }] },
		{ code: '<p>{"Fish &amp; chips"}</p>', options, errors: [{ messageId: "semicolon" }] },
		{
			code: "<p>We’re ready.</p>",
			options,
			errors: [{ messageId: "word", data: { from: "we’re", to: "we are" } }],
		},
		{
			code: "<p>It’s been stopped.</p>",
			options,
			errors: [{ messageId: "word", data: { from: "it’s", to: "it is or it has" } }],
		},
		{
			code: "<p>Utilize the workspace.</p>",
			options,
			errors: [{ messageId: "word", data: { from: "utilize", to: "use" } }],
		},
		{
			code: "<p>Ensure access prior to use.</p>",
			options,
			errors: [{ messageId: "word" }, { messageId: "word" }],
		},
		{ code: '<img alt="Utilize this icon" />', options, errors: [{ messageId: "word" }] },
		{
			code: '<Input placeholder={"Utilize a workspace"} />',
			options,
			errors: [{ messageId: "word" }],
		},
		{ code: '<p>{"Utilize a workspace"}</p>', options, errors: [{ messageId: "word" }] },
		{ code: '<p>{ok ? "Utilize it" : "Use it"}</p>', options, errors: [{ messageId: "word" }] },
		{ code: '<p>{ok && "Utilize it"}</p>', options, errors: [{ messageId: "word" }] },
		{ code: `<p>{\`Utilize \${name} now\`}</p>`, options, errors: [{ messageId: "word" }] },
		{ code: '<p>{"uti" + "lize it"}</p>', options, errors: [{ messageId: "word" }] },
		{ code: "<p>Do not start it; stop it.</p>", options, errors: [{ messageId: "semicolon" }] },
		{ code: "<p>Don’t stop it.</p>", options, errors: [{ messageId: "word" }] },
		{
			code: "<p>Use the server to check the workspace and then use the server to check the workspace and then use the server to check the workspace again.</p>",
			options,
			errors: [{ messageId: "sentence" }],
		},
		{
			code: "<p>Use the server to check the workspace and then use the server\n to check the workspace and then use the server to check the workspace again.</p>",
			options,
			errors: [{ messageId: "sentence" }],
		},
		{ code: 'toast.error("Couldn\'t save the group.");', options, errors: [{ messageId: "word" }] },
		{
			code: 'toast.success("Saved", { description: "Don\'t stop; go." });',
			options,
			errors: [{ messageId: "word" }, { messageId: "semicolon" }],
		},
		{ code: `toast.warning(\`Utilize \${name}\`);`, options, errors: [{ messageId: "word" }] },
		{ code: 'const group = { label: "Don\'t stop" };', options, errors: [{ messageId: "word" }] },
		{
			code: 'const group = { description: "Stop; go." };',
			options,
			errors: [{ messageId: "semicolon" }],
		},
		{ code: 'const group = { "title": "Utilize it" };', options, errors: [{ messageId: "word" }] },
		{ code: "<p>{`Stop; go.`}</p>", options, errors: [{ messageId: "semicolon" }] },
		{
			code: "<p>Quux</p>",
			options: [{ allPaths: true, vocabulary: true }],
			errors: [{ messageId: "vocabulary" }],
		},
	],
});
