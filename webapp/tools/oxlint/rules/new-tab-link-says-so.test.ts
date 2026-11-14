import { ruleTester } from "../rule-tester.ts";
import { newTabLinkSaysSo } from "./new-tab-link-says-so.ts";

ruleTester.run("new-tab-link-says-so", newTabLinkSaysSo, {
	valid: [
		'const a = <a href={url} target="_blank" rel="noopener noreferrer">Docs<OutboundMark /></a>;',
		// The mark may sit in a condition or a fragment below the link.
		'const b = <a href={url} target="_blank">{label}{external && <><OutboundMark /></>}</a>;',
		// A link in a `render=` slot renders the slot owner's children.
		'const c = <Button render={<a href={url} target="_blank" rel="noopener" />}>Star<OutboundMark /></Button>;',
		// A slot inside a slot renders the outer owner's children.
		'const p = <Trigger render={<Badge render={<a href={url} target="_blank" />} />}>Docs<OutboundMark /></Trigger>;',
		'const r = <Badge render={href ? <a href={href} target="_blank" /> : undefined}>Docs<OutboundMark /></Badge>;',
		// A label replaces the content, so it carries the words itself.
		'const l = <a href={url} target="_blank" aria-label="TUM (opens in a new tab)"><img alt="" /><OutboundMark /></a>;',
		`const m = <a href={url} target="_blank" aria-label={\`View \${branch} (opens in a new tab)\`}><OutboundMark /></a>;`,
		// The same tab: nothing to announce.
		"const d = <a href={url}>Docs</a>;",
		'const e = <a href={url} target="_self">Docs</a>;',
		// The components that add the mark themselves.
		"const f = <InlineLink href={url} external>Docs</InlineLink>;",
		'const g = <DocLink doc="upgradeGuide">Read the upgrade guide</DocLink>;',
	],
	invalid: [
		{
			code: 'const h = <a href={url} target="_blank" rel="noopener noreferrer">Docs</a>;',
			errors: [{ messageId: "silent", line: 1, column: 11 }],
		},
		{
			code: 'const i = <a href={url} target={"_blank"}>Docs</a>;',
			errors: [{ messageId: "silent" }],
		},
		{
			code: 'const j = <Button render={<a href={url} target="_blank" />}>Star</Button>;',
			errors: [{ messageId: "silent" }],
		},
		{
			// A mark beside the link does not reach the link's accessible name.
			code: 'const k = <p><a href={url} target="_blank">Docs</a><OutboundMark /></p>;',
			errors: [{ messageId: "silent" }],
		},
		{
			code: 'const q = <Trigger render={<Badge render={<a href={url} target="_blank" />} />}>Docs</Trigger>;',
			errors: [{ messageId: "silent" }],
		},
		{
			code: 'const s = <Badge render={href ? <a href={href} target="_blank" /> : undefined}>Docs</Badge>;',
			errors: [{ messageId: "silent" }],
		},
		{
			code: 'const n = <a href={url} target="_blank" aria-label="TUM"><img alt="" /><OutboundMark /></a>;',
			errors: [{ messageId: "labelHides" }],
		},
		{
			code: `const o = <a href={url} target="_blank" aria-label={\`View \${branch}\`}><OutboundMark /></a>;`,
			errors: [{ messageId: "labelHides" }],
		},
	],
});
