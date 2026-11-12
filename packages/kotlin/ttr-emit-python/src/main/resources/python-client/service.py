# SPDX-License-Identifier: Apache-2.0
"""One JSON request per subprocess; calls the generated typed HTTP client."""
import sys
from .client import Client
from .runtime import ApiError, dumps, execute, loads


def main() -> int:
    try:
        request = loads(sys.stdin.read())
        result = execute(Client(), request["port"], request.get("as_of"),
                         dry_run=request.get("dry_run", False), parameters=request.get("parameters"))
        print(dumps(result))
        return 0
    except (ApiError, ValueError) as error:
        # Values/HTTP bodies/tokens are never copied into diagnostics.
        message = str(error) if isinstance(error, ApiError) else "invalid input or replay parameter"
        print(dumps({"error": message}))
        return 2
    except Exception:
        print(dumps({"error": "generated service failed"}))
        return 2


if __name__ == "__main__":
    sys.exit(main())
