#!/usr/bin/env python3
"""Mint an HS256 JWT for LOCAL DEVELOPMENT against the modular monolith.

The application validates tokens signed with the same secret in local/test mode
(app.security.jwt.secret-key). This script is a development convenience only -
production tokens are issued by the application itself (RSA, see docs/security.md).

Usage:
  python scripts/mint-local-jwt.py --sub <user-id> [--role ADMIN|USER|NONE] [--scope prometheus] [--secret SECRET] [--exp-hours 1]

Examples:
  python scripts/mint-local-jwt.py --sub 8f1c2e4a-0000-0000-0000-000000000001 --role ADMIN
  python scripts/mint-local-jwt.py --sub 8f1c2e4a-0000-0000-0000-000000000001 --role USER --exp-hours 0.1
  python scripts/mint-local-jwt.py --sub scraper-1 --role NONE --scope prometheus
"""

import argparse
import base64
import hashlib
import hmac
import json
import sys
import time

DEFAULT_SECRET = "local-dev-secret-change-me-0123456789abcdef"
DEFAULT_ROLE = "ADMIN"


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser(description="Mint an HS256 JWT for local development")
    parser.add_argument("--sub", required=True, help="JWT subject - the user id (must exist in the users table)")
    parser.add_argument("--role", default=DEFAULT_ROLE, choices=["ADMIN", "USER", "NONE"],
                        help="Role claim, or NONE to omit the role claim entirely "
                             "(e.g. a scope-only scraper token gets no ROLE_* authority)")
    parser.add_argument("--scope", default=None, help="Space-separated scope claim (e.g. prometheus for the scraper token)")
    parser.add_argument("--secret", default=DEFAULT_SECRET, help="HMAC secret matching app.security.jwt.secret-key")
    parser.add_argument("--exp-hours", type=float, default=1.0, help="Token lifetime in hours")
    args = parser.parse_args()

    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "sub": args.sub,
        "iss": "modular-monolith",
        "aud": "modular-monolith",
        "iat": now,
        "exp": now + int(args.exp_hours * 3600),
    }
    if args.role != "NONE":
        claims["role"] = args.role
    if args.scope:
        claims["scope"] = args.scope

    signing_input = (
        b64url(json.dumps(header, separators=(",", ":"), sort_keys=True).encode("utf-8"))
        + "."
        + b64url(json.dumps(claims, separators=(",", ":"), sort_keys=True).encode("utf-8"))
    )
    signature = hmac.new(args.secret.encode("utf-8"), signing_input.encode("ascii"), hashlib.sha256).digest()

    print(signing_input + "." + b64url(signature))
    return 0


if __name__ == "__main__":
    sys.exit(main())
