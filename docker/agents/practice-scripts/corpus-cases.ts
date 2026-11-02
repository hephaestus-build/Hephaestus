import type { DiffFile } from "./kits/scm/types.ts";
/**
 * Authored work that the corpus runs every bundled program on. Each case holds what a review of it
 * captures. These are not reviews of anyone's work: every line is written here to reach a branch of
 * a program, including the near misses, comments and strings that a program must not read as code.
 */
import { event, scan, type Files, type RunInputs } from "./run-bundled.ts";

/** A hunk body: `+` an added line, `-` a removed line, a space an unchanged line. */
type Hunk = readonly string[];

function diffFile(path: string, hunk: Hunk): { file: DiffFile; text: string } {
	const addedLines = new Map<number, string>();
	const removedLines = new Map<number, string>();
	const head: string[] = [];
	let removed = 0;
	for (const line of hunk) {
		const text = line.slice(1);
		if (line.startsWith("-")) {
			removed += 1;
			removedLines.set(removed, text);
		} else {
			head.push(text);
			if (line.startsWith("+")) {
				addedLines.set(head.length, text);
			}
		}
	}
	return {
		file: {
			path,
			addedLines,
			removedLines,
			hunks: [
				{
					oldStart: 1,
					oldCount: hunk.length - addedLines.size,
					newStart: 1,
					newCount: head.length,
					lines: [...hunk],
				},
			],
		},
		text: head.join("\n"),
	};
}

/** The captured change and a checkout that holds its head, with `extra` files around it. */
function change(hunks: Record<string, Hunk>, extra: Files = {}) {
	const files = Object.entries(hunks).map(([path, hunk]) => diffFile(path, hunk));
	return {
		change: new Map(files.map(({ file }) => [file.path, file])),
		repo: { ...extra, ...Object.fromEntries(files.map(({ file, text }) => [file.path, text])) },
	};
}

const added = (...lines: readonly string[]): Hunk => lines.map((line) => `+${line}`);

const SWIFT_VIEW = added(
	"import SwiftUI",
	'// let print("in a comment") = try! fake()',
	"struct CartView: View {",
	"    @State private var total = 0",
	"    @State var shared: Int",
	"    @State var store: CartStore",
	"    @State private var a = 0",
	"    @State private var b = 0",
	"    @State private var c = 0",
	"    @State private var d = 0",
	"    @State private var e = 0",
	"    @State private var f = 0",
	"    @StateObject private var model = CartModel()",
	"    @ObservedObject var made = CartModel()",
	"    @EnvironmentObject var settings: Settings",
	String.raw`    @Environment(\.dismiss) private var dismiss`,
	"    @Binding var one: Int",
	"    @Binding var two: Int",
	"    @Binding var three: Int",
	"    @Binding var four: Int",
	"    @Binding var five: Int",
	'    @AppStorage("seen") var seen = false',
	"    @Query var items: [Item]",
	"    var count = 0",
	"    private func refresh() async {",
	"        let data = try? await URLSession.shared.data(from: url)",
	"        let decoded = try! JSONDecoder().decode(Cart.self, from: data!)",
	'        UserDefaults.standard.set(decoded.total, forKey: "total")',
	"        FileManager.default.removeItem(at: url)",
	"        let contents = Data(contentsOf: url)",
	"        let timer = Timer.publish(every: 1, on: .main, in: .common)",
	"        print(decoded)",
	"        debugPrint(decoded)",
	"        dump(decoded)",
	'        NSLog("loaded")',
	'        Logger().info("loaded")',
	"        let first = decoded.items[0]",
	"        let cast = first as! Item",
	'        fatalError("unreachable")',
	'        preconditionFailure("unreachable")',
	"        // TODO: remove this",
	"        // let old = 1",
	"        // let older = 2",
	'        let token = "ghp_fixture"',
	'        let apiKey = "Zx81Qr7Lm2Vb9Tk4Ww0Pn6Ys"',
	String.raw`        let endpoint = "http://example.org/items?token=\(apiKey)"`,
	String.raw`        let quoted = "try! print(\(total))"`,
	"        do {",
	"            try save()",
	"        } catch {",
	"        }",
	"        do {",
	"            try modelContext.save()",
	"        } catch {",
	"            print(error)",
	"        }",
	"        try? modelContext.save()",
	"        Task.detached { await load() }",
	"        Task { await load() }",
	"        DispatchQueue.main.async { load() }",
	"        await MainActor.run { total = 1 }",
	"        let name = Int(text) ?? 0",
	"        guard let value = Int(text) else { return }",
	"        CLLocationManager().requestWhenInUseAuthorization()",
	"        AVCaptureSession().startRunning()",
	"    }",
	"    private func saveDraft() {",
	"    }",
	"    var body: some View {",
	"        VStack {",
	"            HStack {",
	"                ZStack {",
	"                    Group {",
	"                        List {",
	"                            Section {",
	'                                Image(systemName: "cart")',
	'                                Image("banner")',
	'                                Button { pay() } label: { Image(systemName: "pay") }',
	'                                Text("Total").font(.system(size: 14))',
	'                                Text("Name").font(.headline).foregroundStyle(ok ? .green : .red)',
	"                                Text(error.localizedDescription)",
	'                                Toggle("Save", isOn: $seen)',
	'                                Label("Pay", systemImage: "pay")',
	"                                ProgressView()",
	"                                    .onTapGesture { pay() }",
	"                                    .frame(height: 20)",
	"                                    .lineLimit(1)",
	"                            }",
	"                        }",
	"                    }",
	"                }",
	"            }",
	"        }",
	"        .background(Color(red: 1, green: 0, blue: 0))",
	"        .foregroundColor(.white)",
	"        .foregroundStyle(.secondary)",
	"        .background(.regularMaterial)",
	'        .tint(Color("Brand"))',
	'        .border(Color("Unknown"))',
	"        .fill(Color.green)",
	'        .font(.custom("Inter", fixedSize: 12))',
	'        .font(.custom("Inter", size: 12))',
	'        .accessibilityLabel("Cart")',
	"        .environmentObject(model)",
	"        .task { await load() }",
	"        .refreshable { await load() }",
	"        .onAppear { start() }",
	"    }",
	"}",
	"#Preview {",
	"    let cart = try! Cart()",
	"    CartView()",
	"}",
);

