# Worker protocol v1

The worker consumes one UTF-8 JSON object on stdin, then exits after emitting one UTF-8 JSON object. No human logging belongs on stdout.

```json
{"version":1,"workspace":"/absolute/workspace","action":"state"}
```

Success:

```json
{"version":1,"ok":true,"data":{"repository":"","notes":"","evidence":[]}}
```

Failure:

```json
{"version":1,"ok":false,"error":"Open a repository first"}
```

| Action | Additional fields | Result |
| --- | --- | --- |
| state | none | Saved repository, notebook and evidence metadata |
| open | repository | Resolve Git root, save it, enumerate files and status |
| files | none | Refresh file listing and tracked-file status |
| read | path | Bounded text preview and text digest |
| history | none | Recent commits across refs |
| diff | sha | Commit metadata, stat and patch |
| search | query | Literal hits with path and line, plus truncation/skipped counters |
| capture | kind, source, content | Persist evidence and return ID/digest |
| evidence | id | Read an immutable captured snapshot |
| notes | body | Save notebook text |
| export | path | Create a new standalone HTML report |

The protocol is local and receives full filesystem paths by design. It is not an authenticated remote API. Do not expose it as a network service without a separate threat model.
