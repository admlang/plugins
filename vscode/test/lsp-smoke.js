// Talks to `adm lsp` the way the extension's language client does and checks
// the answers the editor features depend on. Needs no editor:
//
//	node test/lsp-smoke.js [path to adm]
const cp = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const adm = process.argv[2] || process.env.ADM || "adm";
const root = fs.mkdtempSync(path.join(os.tmpdir(), "adm-vscode-smoke-"));
const file = path.join(root, "main.adm");
const source = [
	"application Smoke {",
	"\tdef twice(n int) int {",
	"\t\treturn n * 2",
	"\t}",
	"",
	"\tdef new(args string[]) int {",
	'\t\tprintln("smoke {twice(21)}")',
	"\t\treturn missing(1)",
	"\t}",
	"}",
	"",
].join("\n");
fs.writeFileSync(file, source);
const uri = "file://" + file;

const server = cp.spawn(adm, ["lsp"], { cwd: root, stdio: ["pipe", "pipe", "inherit"] });
let buffer = Buffer.alloc(0);
let nextId = 1;
const waiting = new Map();
const published = [];
let onPublish;

server.stdout.on("data", (chunk) => {
	buffer = Buffer.concat([buffer, chunk]);
	for (;;) {
		const head = buffer.indexOf("\r\n\r\n");
		if (head < 0) {
			return;
		}
		const length = Number(/Content-Length: (\d+)/i.exec(buffer.subarray(0, head).toString())[1]);
		if (buffer.length < head + 4 + length) {
			return;
		}
		const message = JSON.parse(buffer.subarray(head + 4, head + 4 + length).toString());
		buffer = buffer.subarray(head + 4 + length);
		if (message.id !== undefined && message.method === undefined) {
			waiting.get(message.id)?.(message);
			waiting.delete(message.id);
		} else if (message.id !== undefined) {
			// A request from the server (capability registration): accept it.
			send({ jsonrpc: "2.0", id: message.id, result: null });
		} else if (message.method === "textDocument/publishDiagnostics") {
			published.push(message.params);
			onPublish?.();
		}
	}
});

function send(message) {
	const body = Buffer.from(JSON.stringify(message));
	server.stdin.write(`Content-Length: ${body.length}\r\n\r\n`);
	server.stdin.write(body);
}

function request(method, params) {
	const id = nextId++;
	return new Promise((resolve, reject) => {
		const timer = setTimeout(() => reject(new Error(`${method}: no answer in 60 s`)), 60000);
		waiting.set(id, (message) => {
			clearTimeout(timer);
			message.error ? reject(new Error(`${method}: ${message.error.message}`)) : resolve(message.result);
		});
		send({ jsonrpc: "2.0", id, method, params });
	});
}

function diagnosticsFor(target) {
	return new Promise((resolve, reject) => {
		const timer = setTimeout(() => reject(new Error("no diagnostics in 60 s")), 60000);
		const look = () => {
			const hit = published.find((p) => p.uri === target && p.diagnostics.length > 0);
			if (hit) {
				clearTimeout(timer);
				resolve(hit.diagnostics);
			}
		};
		onPublish = look;
		look();
	});
}

const checks = [];
function check(name, ok, detail) {
	checks.push({ name, ok: Boolean(ok) });
	console.log(`${ok ? "ok  " : "FAIL"} ${name}${detail ? "  " + detail : ""}`);
}

async function main() {
	const init = await request("initialize", {
		processId: process.pid,
		rootUri: "file://" + root,
		workspaceFolders: [{ uri: "file://" + root, name: "smoke" }],
		capabilities: {
			textDocument: {
				synchronization: { dynamicRegistration: true },
				publishDiagnostics: {},
				hover: { contentFormat: ["markdown", "plaintext"] },
				semanticTokens: { requests: { full: true, range: true }, tokenTypes: [], tokenModifiers: [], formats: ["relative"] },
			},
			workspace: { workspaceFolders: true, didChangeWatchedFiles: { dynamicRegistration: true } },
		},
	});
	const caps = init.capabilities;
	for (const name of [
		"hoverProvider",
		"definitionProvider",
		"referencesProvider",
		"completionProvider",
		"documentFormattingProvider",
		"documentSymbolProvider",
		"renameProvider",
		"semanticTokensProvider",
		"codeLensProvider",
		"inlayHintProvider",
	]) {
		check(`capability ${name}`, caps[name]);
	}
	send({ jsonrpc: "2.0", method: "initialized", params: {} });
	send({ jsonrpc: "2.0", method: "textDocument/didOpen", params: { textDocument: { uri, languageId: "adm", version: 1, text: source } } });

	const diagnostics = await diagnosticsFor(uri);
	const undefinedCall = diagnostics.find((d) => d.range.start.line === 7 && /missing/.test(d.message));
	check("diagnostic on the undefined call", undefinedCall, undefinedCall?.message);

	const at = { textDocument: { uri }, position: { line: 6, character: 20 } };
	const hover = await request("textDocument/hover", at);
	const hoverText = JSON.stringify(hover?.contents ?? "");
	check("hover names the function", /twice/.test(hoverText), hoverText.slice(0, 80));

	const definition = await request("textDocument/definition", at);
	const target = Array.isArray(definition) ? definition[0] : definition;
	const targetLine = (target?.range ?? target?.targetSelectionRange)?.start.line;
	check("definition goes to the declaration", targetLine === 1, `line ${targetLine}`);

	const references = await request("textDocument/references", { ...at, context: { includeDeclaration: true } });
	check("references include the call and the declaration", references?.length >= 2, `${references?.length} found`);

	const symbols = await request("textDocument/documentSymbol", { textDocument: { uri } });
	check("document symbols name the application", JSON.stringify(symbols).includes("Smoke"));

	const tokens = await request("textDocument/semanticTokens/full", { textDocument: { uri } });
	check("semantic tokens", tokens?.data?.length > 0 && tokens.data.length % 5 === 0, `${tokens?.data?.length / 5} tokens`);

	const lenses = await request("textDocument/codeLens", { textDocument: { uri } });
	const usage = (lenses ?? []).find((l) => /\busages?\b/.test(l.command?.title ?? ""));
	check("a usage lens the extension can turn into show-references", usage && usage.command.command === "", usage?.command?.title);

	const unformatted = source.replace("\t\treturn n * 2", "\t\treturn    n*2");
	send({
		jsonrpc: "2.0",
		method: "textDocument/didChange",
		params: { textDocument: { uri, version: 2 }, contentChanges: [{ text: unformatted }] },
	});
	const edits = await request("textDocument/formatting", { textDocument: { uri }, options: { tabSize: 4, insertSpaces: false } });
	check("formatting answers edits", edits?.length > 0, `${edits?.length} edits`);

	await request("shutdown", null);
	send({ jsonrpc: "2.0", method: "exit" });
}

main()
	.catch((err) => check(String(err.message ?? err), false))
	.finally(() => {
		server.kill();
		fs.rmSync(root, { recursive: true, force: true });
		const failed = checks.filter((c) => !c.ok).length;
		console.log(failed === 0 ? `\n${checks.length} checks passed` : `\n${failed} of ${checks.length} checks failed`);
		process.exit(failed === 0 ? 0 : 1);
	});