const LONG_BODY = added(
	"import SwiftUI",
	"struct Report: View {",
	"    var body: some View {",
	...Array.from({ length: 56 }, () => "        Text(title)"),
	"    }",
	"}",
);

/** Every language a program names a row for, with a comment and a string that hold the same words. */
const POLYGLOT: Record<string, Hunk> = {
	"web/cart.ts": added(
		"// process.exit(1) in a comment",
		'const note = "process.exit(1) in a string";',
		"try { pay(); } catch {}",
		"try { pay(); } catch (e) { console.log(e); }",
		"pay().catch(() => {});",
		"console.log(req.query.id);",
		"logger.info(order);",
		"const n = parseInt(req.query.id);",
		"const q = order!.total / count;",
		"const row = JSON.parse(req.body.raw);",
		'db.run("SELECT * FROM t WHERE id = " + req.params.id);',
		"el.innerHTML = req.body.html;",
		"eval(req.body.code);",
		'fetch("http://example.org/a");',
		"debugger;",
		"process.exit(1);",
		"export function total(): number { return 1; }",
		'const password = "abcdefgh12345678";',
		"app.use(cors({ allowedOrigins: '*' }));",
	),
	"web/page.tsx": added(
		"export const Page = () => <p>don't panic</p>;",
		"try { render(); } catch {}",
		"console.log(page);",
	),
	"web/quote.js": added(
		"const clean = value.replace(/['\"]/g, '');",
		"try { run(); } catch {}",
		"console.debug(clean);",
		"process.exit(2);",
	),
	"app/tool.py": added(
		"# os.system('in a comment')",
		"def load(path):",
		"    value = int(request.args['id'])",
		"    row = json.loads(request.data)",
		"    print(value)",
		"    logging.info(value)",
		"    cursor.execute('SELECT * FROM t WHERE id = ' + value)",
		"    os.system(request.args['cmd'])",
		"    breakpoint()",
		"    try:",
		"        run()",
		"    except: pass",
		"    try:",
		"        run()",
		"    except ValueError: continue",
		"    sys.exit(1)",
		"    requests.get(url, verify=False)",
	),
	"pkg/load.go": added(
		'// panic("in a comment")',
		"func Load(r *http.Request) {",
		'\tid := r.URL.Query().Get("id")',
		"\t_ = run(id)",
		"\tif err != nil {}",
		"\tn, _ := strconv.Atoi(id)",
		"\tfmt.Println(n)",
		'\tlog.Fatalf("bad %d", n)',
		"\tos.Exit(1)",
		"\tpanic(id)",
		"}",
	),
	"src/main/App.java": added(
		"// System.exit(1) in a comment",
		"public class App {",
		"  public void load(@RequestParam String id) {",
		"    try { run(); } catch (Exception e) {}",
		"    try { run(); } catch (Exception e) { e.printStackTrace(); }",
		"    System.out.println(id);",
		"    LOG.info(id);",
		"    int n = Integer.parseInt(id);",
		"    Runtime.getRuntime().exec(id);",
		"    System.exit(1);",
		"  }",
		"}",
	),
	"src/main/App.kt": added(
		"fun load(id: String?) {",
		"  val n = id!!.toInt()",
		"  val x = runCatching { run() }.getOrNull()",
		"  try { run() } catch (e: Exception) {}",
		"  println(n)",
		'  Log.d("app", "n")',
		'  error("bad")',
		"}",
	),
	"src/lib.rs": added(
		"// x.unwrap() in a comment",
		"fn load<'a>(s: &'a str) {",
		"    let c = '\"';",
		"    let v = s.parse::<i32>().unwrap();",
		'    let w = s.parse::<i32>().expect("number");',
		"    run().ok();",
		"    let _ = run();",
		"    if let Err(_) = run() {}",
		'    println!("{}", v);',
		'    panic!("bad");',
		"}",
	),
	"app/models/cart.rb": added(
		"# abort in a comment",
		"def load",
		"  value = params[:id]",
		'  puts "loaded"',
		"  JSON.parse(value)",
		"  begin; run; rescue => e",
		"  begin; run; rescue; end",
		"  exit!",
		"end",
	),
	"native/cart.c": added(
		"// abort(); in a comment",
		'char *note = "abort() in a string";',
		"int load(void) {",
		"  int n = atoi(input);",
		"  assert(n > 0);",
		"  abort();",
		"  exit(1);",
		"}",
	),
	"ios/Cart.m": added(
		"// NSAssert(a, nil); in a comment",
		"- (void)load {",
		'  NSAssert(ready, @"ready");',
		'  printf("loaded");',
		'  NSLog(@"loaded");',
		"  abort();",
		"}",
	),
	"src/Cart.cs": added(
		"public class Cart {",
		"  public void Load(string id) {",
		"    var n = int.Parse(id);",
		"    var first = items.First();",
		"    var row = order!.Total;",
		"    Debug.Assert(n > 0);",
		"    Environment.Exit(1);",
		"    Html.Raw(id);",
		"  }",
		"}",
	),
	"public/cart.php": added(
		"<?php",
		"// exec($x); in a comment",
		'$id = $_GET["id"];',
		"exec($id);",
		"echo $id;",
	),
	"web/cart.test.ts": added('test("cart", () => { console.log(cart!.total); process.exit(0); });'),
	"AppTests/CartTests.swift": added("func testCart() { let x = try! load(); print(x) }"),
	"docs/notes.md": added("# Notes", "TODO: try! this"),
	"assets/logo.png": [],
	".gitignore": added(".env", "secrets.xcconfig"),
};

