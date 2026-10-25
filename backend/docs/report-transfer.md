# Optional report transfer

The sender selects saved documents and creates a transfer. The sender's originals,
signatures and amendments are unchanged. A transfer contains the exact saved text
and the document's saved/signature status at the time it was created; subsequent
signing or amendments do not change an existing transfer snapshot.

## API

- `POST /api/v1/transfers`, authenticated with the existing session cookie and
  `X-CSRF-Token`: `{"document_ids":["<uuid>"]}`. Select 1–50 distinct documents
  authored by the current user in their current workspace. The complete snapshot
  is limited to 2 MiB. Returns `201` with `transfer_id`, `url`, `code`,
  `expires_at`, and `document_count`.
- `GET /receive/` serves the receiver. The returned URL has the form
  `https://<service>/receive/#<random-token>`. The fragment is deliberate: a token
  in a URL path would be captured by infrastructure access logs. The page removes
  the fragment from its current browser-history entry immediately and keeps it
  only in memory until it is redeemed or the page is left.
- `POST /api/v1/transfers/redeem`, without a session: `{"token":"...","code":"..."}`.
  Both the URL capability and its generated seven-digit code are required. A
  successful response contains `documents` with `id`, `text`, `template`,
  `version`, `content_hash`, `status`, `signed_at`, and `created_at`.
- `DELETE /api/v1/transfers/{transfer_id}`, authenticated with CSRF, revokes an
  owned transfer and clears its transfer payload. Returns an empty `204`.

The code is generated on the server, including possible leading zeroes. The
sender and recipient cannot select the code or its length. The URL token uses
32 bytes of cryptographic randomness. URLs contain neither report identifiers
nor patient information. Creation is limited to 20/hour/user; redemption is
limited to 30/minute/client IP. Each transfer additionally has a database-enforced
five-attempt limit across workers. The fifth wrong attempt clears the copy.

## Lifetime and storage

Expiry is enforced at redemption after 15 minutes. The database stores only a
SHA-256 digest of the URL token, a keyed HMAC of the PIN bound to that token, and
an authenticated encrypted snapshot. Encryption and PIN keys use separate
domains derived from the application's existing secret. Never log request
bodies or the returned URL/PIN in analytics or client diagnostics.

Successful redemption is atomic and one-time, including concurrent requests.
The ciphertext and PIN digest are cleared before the successful response is
sent. This means a network failure after consumption requires a new transfer;
the sender can create another copy without changing the original report.

Expired ciphertext is cleared in bounded batches of 100 during subsequent
creation/redemption requests. `server.transfers.clear_expired(db, limit=100)`
can also be called from maintenance and followed by a commit. On an idle service,
expired ciphertext may remain encrypted at rest until cleanup runs, but cannot
be redeemed. Do not describe this as guaranteed deletion exactly at minute 15.
Database backups retain data according to the existing backup retention policy.

The receiving browser uses no local/session storage and no third-party assets.
It renders report text as text, never HTML. It supports exact text copying and
downloads, and invokes the browser's print dialog for printing or Save PDF.
The report remains on that page until it is cleared or the user leaves it.
Downloaded files and clipboard contents are controlled by the receiving device.

## Integration

Import `server.transfers` before development schema creation. Include `router`
with `/api/v1` and `receiver_router` without a prefix, before the frontend's
catch-all route. Import `transfer_models` in Alembic's environment and apply
migration `0004` before deploying the application routes. Existing security
middleware's same-origin script policy allows `/receive/app.js`; no inline
JavaScript exception is needed.

Run `python -m pytest tests/test_transfers.py -q`. These tests use an isolated
SQLite database and fictional content, and include a four-way concurrent
redemption test with exactly one successful delivery.
