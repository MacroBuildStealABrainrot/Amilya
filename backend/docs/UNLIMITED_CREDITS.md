# Account-specific unlimited credits

Operators may grant unlimited application credits with `UNLIMITED_CREDIT_USER_IDS`, a comma-separated list of existing immutable user IDs in protected server configuration. An empty value grants no additional exemptions. Signup email, profile fields and client requests cannot create this entitlement. Platform administrators retain their existing exemption.

The entitlement bypasses subscription and credit-balance checks for that user only. It does not grant platform administrator access or affect other workspace members. The billing response exposes `is_unlimited`, and the pricing screen labels this as “Unlimited credits”. No artificial paid subscription, purchased balance, or Stripe transaction is created.

Provider reservations snapshot the exemption in the existing `usage_operations.admin_exempt` column. This legacy column now represents either an owner or explicit account credit exemption. Normalized provider costs and actual costs, when available, remain recorded; the user is charged zero credits. Removing the configured ID affects new reservations; already reserved operations preserve their original charging terms.

Unlimited application credits do not activate providers, satisfy provider account requirements or pay external provider invoices. Execution and clinical-data gates remain independent. Deployment revision history records configuration changes; operators should retain the grant/revocation reason with the release record.

Regression coverage verifies unlimited processing without a subscription, zero charges with preserved costs, administrator rejection, isolation from other accounts, exact-ID matching, revocation and settlement of already-reserved work.
