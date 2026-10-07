# ADM for Visual Studio Code

Support for the [ADM language](https://adm-lang.dev) through `adm lsp`, the language server that
ships with the compiler.

## Requirements

ADM installed, with `adm` on `PATH` or in `~/.adm/bin`:

```sh
curl -fsSL https://raw.githubusercontent.com/admlang/adm/main/install.sh | sh
```

Set `adm.path` when the program is somewhere else.

## What it does

| | |
|---|---|
| Editing | Diagnostics as you type, completion, signature help, hover, inlay hints, semantic highlighting, folding, selection ranges |
| Navigation | Go to definition, type definition and implementations, find references, document and workspace symbols, usage counts above declarations |
| Changes | Rename, code actions, formatting with `adm fmt` rules |
| Tests | The check suites of the workspace in the Testing view: run a file, a suite or one test, with failures shown at the failing line |
| Commands | **ADM: Run Application**, **Build**, **Check**, **Run Tests**, **Lint**, on the application or library of the open file |
| Tasks | Tasks of type `adm` for `tasks.json` (below) |

Syntax colouring comes from a TextMate grammar and is refined by the server's semantic tokens.

## Settings

| Setting | Default | |
|---|---|---|
| `adm.path` | `adm` | The `adm` program. A bare name is looked up on `PATH`, then in `~/.adm/bin` |
| `adm.lib` | | Where the prelude and standard library are (`ADM_LIB`); empty leaves it to `adm` |
| `adm.env` | `{}` | Environment variables for every `adm` the extension starts |
| `adm.test.arguments` | `[]` | Extra arguments for `adm test` in the Testing view, such as `--release` |
| `adm.lsp.logProtocol` | `false` | Start the server with `--log-protocol`; the trace goes to the "ADM Language Server" output |
| `adm.trace.server` | `off` | The editor's own trace of the protocol |

Changing `adm.path`, `adm.lib`, `adm.env` or `adm.lsp.logProtocol` restarts the server;
**ADM: Restart Language Server** does it by hand, for example after updating ADM.

## Tasks

```json
{
	"version": "2.0.0",
	"tasks": [
		{ "type": "adm", "command": "build", "args": ["--release"], "group": "build" },
		{ "type": "adm", "command": "run", "dir": "server", "args": ["Server", "--", "--port", "8080"] }
	]
}
```

`command` is one of `run`, `build`, `check`, `test`, `lint`, `fmt`; `dir` is the folder to run it
in, relative to the workspace folder; `args` follow the command. The tasks the extension offers by
itself are those five on the workspace folder and `run` for each application it finds.

Diagnostics a task prints are not copied to the Problems view: the language server already
reports them there.

## Not here yet

Debugging, and the project panels of the IntelliJ plugin (libraries, documentation, security
audit, services, toolchain).

## Building

```sh
npm install
npm run check      # type check
npm test           # unit tests
npm run smoke      # talks to `adm lsp` without an editor
npm run package    # adm-<version>.vsix
```

Install the package with **Extensions: Install from VSIX...**, or `code --install-extension adm-0.1.0.vsix`.
Released packages are on the [releases page](https://github.com/admlang/plugins/releases) as
`adm-vscode-<version>.vsix`.
