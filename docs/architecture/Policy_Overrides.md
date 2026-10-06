# Tenant Policy Overrides

Shhmods is a multi-tenant platform. While it ships with incredibly secure global defaults, every community has its own culture. What is considered spam in a financial forum might be highly encouraged behavior in a promotional community.

To support this, Shhmods provides **Tenant Policy Overrides**.

## JSON-Based Bypass Rules
Instead of just allowing communities to tweak sliders (e.g., changing a rate limit from 3 to 5), Shhmods allows tenants to write specific, behavioral JSON rules that override the core engine.

### Example: Bypassing URL Restrictions
By default, if a user with a low `SPAM` trust score attempts to post a URL, Shhmods will flag the post for `REVIEW`.

However, a tenant can inject a specific policy override:
```json
{
  "rule_name": "URL_FILTER",
  "override_action": "BYPASS",
  "condition_json": {
    "min_spam_trust": 50.0,
    "min_account_age_days": 30.0
  }
}
```

### How the Engine Evaluates It
When the user submits the URL, the Decision Engine calculates their trust scores.
1. It notices the user has a `SPAM` trust of `70.0`, which normally triggers a block.
2. Right before issuing the block, it checks the tenant's policies.
3. It sees the `URL_FILTER` bypass policy.
4. It compares the user's metrics against the JSON payload:
   * Is `spam_trust` (70.0) >= `min_spam_trust` (50.0)? Yes.
   * Is `account_age_days` (35) >= `min_account_age_days` (30.0)? Yes.
5. The block is safely aborted, and the user is allowed to post.

This allows communities to craft incredibly nuanced, forgiving rules for their trusted veterans while maintaining iron-clad defenses against new, adversarial traffic.
