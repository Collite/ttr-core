# SPDX-License-Identifier: Apache-2.0
import argparse
import sys
from .client import Client
from .runtime import ApiError, dumps, execute, loads, port_key


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a recipe through the Server HTTP API")
    port = parser.add_mutually_exclusive_group(required=True)
    port.add_argument("--key", help="JSON scalar, key array or composite key object")
    port.add_argument("--batch", nargs="?", const="all", help="optional JSON keys array; no argument selects all")
    parser.add_argument("--as-of", required=True, help="now or ISO instant with offset")
    parser.add_argument("--dry-run", action="store_true", help="preview without applying writes; omit for a real run")
    args = parser.parse_args()
    try:
        if args.key is not None:
            selected = {"key": port_key(loads(args.key))}
        elif args.batch == "all":
            selected = {"none": True}
        else:
            keys = loads(args.batch)
            if not isinstance(keys, list):
                raise ValueError("batch requires an array")
            selected = {"keys": [port_key(key) for key in keys]}
        print(dumps(execute(Client(), selected, args.as_of, dry_run=args.dry_run)))
        return 0
    except ApiError as error:
        print(str(error), file=sys.stderr)
    except Exception:
        print("invalid input or configuration", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
