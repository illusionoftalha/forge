# Architecture and tradeoffs

## Ownership

The desktop owns interaction, process lifetime, and the currently displayed evidence. The Python worker owns workspace persistence and repository reads. The JVM analyzer remains in Java and has no UI dependency.

Each operation launches one Python process, writes one JSON request to stdin, closes stdin, and reads one response. Startup overhead is accepted in exchange for straightforward isolation and cancellation. Protocol v1 intentionally avoids a daemon, network socket, authentication layer, and multiplexed job state.

## Persistence

`forge.sqlite` contains settings, a notebook, append-only evidence, a source cache and the last nonempty index change set. Opening a schema 1 workspace creates the index tables and advances `user_version` to 2 in a transaction. A newer workspace version is rejected. Captures retain a source, kind, timestamp, and SHA-256 over captured text.

There is currently no evidence deletion, editing, deduplication, or cross-workspace sync. Switching repositories within a workspace keeps previous evidence; source references distinguish it. Use separate workspaces for unrelated investigations.

## Git boundary

Commands use argument arrays without a shell. Commit inspection accepts only a full hexadecimal object ID. Paging, fsmonitor hooks, signature verification, external diff drivers, and text conversion are disabled. Output goes to a bounded temporary file and a timeout terminates the process. File enumeration uses Git's tracked/untracked listing with ignore rules instead of recursively traversing arbitrary directory trees.

File reads reject absolute paths, `..`, symlink components, non-files, binary content, and oversized previews. These checks reduce accidental exposure; they are not a sandbox against another process racing filesystem replacement. Git metadata and the selected repository are trusted enough for ordinary local inspection.

## Desktop jobs

Operations run in SwingWorker. Widgets are updated on the event-dispatch thread. A single active operation avoids simultaneous UI ownership. Cancel terminates the active worker process tree; its completion is suppressed. A cancellation can arrive after a small SQLite transaction has committed, so it does not imply rollback of previously saved data.

Worker stdout is redirected to a temporary file, avoiding pipe deadlocks. Request input closes before the desktop waits. The bridge limits response size and lifetime. Export serializes a database snapshot and escapes all dynamic HTML text; output creation is exclusive.

## Cost and future direction

One transaction owns each source-cache refresh. Forge lists eligible files through Git, compares size, timestamps and file identity, then rereads changed entries. A forced rebuild bypasses the metadata check. Source and change rows commit together; a killed worker rolls back through SQLite recovery when the workspace reopens. A single workspace caches one repository at a time. Search streams one cached document at a time and compares case-folded text; there is no inverted or symbol index.

The reproducible workload in `python scripts/benchmark_index.py` creates 1,000 files totaling 17,000,000 bytes. On Windows 11 with Python 3.14.4 (Intel family 6, model 60), a traced run took 15.55 s cold, 1.11 s unchanged and 1.10 s after one edit. It reread 1,000, zero and one file respectively. Peak traced Python allocations were 4.14 MiB or less in each pass; this excludes native SQLite memory and the OS cache. Timings are a sample, not a latency guarantee.

The desktop currently uses a small JSON codec tested at the protocol boundary. If the protocol grows substantially, replace it with a maintained library rather than extending it into a general serialization framework.
