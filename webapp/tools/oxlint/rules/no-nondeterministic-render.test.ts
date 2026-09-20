import { ruleTester } from "../rule-tester.ts";
import { noNondeterministicRender } from "./no-nondeterministic-render.ts";

ruleTester.run("no-nondeterministic-render", noNondeterministicRender, {
	valid: [
		// -- Not a reading of the real clock or the RNG --------------------------------------------
		'const a = new Date("2026-01-01");',
		"const b = new Date(STORY_NOW - 5 * MINUTE_MS);",
		"const c = Date.parse(iso);",
		"const d = Math.floor(ratio * 40);",
		"const e = clock.now();",
		"const f = seeded.random();",
		// A reference is not a call, so nothing is read here.
		"const g = Date.now;",
		"const h = MINUTE_MS * 60;",

		// -- Inside a function that is not a render ------------------------------------------------
		// The house helpers: a lowercase module-scope function is called from somewhere, and where
		// that is cannot be read here.
		"function startOfToday() { return new Date(); }",
		"const readClock = () => Date.now();",
		"function format(now: Date = new Date()) { return now; }",

		// -- Inside a component, but not during its render ------------------------------------------
		"function Card() { return <button onClick={() => setAt(Date.now())} />; }",
		"function Card() { useEffect(() => { setNow(new Date()); }, []); return null; }",
		// A mutation callback fires when the mutation does, not when the component renders.
		"function DangerZone() { const m = useMutation({ onMutate: () => setRequestedAt(Date.now()) }); return null; }",
		// A lazy initializer runs inside its own function, and is the sanctioned way to seed state
		// from the clock once. The eager spelling of this is reported below.
		"function Card() { const [now] = useState(() => new Date()); return <p>{String(now)}</p>; }",
		"function Ticker() { useEffect(() => { const id = setInterval(() => setNow(new Date()), 1000); return () => clearInterval(id); }); return null; }",
		// A `cell:` render prop is a component, but named like a callback — the rule cannot tell the
		// two apart and stays quiet rather than guessing.
		"const columns = [{ cell: () => <span>{Date.now()}</span> }];",

		// -- Not a component or a hook, so not known to be a render ---------------------------------
		"function useLabel() { return (at: number) => new Date(at); }",
		"class Timer { at = Date.now(); }",

		// -- date-fns, with the instant passed in -----------------------------------------------------
		'import { formatDistance, isBefore } from "date-fns";\nfunction Card({ at }) { const now = useNow(); return <p>{formatDistance(at, now)} {String(isBefore(at, now))}</p>; }',
		// The same name, but not the library's: a helper written here takes its instant from a caller.
		"function isPast(at: Date, now: Date) { return at < now; }\nfunction Card({ at }) { return <p>{String(isPast(at, useNow()))}</p>; }",
		'import { isPast } from "./clock";\nfunction Card({ at }) { return <p>{String(isPast(at))}</p>; }',
		// Called from a handler, it reads the clock at the moment of the event, which is right.
		'import { isPast } from "date-fns";\nfunction Card({ at }) { return <button onClick={() => setExpired(isPast(at))} />; }',
	],
	invalid: [
		// -- Module load ----------------------------------------------------------------------------
		{
			code: "const now = new Date();",
			errors: [{ messageId: "moduleLoad", data: { reading: "new Date()" }, line: 1, column: 13 }],
		},
		{
			code: "export const STORY_NOW = Date.now();",
			errors: [{ messageId: "moduleLoad", data: { reading: "Date.now()" } }],
		},
		{
			code: "const survey = { startedAt: new Date().toISOString() };",
			errors: [{ messageId: "moduleLoad" }],
		},
		{
			// Nothing about a statement's nesting changes when it runs.
			code: "if (enabled) { seed = Math.random(); }",
			errors: [{ messageId: "moduleLoad", data: { reading: "Math.random()" } }],
		},

		// -- During a render ------------------------------------------------------------------------
		{
			code: "function Card() { const now = new Date(); return <p>{String(now)}</p>; }",
			errors: [{ messageId: "duringRender", data: { reading: "new Date()" } }],
		},
		{
			code: "const Card = () => { const t = Date.now(); return <p>{t}</p>; };",
			errors: [{ messageId: "duringRender" }],
		},
		{
			// The near miss of the lazy initializer above: this argument is evaluated on every
			// render, and thrown away on all but the first.
			code: "function Card() { const [now] = useState(Date.now()); return <p>{now}</p>; }",
			errors: [{ messageId: "duringRender" }],
		},
		{
			// A hook body runs during the render of whoever calls it.
			code: "function useCountdown() { const now = Date.now(); return now; }",
			errors: [{ messageId: "duringRender" }],
		},
		{
			// A default evaluated during render is as non-deterministic as a body statement.
			code: "function Card({ now = new Date() }) { return <p>{String(now)}</p>; }",
			errors: [{ messageId: "duringRender" }],
		},
		{
			code: "function Card() { return <p>{Math.random()}</p>; }",
			errors: [{ messageId: "duringRender", data: { reading: "Math.random()" } }],
		},
		{
			// Two readings are two edits, so each is reported where it stands.
			code: "function Card() { const a = Date.now(); const b = new Date(); return <p>{a}{String(b)}</p>; }",
			errors: [{ messageId: "duringRender" }, { messageId: "duringRender" }],
		},

		// -- date-fns reading the clock for itself ----------------------------------------------------
		{
			code: 'import { formatDistanceToNow } from "date-fns";\nfunction Card({ at }) { return <p>{formatDistanceToNow(at)}</p>; }',
			errors: [{ messageId: "hiddenClock", data: { reading: "formatDistanceToNow()" }, line: 2 }],
		},
		{
			code: 'import { isPast, isFuture, isToday } from "date-fns";\nconst Card = ({ at }) => <p>{String(isPast(at) || isFuture(at) || isToday(at))}</p>;',
			errors: [
				{ messageId: "hiddenClock", data: { reading: "isPast()" } },
				{ messageId: "hiddenClock", data: { reading: "isFuture()" } },
				{ messageId: "hiddenClock", data: { reading: "isToday()" } },
			],
		},
		{
			// A hook body runs during the render of whoever calls it.
			code: 'import { formatDistanceToNowStrict } from "date-fns";\nfunction useAge(at) { return formatDistanceToNowStrict(at); }',
			errors: [{ messageId: "hiddenClock" }],
		},
		{
			// Module scope: the fixture is stamped once at import and drifts with the calendar.
			code: 'import { startOfToday } from "date-fns";\nexport const since = startOfToday();',
			errors: [{ messageId: "hiddenClock", data: { reading: "startOfToday()" } }],
		},
		{
			// Renamed on import, it is still the library's function and still reads the clock.
			code: 'import { isPast as expired } from "date-fns";\nfunction Card({ at }) { return <p>{String(expired(at))}</p>; }',
			errors: [{ messageId: "hiddenClock", data: { reading: "isPast()" } }],
		},
		{
			code: 'import { isToday } from "date-fns/isToday";\nfunction Card({ at }) { return <p>{String(isToday(at))}</p>; }',
			errors: [{ messageId: "hiddenClock" }],
		},
	],
});