const MANIFESTS: Record<string, Hunk> = {
	"package.json": ["  {", '-    "left-pad": "1.0.0",', '+    "left-pad": "^1.0.0",', "  }"],
	"services/api/Cargo.toml": ['-serde = "1.0.1"', '+serde = "1.0.2"', '+tokio = { version = "1" }'],
	"requirements.txt": ["-flask==2.0.0", "+flask", "+requests>=2.0"],
	Gemfile: ["-gem 'rails', '7.0.0'", "+gem 'rails', '~> 7.1'", "+gem 'puma'"],
	"pom.xml": [
		"  <dependency>",
		"+    <artifactId>guava</artifactId>",
		"-    <version>30</version>",
		"+    <version>31</version>",
		"  </dependency>",
	],
	"build.gradle.kts": ['-implementation("com.a:b:1.0")', '+implementation("com.a:b:1.1")'],
	"Package.swift": [
		'+.package(url: "https://example.org/a.git", from: "1.0.0"),',
		'-.package(url: "https://example.org/b.git", exact: "1.0.0"),',
	],
	"project.yml": ["+packages:", "+  Foo:", "+    url: https://example.org/foo", "+    from: 1.0.0"],
	"go.mod": ["-require example.org/a v1.0.0", "+require example.org/a v1.1.0"],
	"pyproject.toml": ["+[project]", '+dependencies = "a"'],
	"yarn.lock": added("left-pad@^1.0.0:"),
	"App/Info.plist": added("<key>NSCameraUsageDescription</key>", "<string>Scan</string>"),
};

