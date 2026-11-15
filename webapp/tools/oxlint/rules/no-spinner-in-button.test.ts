import { ruleTester } from "../rule-tester.ts";
import { noSpinnerInButton } from "./no-spinner-in-button.ts";

ruleTester.run("no-spinner-in-button", noSpinnerInButton, {
	valid: [
		"const a = <Button pending={saving}>{saving ? 'Saving…' : 'Save'}</Button>;",
		// A spinner that stands alone for a region opts in to its announcement and is not a button's.
		'const b = <div><Spinner role="status" aria-label="Loading" /></div>;',
		// The switch row puts its spinner beside the control, which `Switch` `pending` owns.
		"const c = <ItemActions>{pending && <Spinner />}<Switch /></ItemActions>;",
		// Nested in another element, the spinner belongs to that element.
		"const d = <Button><span><Spinner /></span></Button>;",
		// A callback renders somewhere else, at another time.
		"const e = <Button render={(props) => <a {...props}><Spinner /></a>} />;",
		// A trigger that renders something other than a button.
		"const f = <TooltipTrigger render={<span />}><Spinner /></TooltipTrigger>;",
		// A component whose name only contains `Button`.
		"const g = <ButtonGroup>{pending && <Spinner />}</ButtonGroup>;",
	],
	invalid: [
		{
			code: "const h = <Button>{saving && <Spinner />}Save</Button>;",
			errors: [{ messageId: "spinner", line: 1, column: 30, endColumn: 41 }],
		},
		{
			code: "const i = <Button>{saving ? <Spinner /> : <SaveIcon />}Save</Button>;",
			errors: [{ messageId: "spinner" }],
		},
		{
			code: "const j = <Button><Spinner />Saving…</Button>;",
			errors: [{ messageId: "spinner" }],
		},
		{
			code: "const k = <Button>{saving ? (<><Spinner />Saving…</>) : 'Save'}</Button>;",
			errors: [{ messageId: "spinner" }],
		},
		{
			// A component that forwards to `Button`.
			code: "const l = <ActionButton>{busy ? <Spinner /> : <PlayIcon />}</ActionButton>;",
			errors: [{ messageId: "spinner" }],
		},
		{
			code: "const m = <AlertDialogAction>{deleting && <Spinner />}Delete</AlertDialogAction>;",
			errors: [{ messageId: "spinner" }],
		},
		{
			// The trigger passes its children into the button it renders.
			code: 'const n = <PopoverTrigger render={<Button variant="outline" />}>{busy && <Spinner />}Open</PopoverTrigger>;',
			errors: [{ messageId: "spinner" }],
		},
	],
});
