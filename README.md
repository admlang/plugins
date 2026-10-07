# ADM IDE plugins

Official editor support for the [ADM](https://github.com/admlang/adm) language. Both plugins run
the `adm` program installed on the machine: `adm lsp` for language features, and the commands'
`--json` output for everything else, so a plugin shows what the compiler itself computes.

| Plugin | Editors | What it adds |
|---|---|---|
| [`intellij`](intellij/) | IntelliJ IDEA, CLion and other JetBrains IDEs, 2025.2 and later | Language features, run and debug, and the ADM tool window: project info, libraries, documentation, tests with coverage, security audit, lint, live services, toolchain |
| [`vscode`](vscode/) | Visual Studio Code 1.88 and later | Language features, syntax colouring, check suites in the Testing view, commands and tasks for `adm run`, `build`, `check`, `test`, `lint` and `fmt` |

Language features are those of the language server: diagnostics, completion, hover, signature
help, go to definition and implementations, references, rename, formatting, code actions,
symbols, semantic highlighting and inlay hints.

## Install

Install ADM first:

```bash
curl -fsSL https://raw.githubusercontent.com/admlang/adm/main/install.sh | sh
```

The IntelliJ plugin is attached to each [ADM release](https://github.com/admlang/adm/releases/latest)
as `adm-intellij.zip`: **Settings → Plugins → ⚙ → Install Plugin from Disk...**.

The Visual Studio Code extension is on the
[Marketplace](https://marketplace.visualstudio.com/items?itemName=admlang.adm): search for "ADM" in
the Extensions view, or run `code --install-extension admlang.adm`. The same build is attached to
each ADM release as `adm-vscode.vsix` for **Extensions: Install from VSIX...**.

Both are also released here, each under its own version and with the version in the file name
([releases](https://github.com/admlang/plugins/releases)).

## Build

```bash
(cd intellij && ./gradlew buildPlugin)      # build/distributions/adm-intellij-<version>.zip
(cd vscode && npm install && npm run package)   # adm-<version>.vsix
```

[`vscode/README.md`](vscode/README.md) lists the extension's settings and tests.

## License

[Apache 2.0](vscode/LICENSE).
