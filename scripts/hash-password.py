#!/usr/bin/env python3
"""Generate a BCrypt password hash for manual user provisioning/recovery.

The application stores BCrypt hashes (72-byte input limit). Python's stdlib has no
bcrypt, so this tries, in order:
  1. the Apache `htpasswd` tool (htpasswd -bnBC 10 "" <password>);
  2. the Python `bcrypt` package (pip install bcrypt).

Usage:
  python scripts/hash-password.py <password>
"""

import subprocess
import sys


def via_htpasswd(password: str) -> str | None:
    try:
        out = subprocess.run(
            ["htpasswd", "-bnBC", "10", "", password],
            capture_output=True, text=True, check=True,
        ).stdout.strip()
        return out.split(":", 1)[1] if ":" in out else out
    except (FileNotFoundError, subprocess.CalledProcessError):
        return None


def via_bcrypt_module(password: str) -> str | None:
    try:
        import bcrypt
        return bcrypt.hashpw(password.encode("utf-8"), bcrypt.gensalt(10)).decode()
    except ImportError:
        return None


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: python scripts/hash-password.py <password>", file=sys.stderr)
        return 2
    password = sys.argv[1]
    if len(password.encode("utf-8")) > 72:
        print("Password exceeds BCrypt's 72-byte limit", file=sys.stderr)
        return 2

    for label, fn in (("htpasswd", via_htpasswd), ("bcrypt module", via_bcrypt_module)):
        result = fn(password)
        if result:
            print(result)
            return 0

    print("No bcrypt-capable tool found. Install one of:", file=sys.stderr)
    print("  - Apache htpasswd, or", file=sys.stderr)
    print("  - pip install bcrypt", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
