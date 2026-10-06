# Trust and Decay Model

Shhmods abandons traditional point-based reputation systems in favor of **Temporal Trust Decay**.

In most platforms, if a user is penalized for spamming, an admin has to manually unban them, or a cron job has to run every night to increment their score. Shhmods handles this mathematically on the fly.

## Trust Compartmentalization
Trust isn't a single number. A user might be a wonderful, engaging community member who occasionally posts links that look like spam. Therefore, trust is divided into strict compartments:
*   **SPAM:** Posting repetitive links, extreme burst rates, or identical hashes.
*   **HARASSMENT:** Using banned keywords, toxic behavior, or targeted attacks.
*   **COORDINATION:** Participating in botnets, swarm reporting, or brigading.

Every user effectively starts with a score of `100.0` in each compartment.

## The Append-Only Ledger
When a user commits a violation, the Decision Engine does not overwrite their score. Instead, it issues a **Trust Event**.

A Trust Event is a permanent record consisting of:
1.  **Compartment:** e.g., SPAM
2.  **Penalty:** e.g., 25.0 points
3.  **Decay Rate:** e.g., 0.0000005 (determines the half-life of the penalty)
4.  **Timestamp:** The exact moment the violation occurred.

## Continuous Exponential Decay
When the system needs to know a user's score, it calculates it in real-time using a PostgreSQL view. The formula applies exponential decay to all historical penalties based on the time elapsed since the infraction:

```sql
GREATEST(0.0, 100.0 - SUM( penalty * exp(-decay_rate * seconds_elapsed) ))
```

### What does this mean in practice?
*   **Immediate Consequence:** If a user is penalized 30 points, their effective trust drops to 70 instantly.
*   **Gradual Forgiveness:** As seconds tick by, the `exp()` function naturally shrinks that 30-point penalty. A week later, it might only weigh 15 points. A month later, it might weigh 1 point.
*   **Permanent Scars:** If a user commits a horrific violation, an admin can issue a penalty with a `decay_rate` of `0`. This means the penalty never shrinks; the scar is permanent.

Because it calculates continuously, a user who is temporarily restricted will automatically regain their privileges the moment their decayed score crosses the threshold, without any batch jobs or admin intervention.