const AROUND: Files = {
	"package-lock.json": "{}\n",
	"Library/Package.swift": '.library(name: "Cart")\n',
	"Project/project.yml": "SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor\n",
	"Project/Info.plist": "<key>NSLocationWhenInUseUsageDescription</key>\n",
	"Assets.xcassets/Brand.colorset/Contents.json": JSON.stringify({
		colors: [{ appearances: [{ appearance: "luminosity", value: "dark" }] }],
	}),
	"Assets.xcassets/Plain.colorset/Contents.json": JSON.stringify({ colors: [{}] }),
};

const shaOf = (digit: number) => String(digit).repeat(40);
const pretty = (value: unknown) => JSON.stringify(value, null, 2);

const COMMITS = {
	commits: [
		["fix", "App/Cart.swift", "2026-01-02T10:00:00Z", []],
		[
			"Add the cart total and keep it in the store, with a test",
			"App/Cart.swift",
			"2026-01-02T11:00:00Z",
			[shaOf(1)],
		],
		["fix", "web/cart.ts", "2026-01-02T12:00:00Z", [shaOf(2)]],
		[
			"Merge branch 'main' into feature",
			"web/cart.ts",
			"2026-01-02T13:00:00Z",
			[shaOf(3), shaOf(1)],
		],
		["wip", "web/cart.ts", "2026-01-03T10:00:00Z", [shaOf(4)]],
	].map(([message, path, at, parents], index) => ({
		sha: shaOf(index + 1),
		message,
		author: "author",
		authoredAt: at,
		committer: "author",
		committedAt: at,
		parents,
		files: [{ status: "modified", path, additions: 1, deletions: 0 }],
	})),
};

const INLINE = [
	{
		id: 1,
		thread: 1,
		path: "web/cart.ts",
		line: 3,
		side: "RIGHT",
		outdated: false,
		body: "Please handle this failure.",
		author: "reviewer",
		created_at: "2026-01-02T11:30:00Z",
	},
	{
		id: 2,
		thread: 1,
		in_reply_to: 1,
		path: "web/cart.ts",
		line: 3,
		side: "RIGHT",
		outdated: false,
		body: "Will do later, see #12.",
		author: "author",
		created_at: "2026-01-02T11:40:00Z",
	},
	{
		id: 3,
		thread: 2,
		path: "web/cart.ts",
		line: 5,
		side: "RIGHT",
		outdated: true,
		body: "nit: optional rename",
		author: "linter",
		bot: true,
		created_at: "2026-01-02T11:50:00Z",
	},
];

const CONVERSATION = {
	comments: [
		{
			body: "Can you mark this ready and link an issue?",
			author: "reviewer",
			createdAt: "2026-01-02T12:30:00Z",
		},
		{ body: "Done, this closes #12.", author: "author", createdAt: "2026-01-02T12:40:00Z" },
	],
};

const THREADS = {
	review_decision: "APPROVED",
	threads: [
		{ id: 1, path: "web/cart.ts", line: 3, state: "UNRESOLVED", createdAt: "2026-01-02T11:30:00Z" },
		{
			id: 2,
			path: "web/cart.ts",
			line: 5,
			state: "RESOLVED",
			resolvedBy: "reviewer",
			resolvedAt: "2026-01-05T09:00:00Z",
			createdAt: "2026-01-02T11:50:00Z",
		},
		{ id: 3, path: "web/cart.ts", line: 7, state: "RESOLVED", createdAt: "2026-01-02T11:51:00Z" },
	],
	reviewDecisions: [
		{
			state: "COMMENTED",
			author: "reviewer",
			submittedAt: "2026-01-02T11:35:00Z",
			body: "See the comment.",
		},
		{ state: "APPROVED", author: "reviewer", submittedAt: "2026-01-03T12:00:00Z" },
		{ state: "APPROVED", author: "author", submittedAt: "2026-01-03T12:05:00Z" },
		{ state: "APPROVED", author: "helper", bot: true },
		{ state: "APPROVED", author: "former", submittedAt: "2026-01-03T12:06:00Z", dismissed: true },
	],
};

const LINKED = {
	workItems: [
		{
			number: 12,
			title: "Show the cart total",
			body: "## Acceptance criteria\n- [x] It shows the total\n- [ ] It shows the tax",
			state: "OPEN",
			how: "closesOnMerge",
			url: "https://example.org/team/project/issues/12",
			createdAt: "2026-01-01T09:00:00Z",
			subIssuesTotal: 2,
			subIssuesCompleted: 1,
			subIssuesSource: "provider",
		},
		{
			number: 13,
			title: "Later work",
			body: "Some words.",
			state: "CLOSED",
			how: "mentions",
			createdAt: "2026-01-04T09:00:00Z",
			closedAt: "2026-01-05T09:00:00Z",
			subIssuesTotal: 0,
			subIssuesSource: "children",
		},
	],
	unresolvedReferences: [14],
};

