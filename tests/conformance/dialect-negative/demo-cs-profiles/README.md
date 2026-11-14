# dialect-negative/demo-cs-profiles

MW-P3 S3.7 T1 (phase DONE 1): the profile-level `ys/` codes no other demo-cs fixture raises, one file each, in one
dialect tree served by `ttr-metadata-cli lint`. `ttr.yaml` names `profiles/ok.yaml`; a file with its own `ttr:` header
names the profile that fails:

| file | code |
|---|---|
| `invalid.yaml` → `profiles/invalid.yaml` | `ys/profile-invalid` (an unknown `op`) |
| `mismatch.yaml` → `profiles/old.yaml` | `ys/profile-grammar-mismatch` (`>=0.1 <0.2`) |
| `not-found.yaml` → `profiles/missing.yaml` | `ys/profile-not-found` |
| `cycle.yaml` → `profiles/cycle-a.yaml` ↔ `cycle-b.yaml` | `ys/profile-cycle` |
| `unmapped.yaml` (tree profile) | `ys/value-unmapped` (`faktura` has no mapping and no default) |
| `canonical-bad.yaml` | `ys/canonical-invalid` (`defs: 5`) |
| `profiles/dup-1.yaml` + `dup-2.yaml` | `ys/profile-duplicate` (a warning: both declare `demo-dup/1`) |

`expected-github.txt` is `lint --root . --strict --format github` run inside `tree/`. Exit 1. The index of every demo-cs
fixture and the code it raises is `../../demo-cs-index.json`.
