#!/usr/bin/env python3
"""OwnerGuard 1.0.44 Google Play Publisher automation.

Creates the lifetime Pro one-time product when missing and publishes an AAB through
Android Publisher transactional edits. Credentials are read only from an explicit
file or environment variable; secret material is never printed.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path
from typing import Any, Dict, Optional

import requests
from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

SCOPE = "https://www.googleapis.com/auth/androidpublisher"
API = "https://androidpublisher.googleapis.com/androidpublisher/v3"
UPLOAD_API = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3"
PACKAGE = "com.fantest.ownerguard"
EXPECTED_VERSION_CODE = 10044
PRODUCT_ID = "ownerguard_pro_lifetime"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Publish OwnerGuard 1.0.44 to Google Play")
    p.add_argument("--aab", type=Path, help="Signed OwnerGuard 1.0.44 AAB")
    p.add_argument("--track", default=os.getenv("OWNERGUARD_PLAY_TRACK", "internal"),
                   help="Google Play track, e.g. internal or production")
    p.add_argument("--service-account", type=Path,
                   help="Path to Google Play service-account JSON. If omitted, environment discovery is used.")
    p.add_argument("--ensure-product", action="store_true", help="Create lifetime Pro product if it does not exist")
    p.add_argument("--product-only", action="store_true", help="Only ensure/check the Pro product")
    p.add_argument("--release-status", default="completed", choices=("completed", "draft", "halted", "inProgress"))
    p.add_argument("--release-name", default="OwnerGuard 1.0.44 Pro Drive")
    p.add_argument("--price-micros", default=os.getenv("OWNERGUARD_PRO_PRICE_MICROS", "19990000"),
                   help="Lifetime Pro base price in micros; default AED 19.99")
    p.add_argument("--currency", default=os.getenv("OWNERGUARD_PRO_CURRENCY", "AED"))
    p.add_argument("--changes-not-sent-for-review", action="store_true",
                   help="Commit without sending for review. Normally leave false for release submission.")
    p.add_argument("--dry-run", action="store_true", help="Authenticate and describe intended actions without changing Play")
    return p.parse_args()


def load_credentials(args: argparse.Namespace):
    candidates = []
    if args.service_account:
        candidates.append(args.service_account)
    for name in (
        "GOOGLE_PLAY_SERVICE_ACCOUNT_FILE",
        "PLAY_SERVICE_ACCOUNT_FILE",
        "ANDROID_PUBLISHER_SERVICE_ACCOUNT_FILE",
    ):
        value = os.getenv(name, "").strip()
        if value:
            candidates.append(Path(value))
    for path in candidates:
        if path.is_file():
            return service_account.Credentials.from_service_account_file(str(path), scopes=[SCOPE]), str(path)

    for name in (
        "GOOGLE_PLAY_SERVICE_ACCOUNT_JSON",
        "PLAY_SERVICE_ACCOUNT_JSON",
        "ANDROID_PUBLISHER_SERVICE_ACCOUNT_JSON",
    ):
        value = os.getenv(name, "").strip()
        if value:
            info = json.loads(value)
            return service_account.Credentials.from_service_account_info(info, scopes=[SCOPE]), f"env:{name}"
    raise SystemExit(
        "Google Play service-account credentials were not found. Set GOOGLE_PLAY_SERVICE_ACCOUNT_FILE "
        "or GOOGLE_PLAY_SERVICE_ACCOUNT_JSON (or pass --service-account)."
    )


def api_error(response: requests.Response, label: str) -> None:
    if response.ok:
        return
    try:
        detail = response.json()
    except Exception:
        detail = response.text[:2000]
    raise RuntimeError(f"{label} failed: HTTP {response.status_code}: {detail}")


def ensure_product(session: AuthorizedSession, args: argparse.Namespace) -> Dict[str, Any]:
    url = f"{API}/applications/{PACKAGE}/inappproducts/{PRODUCT_ID}"
    current = session.get(url, timeout=45)
    if current.status_code == 200:
        product = current.json()
        return {
            "action": "existing",
            "sku": product.get("sku", PRODUCT_ID),
            "status": product.get("status"),
            "purchaseType": product.get("purchaseType"),
            "defaultPrice": product.get("defaultPrice"),
        }
    if current.status_code != 404:
        api_error(current, "Read OwnerGuard Pro product")

    payload = {
        "packageName": PACKAGE,
        "sku": PRODUCT_ID,
        "status": "active",
        "purchaseType": "managedUser",
        "defaultPrice": {
            "priceMicros": str(args.price_micros),
            "currency": str(args.currency).upper(),
        },
        "listings": {
            "en-US": {
                "title": "OwnerGuard Pro Lifetime",
                "description": "Portable encrypted Google Drive or cloud-folder backup, automatic daily backup, and disaster-recovery restore. One-time purchase.",
            }
        },
        "defaultLanguage": "en-US",
    }
    if args.dry_run:
        return {"action": "would_create", "sku": PRODUCT_ID, "defaultPrice": payload["defaultPrice"]}

    insert = session.post(
        f"{API}/applications/{PACKAGE}/inappproducts",
        params={"autoConvertMissingPrices": "true"},
        json=payload,
        timeout=60,
    )
    api_error(insert, "Create OwnerGuard Pro product")
    product = insert.json()
    return {
        "action": "created",
        "sku": product.get("sku", PRODUCT_ID),
        "status": product.get("status"),
        "purchaseType": product.get("purchaseType"),
        "defaultPrice": product.get("defaultPrice"),
    }


def insert_edit(session: AuthorizedSession) -> str:
    r = session.post(f"{API}/applications/{PACKAGE}/edits", json={}, timeout=45)
    api_error(r, "Create Play edit")
    edit_id = str(r.json().get("id", "")).strip()
    if not edit_id:
        raise RuntimeError("Android Publisher returned an edit without an id")
    return edit_id


def delete_edit(session: AuthorizedSession, edit_id: str) -> None:
    r = session.delete(f"{API}/applications/{PACKAGE}/edits/{edit_id}", timeout=45)
    if r.status_code not in (200, 204):
        api_error(r, "Delete verification Play edit")


def upload_bundle(session: AuthorizedSession, edit_id: str, aab: Path) -> Dict[str, Any]:
    if not aab.is_file() or aab.stat().st_size <= 0:
        raise RuntimeError(f"AAB does not exist or is empty: {aab}")
    url = f"{UPLOAD_API}/applications/{PACKAGE}/edits/{edit_id}/bundles"
    headers = {"Content-Type": "application/octet-stream"}
    with aab.open("rb") as handle:
        r = session.post(url, params={"uploadType": "media"}, headers=headers, data=handle, timeout=600)
    api_error(r, "Upload OwnerGuard AAB")
    bundle = r.json()
    version_code = int(bundle.get("versionCode", -1))
    if version_code != EXPECTED_VERSION_CODE:
        raise RuntimeError(f"Uploaded bundle versionCode is {version_code}; expected {EXPECTED_VERSION_CODE}")
    return {"versionCode": version_code, "sha1": bundle.get("sha1"), "sha256": bundle.get("sha256")}


def update_track(session: AuthorizedSession, edit_id: str, args: argparse.Namespace) -> Dict[str, Any]:
    release = {
        "name": args.release_name,
        "versionCodes": [str(EXPECTED_VERSION_CODE)],
        "status": args.release_status,
        "releaseNotes": [
            {
                "language": "en-US",
                "text": (
                    "OwnerGuard 1.0.44 adds optional OwnerGuard Pro lifetime purchase with portable encrypted "
                    "Google Drive/cloud-folder backup, automatic daily backup, and disaster-recovery restore. "
                    "It also carries forward the upload-pipeline reliability fixes, audited Administrator media gallery, "
                    "and targets Android 16 (API 36). Core local protection remains free."
                ),
            }
        ],
    }
    body = {"track": args.track, "releases": [release]}
    r = session.put(f"{API}/applications/{PACKAGE}/edits/{edit_id}/tracks/{args.track}", json=body, timeout=60)
    api_error(r, f"Update Play track {args.track}")
    return r.json()


def validate_edit(session: AuthorizedSession, edit_id: str) -> None:
    r = session.post(f"{API}/applications/{PACKAGE}/edits/{edit_id}:validate", json={}, timeout=60)
    api_error(r, "Validate Play edit")


def commit_edit(session: AuthorizedSession, edit_id: str, changes_not_sent_for_review: bool) -> Dict[str, Any]:
    params = {"changesNotSentForReview": "true" if changes_not_sent_for_review else "false"}
    r = session.post(f"{API}/applications/{PACKAGE}/edits/{edit_id}:commit", params=params, json={}, timeout=90)
    api_error(r, "Commit Play edit")
    return r.json()


def verify_committed_track(session: AuthorizedSession, track: str) -> Dict[str, Any]:
    """Read a fresh edit after commit and prove versionCode 10044 is on the requested track."""
    verify_edit = insert_edit(session)
    try:
        r = session.get(f"{API}/applications/{PACKAGE}/edits/{verify_edit}/tracks/{track}", timeout=60)
        api_error(r, f"Verify Play track {track}")
        body = r.json()
        releases = body.get("releases") or []
        for release in releases:
            codes = [str(code) for code in (release.get("versionCodes") or [])]
            if str(EXPECTED_VERSION_CODE) in codes:
                return {
                    "track": body.get("track", track),
                    "versionCode": EXPECTED_VERSION_CODE,
                    "status": release.get("status"),
                    "name": release.get("name"),
                    "userFraction": release.get("userFraction"),
                    "inAppUpdatePriority": release.get("inAppUpdatePriority"),
                }
        raise RuntimeError(
            f"Committed Play edit is not visible on track {track}: versionCode {EXPECTED_VERSION_CODE} not found"
        )
    finally:
        delete_edit(session, verify_edit)


def main() -> int:
    args = parse_args()
    creds, credential_source = load_credentials(args)
    session = AuthorizedSession(creds)
    evidence: Dict[str, Any] = {
        "package": PACKAGE,
        "expectedVersionCode": EXPECTED_VERSION_CODE,
        "productId": PRODUCT_ID,
        "productType": "one-time/non-consumable",
        "track": args.track,
        "credentialSource": credential_source,
        "dryRun": args.dry_run,
    }

    if args.ensure_product:
        evidence["product"] = ensure_product(session, args)

    if args.product_only:
        print(json.dumps(evidence, indent=2, sort_keys=True))
        return 0

    if not args.aab:
        raise SystemExit("--aab is required unless --product-only is used")
    if args.dry_run:
        evidence["bundle"] = {"path": str(args.aab), "bytes": args.aab.stat().st_size if args.aab.exists() else 0}
        evidence["release"] = {"status": "dry_run", "requestedTrack": args.track}
        print(json.dumps(evidence, indent=2, sort_keys=True))
        return 0

    edit_id: Optional[str] = None
    try:
        edit_id = insert_edit(session)
        evidence["editId"] = edit_id
        evidence["bundle"] = upload_bundle(session, edit_id, args.aab)
        evidence["trackUpdate"] = update_track(session, edit_id, args)
        validate_edit(session, edit_id)
        evidence["validation"] = "passed"
        evidence["commit"] = commit_edit(session, edit_id, args.changes_not_sent_for_review)
        evidence["postCommitTrack"] = verify_committed_track(session, args.track)
        evidence["playStatus"] = (
            "committed_not_sent_for_review" if args.changes_not_sent_for_review
            else "committed_and_verified_on_requested_track"
        )
    except Exception as exc:
        evidence["error"] = str(exc)
        evidence["editId"] = edit_id
        print(json.dumps(evidence, indent=2, sort_keys=True), file=sys.stderr)
        return 1

    print(json.dumps(evidence, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