const RECORDS: Files = {
	"commits.json": pretty(COMMITS),
	"comments.json": pretty(INLINE),
	"general_comments.json": pretty(CONVERSATION),
	"review_threads.json": pretty(THREADS),
	"linked_work_items.json": pretty(LINKED),
	"linked_work_items/12.md": LINKED.workItems[0]?.body ?? "",
	"linked_work_items/13.md": LINKED.workItems[1]?.body ?? "",
	"metadata.json": "{\n}\n",
	"description.md": "A description.\n",
};

const DESCRIBED = {
	title: "Cart total #12",
	body: [
		"Closes #12. Related to #13. Fixes team/other#9.",
		"<!-- #99 in a comment -->",
		"",
		"## Testing",
		"Open the cart and check the total.",
		"",
		"- [x] Reviewed",
		"- [ ] Documented",
		"",
		"![screen](https://example.org/a.png)",
	].join("\n"),
	source_branch: "12-cart-total",
	target_branch: "main",
	author: "author",
	changed_files: 30,
};

const MERGED = {
	...DESCRIBED,
	is_merged: true,
	merged_at: "2026-01-04T10:00:00Z",
	merged_by: "author",
};

const openedPullRequest = event("scm.pull_request.opened");
const mergedPullRequest = event("scm.pull_request.merged");
const openedIssue = event("scm.issue.opened");
const closedIssue = event("scm.issue.closed");

const SWIFT = "App/CartView.swift";

export const CASES: readonly { name: string; run: RunInputs }[] = [
	{
		name: "swift-view",
		run: {
			...change({ [SWIFT]: SWIFT_VIEW, "App/Report.swift": LONG_BODY }, AROUND),
			inputs: openedPullRequest,
			item: DESCRIBED,
			context: RECORDS,
		},
	},
	{
		name: "every-language",
		run: {
			...change(POLYGLOT, AROUND),
			inputs: openedPullRequest,
			item: DESCRIBED,
			context: RECORDS,
		},
	},
	{
		name: "manifests",
		run: { ...change(MANIFESTS), inputs: openedPullRequest, item: DESCRIBED, context: RECORDS },
	},
	{
		name: "merged",
		run: {
			...change({ "web/cart.ts": POLYGLOT["web/cart.ts"] ?? [] }),
			inputs: mergedPullRequest,
			item: MERGED,
			context: RECORDS,
		},
	},
	{
		name: "more-sites-than-any-list",
		run: {
			...change({
				"App/Many.swift": added(
					...Array.from(
						{ length: 220 },
						(_, i) => `let x${String(i)} = try! load(${String(i)}); print(x${String(i)})`,
					),
				),
			}),
			inputs: openedPullRequest,
			item: DESCRIBED,
			context: RECORDS,
		},
	},
	{ name: "empty-change-nothing-captured", run: { change: new Map(), inputs: openedPullRequest } },
	{
		name: "change-not-captured",
		run: { change: null, inputs: openedPullRequest, item: DESCRIBED, context: RECORDS },
	},
	{
		name: "issue-with-outcome",
		run: {
			inputs: openedIssue,
			item: {
				title: "Export the billing report",
				body: "## Outcome\n- [x] Export to CSV\n- [ ] Export to PDF\n\nSee #1 and #2.",
				issue_type: "Task",
				labels: ["feature", "billing"],
				assignees: ["owner"],
				milestone: "1.0",
				sub_issues_total: 3,
				sub_issues_completed: 1,
				sub_issues_source: "provider",
			},
		},
	},
	{ name: "issue-empty", run: { inputs: openedIssue, item: { title: "Bug", body: "" } } },
	{
		name: "issue-closed",
		run: {
			inputs: closedIssue,
			item: {
				title: "Umbrella for the reports epic",
				body: "Umbrella for the reports epic\n- [ ] One",
				state: "CLOSED",
				state_reason: "COMPLETED",
				closed_at: "2026-01-05T09:00:00Z",
				labels: ["epic"],
				sub_issues_total: 0,
				sub_issues_source: "children",
			},
		},
	},
	{
		name: "scan-swift",
		run: {
			inputs: scan(SWIFT),
			repo: { ...AROUND, [SWIFT]: SWIFT_VIEW.map((line) => line.slice(1)).join("\n") },
		},
	},
	{
		name: "scan-typescript",
		run: {
			inputs: scan("web/cart.ts"),
			repo: {
				"web/cart.ts": (POLYGLOT["web/cart.ts"] ?? []).map((line) => line.slice(1)).join("\n"),
			},
		},
	},
];
