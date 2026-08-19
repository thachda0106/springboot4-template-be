#!/usr/bin/env python3
"""Mint an RS256 JWT signed with the application's production RSA private key.

Production tokens are issued by the application itself (RSA, see docs/security.md),
and the Prometheus scraper token lives in the same trust domain: an operator mints it
offline with the application's private key (app.security.jwt.private-key). Prometheus
only ever holds the resulting bearer token - the private key must never be given to it.

The signature is produced by the openssl CLI (a git-bash prerequisite, like curl):
`openssl dgst -sha256 -sign` emits the raw RSASSA-PKCS1-v1_5 signature that RS256
expects, so the token validates with the application's Nimbus decoder.

Usage:
  python scripts/mint-rsa-jwt.py --sub <scraper-id> --scope prometheus \
      --key-file <private-key.pem> --verify-with <public-key.pem>
  python scripts/mint-rsa-jwt.py --sub <user-id> --role ADMIN --key-file <private-key.pem>

PKCS#1 (BEGIN RSA PRIVATE KEY) and PKCS#8 (BEGIN PRIVATE KEY) PEM files are accepted.
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import tempfile
import time

DEFAULT_ROLE = "NONE"  # scraper tokens carry no role -> no ROLE_* authority
DEFAULT_EXP_HOURS = 720.0  # 30 days; re-mint and redeploy before expiry to rotate


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def b64url_json(payload: dict) -> str:
    return b64url(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode("utf-8"))


def sign_rs256(signing_input: str, key_file: str) -> bytes:
    proc = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", key_file],
        input=signing_input.encode("ascii"),
        capture_output=True,
        check=False,
    )
    if proc.returncode != 0:
        sys.stderr.write("ERROR: openssl signing failed:\n" + proc.stderr.decode("utf-8", "replace"))
        sys.exit(1)
    return proc.stdout


def verify_rs256(signing_input: str, signature: bytes, pub_key_file: str) -> bool:
    # mkstemp, not NamedTemporaryFile: on Windows the latter's O_TEMPORARY flag
    # prevents the openssl subprocess from opening the file.
    fd, sig_path = tempfile.mkstemp(prefix="jwt-sig-")
    try:
        with os.fdopen(fd, "wb") as sig_file:
            sig_file.write(signature)
        proc = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", pub_key_file, "-signature", sig_path],
            input=signing_input.encode("ascii"),
            capture_output=True,
            check=False,
        )
        return proc.returncode == 0
    finally:
        os.unlink(sig_path)


def main() -> int:
    parser = argparse.ArgumentParser(description="Mint an RS256 JWT with the application's RSA private key")
    parser.add_argument("--sub", required=True, help="JWT subject (scraper id or user id)")
    parser.add_argument("--role", default=DEFAULT_ROLE, choices=["ADMIN", "USER", "NONE"],
                        help="Role claim, or NONE to omit the role claim entirely "
                             "(a scope-only scraper token gets no ROLE_* authority)")
    parser.add_argument("--scope", default=None,
                        help="Space-separated scope claim (e.g. prometheus for the scraper token)")
    parser.add_argument("--iss", default="modular-monolith",
                        help="Issuer claim - must match app.security.jwt.issuer")
    parser.add_argument("--aud", default="modular-monolith",
                        help="Audience claim - must match app.security.jwt.audience")
    parser.add_argument("--key-file", required=True,
                        help="Path to the RSA private key PEM (PKCS#1 or PKCS#8)")
    parser.add_argument("--verify-with", default=None,
                        help="Path to the RSA public key PEM to verify the token signature locally")
    parser.add_argument("--exp-hours", type=float, default=DEFAULT_EXP_HOURS,
                        help="Token lifetime in hours (default 720 = 30 days)")
    args = parser.parse_args()

    try:
        with open(args.key_file, "r", encoding="utf-8") as key_file:
            pem_text = key_file.read()
    except OSError as e:
        sys.stderr.write("ERROR: cannot read key file %s: %s\n" % (args.key_file, e))
        return 1
    if "BEGIN" not in pem_text:
        sys.stderr.write("ERROR: %s does not look like a PEM private key\n" % args.key_file)
        return 1

    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    claims = {
        "sub": args.sub,
        "iss": args.iss,
        "aud": args.aud,
        "iat": now,
        "exp": now + int(args.exp_hours * 3600),
    }
    if args.role != "NONE":
        claims["role"] = args.role
    if args.scope:
        claims["scope"] = args.scope

    signing_input = b64url_json(header) + "." + b64url_json(claims)
    signature = sign_rs256(signing_input, args.key_file)
    token = signing_input + "." + b64url(signature)

    if args.verify_with:
        if not verify_rs256(signing_input, signature, args.verify_with):
            sys.stderr.write("ERROR: token signature does NOT verify against %s\n" % args.verify_with)
            return 1
        sys.stderr.write("Signature verified against %s\n" % args.verify_with)

    print(token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
