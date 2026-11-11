# Gitleaks 8.30.1 (finding secrets before they are committed)

## What it is

A **secret** is a piece of text that grants access: a password, an API key, a token, a private key. If a secret is committed to Git, everyone who can read the history can read it, even after the file is deleted in a later commit, because Git keeps old versions. **Gitleaks** is a command-line program that scans for secrets. It reads files, or the commits of a Git history, and compares the text with a list of **rules**. A rule describes what one kind of secret looks like: a pattern for an access-key format, the header line of a private key, a file type that holds keys. When a rule matches, Gitleaks reports a **finding** with the rule name, the file and the line.

Gitleaks is a separate program. It is not a library, it is not in `build.gradle.kts`, and no file of this repository calls it.

## Why this repository uses it

I have to be exact here: **nothing in this repository runs Gitleaks.** There is no configuration file for it, no script that calls it, and no Gradle task. The README and the other files do not name it. It belongs to the habit around the repository: this project is one of many that are checked before they are published. The vetting step before a commit or a push is `gitleaks git --staged` on the folder (the course's diagram-kit instructions, which sit one folder above this repository, show that exact command). This note exists because it is the tool that protects the one risky thing this repository does: its build makes private keys and a password.

That risk is real. `./gradlew testCertificates` writes, into `build/tls`: two PKCS12 key stores (`seatwave-server.p12`, `seatwave-test-ca.p12`), a PEM private key (`server-key.pem`) and a password file (`store.password`) (`gradle.md`, section "The certificate task"). They are throwaway test keys for a lab, but a habit of committing keys is the habit that leaks real ones. The repository protects itself in two layers: `.gitignore` keeps these files out of Git (`git.md`), and a scanner is the second check, in case the first layer fails.

## Where it appears

| Place | What it does with Gitleaks |
| --- | --- |
| This repository's files | Nothing. No file references it. |
| `.gitignore` | The first layer: the patterns `build/`, `*.p12`, `*.jks`, `*.pem`, `*.key`, `*.crt` and `*.password` that keep keys and passwords untracked (`git.md`). |
| `build.gradle.kts` | The code that makes the keys never prints the password and writes it to a file instead of a command line (`gradle.md`). |
| The course's tooling, outside the repository | The recipe `gitleaks git --staged <repo>` before every commit that publishes work. |
| Your machine | The program, installed once. On the machine of these notes: `/opt/homebrew/bin/gitleaks`, version 8.30.1 (`gitleaks version`). |

## How it works underneath

1. Gitleaks reads text. For `gitleaks git` it asks Git for the commits (it runs `git log -p` behind the scenes) and reads the **added lines** of every commit patch. For `gitleaks git --staged` it reads only the changes that are staged (added with `git add`) and not yet committed. For `gitleaks dir` it walks a folder and reads every file in it, ignoring Git.
2. Each text is checked against the built-in rules (a long list that ships with the program). Some rules look for a **file name or type** (a PKCS12 key store), some for a **pattern in the text** (`-----BEGIN PRIVATE KEY-----`), some for a **high-entropy string** next to a word such as `password` or `token`. Entropy is a measure of randomness: a random-looking string is more likely a secret than an English word.
3. A match becomes a finding. Gitleaks prints how many commits and bytes it scanned and then either `no leaks found` or `leaks found: N`.
4. The **exit status** (the number a program leaves behind for the shell, 0 for success) is 0 when nothing is found and 1 when something is found. A script or a Git hook can stop on 1.
5. `--redact` replaces the secret itself with `REDACTED` in the output, so the scan does not print the very thing it found. `--report-format json --report-path file.json` writes the findings to a file.
6. The scan reads local files and Git objects. I ran every command below with no network access needed; Gitleaks printed no download or update line.

## Walkthrough of this repository's use

Since no file of the repository calls Gitleaks, this walkthrough runs it on a scratch copy of the repository and reads what it says. Every command ran on 11 November 2026 with Gitleaks 8.30.1. The scratch copy had a full build, so `build/tls` held the real throwaway keys.

### The history is clean

```
gitleaks git --no-banner --redact .
```

Output (colors removed): `1 commits scanned.`, `scanned ~442966 bytes (442.97 KB) in 87.5ms` and `no leaks found`. The exit status was 0. The repository has one commit, and no commit adds a key or a password.

### The tracked source folders are clean

```
gitleaks dir --no-banner --redact config
```

Exit status 0. I ran the same command for `src`, `gradle`, `nginx` and `scripts` and each also left status 0. These folders hold the configuration, the code, the Gradle files, the NGINX configuration and the scripts, so none of them contains a secret that a rule recognizes. (`gitleaks dir` takes **one** path; with several paths my first try left status 1 without any finding, and one path at a time worked.)

### The build folder is not clean, and that is why it is ignored

```
gitleaks dir --no-banner --redact build/tls
```

Exit status 1, `leaks found: 3`. I wrote the findings to a JSON file with `--report-format json --report-path` and read only the rule, the file and the line:

| Rule | File | Line |
| --- | --- | --- |
| `pkcs12-file` | `build/tls/seatwave-test-ca.p12` | 0 |
| `private-key` | `build/tls/server-key.pem` | 1 |
| `pkcs12-file` | `build/tls/seatwave-server.p12` | 0 |

Gitleaks is right: those files are key material. They are harmless only because they are throwaway keys of a lab and because `build/` is in `.gitignore`. A `gitleaks dir` scan of the whole working tree reports these three; a `gitleaks git` scan does not, because Git never sees an ignored file. That difference is the whole reason for having both layers.

### What happens when a key is staged by mistake

In the scratch copy only, I copied `build/tls/server-key.pem` to `keytest.pem` in the project folder. `.gitignore` ignores `*.pem`, so a plain `git add` refuses it; I forced it with `git add -f keytest.pem`, which is exactly the kind of override the second layer is for. Then:

```
gitleaks git --staged --no-banner --redact .
```

Output: `0 commits scanned.`, `scanned ~148 bytes (148 bytes)` and `leaks found: 1`, with exit status 1. The 148 bytes are the size of `server-key.pem`. I removed the file from the index with `git reset` and deleted the copy. A hook that runs this command before each commit would have refused the commit.

### The commands you will use

| Command | What it scans |
| --- | --- |
| `gitleaks git .` | Every commit of the repository in the current folder. |
| `gitleaks git --staged .` | Only the changes that are staged for the next commit. |
| `gitleaks dir <path>` | The files in one folder, tracked or not. |
| `--redact` | Hides the secret in the output. Always use it on a shared screen or a log. |
| `--no-banner` | Leaves out the start banner. |
| `--report-format json --report-path <file>` | Writes the findings to a file you can read with a program. |
| `--exit-code <n>` | Sets the exit status used for a leak (the default is 1). |
| `--baseline-path <file>` | Ignores findings that are already known and accepted. |
| `.gitleaksignore` | A file in the scanned folder that lists findings to ignore, by fingerprint. |

## Try it yourself

All commands ran in a scratch copy of the repository on 11 November 2026, after `./gradlew testCertificates`. Never print the files in `build/tls`.

1. Check the program and its version:

```
gitleaks version
```

   Expected: `8.30.1`.

2. Scan the history:

```
gitleaks git --no-banner --redact .
echo "exit=$?"
```

   Expected: `no leaks found` and `exit=0`.

3. Scan one folder of source:

```
gitleaks dir --no-banner --redact config
echo "exit=$?"
```

   Expected: `no leaks found` and `exit=0`.

4. Scan the build folder, and read only the rule names:

```
gitleaks dir --no-banner --redact --report-format json --report-path ../report.json build/tls
python3 -c "import json; [print(x['RuleID'], x['File'], x['StartLine']) for x in json.load(open('../report.json'))]"
```

   Expected: exit status 1, `leaks found: 3`, and the three rows of the table above (`pkcs12-file`, `private-key`, `pkcs12-file`). The first path in each row is the path of your checkout.

5. Stage a key on purpose, in a scratch copy only, and see the scan refuse it:

```
cp build/tls/server-key.pem keytest.pem
git add -f keytest.pem
gitleaks git --staged --no-banner --redact .
git reset -q keytest.pem
rm keytest.pem
```

   Expected: `leaks found: 1` and exit status 1 for the scan; after the last two commands `git status --short` shows nothing for that file.

## Mistakes people make

- **Thinking `.gitignore` is a security tool.** It only stops `git add` of a plain path. `git add -f` overrides it. A scanner is the check that does not trust the first layer.
- **Printing the secret while looking for it.** Always scan with `--redact`, and read a JSON report by rule and file only.
- **Removing a leaked key in the next commit.** The old commit still holds it. A leaked key has to be revoked, not deleted. For a real key, tell the owner of the service and replace it.
- **Scanning only the working tree.** `gitleaks dir` does not see history, and `gitleaks git` does not see ignored files. Use the one that matches the question.
- **Ignoring a finding without a reason.** A `.gitleaksignore` entry or a baseline hides a problem for ever. Write the reason next to it.
- **Believing a clean scan means no secret.** Rules recognize known shapes. A password that looks like an ordinary word can pass. Design so that secrets are never in the repository, as the build here does (a random password in a file, no password on a command line).
- **Passing several paths to `gitleaks dir`.** It takes one.

## Check yourself

1. *What does Gitleaks look for?* Text that matches a rule for a kind of secret: a key header, a key file type, a token pattern, or a random-looking string next to a word like `password`.
2. *What exit status means a leak was found?* 1 by default; 0 means none.
3. *Why does `gitleaks dir build/tls` find three leaks but `gitleaks git` finds none?* The files exist on disk but Git never recorded them, because `.gitignore` excludes `build/`.
4. *What does `--redact` do?* It hides the secret value in the output.
5. *Which command checks only what you are about to commit?* `gitleaks git --staged .`

## Further reading

- Gitleaks project, usage and rule list (8.x): <https://github.com/gitleaks/gitleaks>
- Git `add -f` and ignored files: <https://git-scm.com/docs/git-add>
- gitignore patterns: <https://git-scm.com/docs/gitignore>
- Git `log -p` (the patches Gitleaks reads): <https://git-scm.com/docs/git-log>
