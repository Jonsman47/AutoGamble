package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.config.AutoGambleConfig;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;
import org.slf4j.LoggerFactory;

public final class PaymentSpamTracker {
   private final Map<String, PaymentSpamTracker.State> players = new HashMap<>();
   private final Deque<PaymentSpamTracker.Warning> pending = new ArrayDeque<>();
   private long retryAt;

   public void observe(String name, long now, AutoGambleConfig c) {
      if (c.enabled && c.gambleEnabled && c.spamPaymentWarningEnabled) {
         this.expire(now, c);
         String key = name.toLowerCase(Locale.ROOT);
         if (this.players.containsKey(key) || this.players.size() < 4096) {
            PaymentSpamTracker.State s = this.players.computeIfAbsent(key, k -> new PaymentSpamTracker.State());
            s.times.addLast(now);

            while (s.times.size() > 20) {
               s.times.removeFirst();
            }

            if (s.times.size() >= c.spamPaymentThreshold && (s.warned == null || now - s.warned >= c.spamWarningCooldownSeconds * 1000000000L)) {
               if (this.pending.size() < 256 && !this.pending.stream().anyMatch(w -> w.name.equalsIgnoreCase(name))) {
                  s.warned = now;
                  this.pending.addLast(new PaymentSpamTracker.Warning(name, now));
                  LoggerFactory.getLogger("autogamble")
                     .info("[AutoGamble] Payment spam detected from {}: {} payments in {}s", new Object[]{name, s.times.size(), c.spamPaymentWindowSeconds});
               }
            }
         }
      }
   }

   public void expire(long now, AutoGambleConfig c) {
      this.players.values().forEach(s -> {
         while (!s.times.isEmpty() && now - s.times.peekFirst() > c.spamPaymentWindowSeconds * 1000000000L) {
            s.times.removeFirst();
         }
      });
      this.players.values().removeIf(s -> s.times.isEmpty() && (s.warned == null || now - s.warned >= c.spamWarningCooldownSeconds * 1000000000L));
      this.pending.removeIf(w -> now - w.at > 30000000000L);
   }

   public int size() {
      return this.players.size();
   }

   public int pendingCount() {
      return this.pending.size();
   }

   public void tick(long now, AutoGambleConfig c, BiPredicate<String, String> sender) {
      if (c.enabled && c.gambleEnabled && c.spamPaymentWarningEnabled) {
         this.expire(now, c);
         if (!this.pending.isEmpty() && now - this.retryAt >= 0L) {
            PaymentSpamTracker.Warning job = this.pending.peekFirst();
            if (sender.test(job.name, c.spamWarningMessage)) {
               this.pending.removeFirst();
               PaymentSpamTracker.State state = this.players.get(job.name.toLowerCase(Locale.ROOT));
               if (state != null) {
                  state.warned = now;
               }
            }

            this.retryAt = now + 500000000L;
         }
      } else {
         this.reset();
      }
   }

   public void reset() {
      this.players.clear();
      this.pending.clear();
      this.retryAt = 0L;
   }

   private static final class State {
      final Deque<Long> times = new ArrayDeque<>();
      Long warned;
   }

   private record Warning(String name, long at) {
   }
}
