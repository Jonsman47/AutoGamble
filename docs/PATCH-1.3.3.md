# AutoGamble 1.3.3 — faster minimum balance filtering

When Minimum Payment Balance is positive, client ticks now collect recipients from the existing random `/pay` prefix suggestions and check up to 100 names per suggestion batch against locally scanned balances. Accepted names enter a memory only queue of up to 20. Suggestions are throttled to at most one request every 200 ms and never overlap. Payment deadlines consume a ready name without running discovery first; when the queue is empty, discovery continues and the pending payment resumes as soon as one qualifies.

Queue entries expire after 30 seconds, and scanned balances are accepted for at most six hours after observation. When no cached player can meet the threshold, a local check every five seconds avoids pointless suggestion requests. Old or unknown balances require a new manual scan. The queue is cleared when the session ends or the payment settings change. A compact preview appears in Auto Pay settings. Minimum Payment Balance = 0 keeps the 1.2.4 suggestion path without queue or balance lookups. Baltop recipient modes remain inactive; the saved scan provides balance evidence only.

Automated tests cover filtering, queue bounds, duplicates, expiry, refill, empty queue recovery, and configured payment timing. Live DonutSMP timing still needs in game verification.
