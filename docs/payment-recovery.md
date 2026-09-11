# Legacy payment receipt recovery

This page describes file receipts created by the earlier payment-recovery update. New exchanges
now use the database journal described in [Auction reliability and recovery](RECOVERY.md).
Existing file receipts retain the retry and reconciliation behavior below; do not move their
obligations into the database journal or replay the original purchase. The remaining-crash-window
section documents the earlier implementation, not the current database reservation flow.

Payment receipts live in `payments/pending/<UUID>.properties`; closed receipts move to `payments/receipts/`. The leg records contain the recipient/account UUID, amount, direction, and result. Files are written using a flushed temporary file and atomic rename on the same filesystem. Unsupported atomic writes fail closed. This is not a transaction spanning Vault, player storage, and the auction database, nor a guarantee against every storage/power failure.

- `SUCCESS`: the provider acknowledged the leg. Do not pay it again.
- `REJECTED`: the provider explicitly declined it. This assumes a conforming provider did not move the money despite returning failure.
- `IN_FLIGHT`: the call or result write was interrupted; the provider may already have moved money. Never automatically replay or invert this leg.
- An intent with no leg result also remains for inspection; it is not proof of a completed transaction.

The [Vault economy API](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/Economy.java) offers individual withdrawals/deposits, not a cross-provider transaction or idempotency key. A generic retry library cannot resolve an unknown payment outcome. Existing local storage is retained; no extra runtime library/service is installed.

## Operator reconciliation

Stop the server normally and back up the plugin data, player data, and economy provider's ledger. Inspect the receipt, server log, and provider history together. Determine which legs actually moved money before applying compensation. Record the resolution and preserve the original receipt; do not simply delete pending records or repeat a purchase/trade. If the provider history cannot establish an unknown leg's outcome, keep the hold and escalate instead of guessing.

## Auctions behavior

Seller payouts (buy-now and auction expiry), outbid refunds, and failed-listing fee refunds pass through the payment journal. Known rejected credits are retried on the main thread every 30 seconds, up to ten per pass with rotation so later records are not starved. Confirmed credits are not replayed. `IN_FLIGHT` outcomes are held for staff, including after restart. Successful-credit audits run only after payment confirmation; audit delivery remains best effort.

Buyer delivery can finish while a seller credit is pending, because the confirmed buyer debit funds a retained seller obligation. Do not re-charge the buyer or restore/re-sell the listing to solve an unpaid credit. Existing online sellers/refund recipients receive a pending-payment message; ordinary paid-sale/refund notifications are withheld while payment is pending. A later successful retry is recorded in the payment receipt and audit, without replaying the original sale notification.

After resolving an unknown credit from provider history, archive its receipt only after verifying the owed credit was satisfied once. Never change IN_FLIGHT to REJECTED solely to force a retry. Receipt IDs are stable per sale/refund source; preserve completed receipts to prevent duplicate credits. Keep backups and monitor receipt storage growth; automatic receipt pruning is not enabled.

## Verification boundary and remaining crash windows

This fixes known rejected credits and prevents blind retries of unknown **credit-call** outcomes. It does not make existing asynchronous listing/bid database transitions, buyer debits, item collection writes, and the new journal one atomic transaction. A crash before a credit intent is written (or a storage failure that prevents it) still needs reconciliation from the listing/provider records and severe server logs. Concurrent bid funding/expiry and collection-delivery crash windows remain separate work. No database schema or existing listing statuses are changed in this patch.

The current service tests drive purchases, bidding, expiry, and listing reservation failure through
the public service API with real SQLite and a rejecting economy. They also cover upgrade retries
of old receipts and preservation of unknown outcomes. Separate receipt tests retain duplicate-credit,
audit-failure, retry-limit, write-failure and corruption coverage. They do not prove full crash atomicity.
