# Graph Coordination Engine

One of the most difficult challenges in modern community moderation is defending against coordinated attacks—such as botnets spamming the exact same payload across hundreds of accounts, or discord servers organizing "brigades" to mass-report a specific user.

Shhmods handles these adversarial behaviors natively using **Graph Coordination Analytics**.

## How It Works

Instead of looking at isolated posts, Shhmods analyzes the relational graph of how users interact with content and each other over rolling time windows.

Because analyzing millions of graph edges on every single web request would crash the database, Shhmods leverages **PostgreSQL Materialized Views** coupled with asynchronous background workers.

### 1. Content Clusters (Spam Rings)
A background worker continuously scans the last hour of content to find pairs of users who are posting the exact same content hashes.
If `User A` and `User B` post the same hash, an edge is created between them. 
If they share 2 or more hashes, the system identifies them as a highly-suspicious cluster. When any user in that cluster attempts to post again, the Decision Engine intercepts it and applies a severe `COORDINATION` penalty.

### 2. Coordination Clusters (Brigading / Swatting)
Similarly, the system analyzes the `REPORT` table. If 5 different users all report the exact same target within a 24-hour window, the engine draws edges between those reporters.
If they share targets frequently, they are identified as a swarm. Their reports are subsequently de-weighted, protecting innocent users from malicious mass-reporting campaigns.

## The Advantage
By clustering actors based on their shared behavioral patterns, Shhmods can identify and neutralize a botnet even if the botnet is using brand new IP addresses and highly-aged accounts. The mathematical footprint of coordination is impossible to hide.
