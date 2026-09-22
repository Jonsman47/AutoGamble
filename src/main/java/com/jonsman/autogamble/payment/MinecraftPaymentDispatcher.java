package com.jonsman.autogamble.payment;

import com.jonsman.autogamble.config.AutoGambleConfig;
import org.slf4j.LoggerFactory;

import com.jonsman.autogamble.manager.PlayerSelectionManager;
import com.jonsman.autogamble.manager.PlayerSelectionManager.Candidate;
import com.jonsman.autogamble.targeting.*;
import com.jonsman.autogamble.baltop.BaltopDatabase;
import net.minecraft.client.Minecraft;
import java.util.*;

/** Uses the vanilla command API only; never constructs custom packets. */
public final class MinecraftPaymentDispatcher implements AutoPayEnvironment, PaymentSender {
    private final Minecraft client;
    private com.jonsman.autogamble.history.PaymentHistory history;
    private com.jonsman.autogamble.history.AnalyticsEngine analytics;
    private com.jonsman.autogamble.manager.KnownBalance balance;
    private TippingManager tipping;
    public void history(com.jonsman.autogamble.history.PaymentHistory history, com.jonsman.autogamble.manager.KnownBalance balance) { this.history = history; this.balance = balance; }
    public void analytics(com.jonsman.autogamble.history.AnalyticsEngine analytics) { this.analytics = analytics; }
    public void tipping(TippingManager tipping) { this.tipping = tipping; }
    public Result sendFollow(String username) {
        var c = config.get();
        if (!c.enabled || !c.autoFollowGoodCustomersEnabled || c.dryRunMode || !connected() || inputBlocked() || !client.isSameThread()
                || username == null || !username.matches("[A-Za-z0-9_]{2,16}") || username.equalsIgnoreCase(client.player.getGameProfile().name()) || !gate.reserve()) return Result.RETRY_LATER;
        try { client.getConnection().sendCommand("follow " + username); return Result.SENT; }
        catch (RuntimeException ex) { LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Ambiguous follow dispatch; suppressing retry", ex); return Result.UNCERTAIN; }
    }
    private final PlayerSelectionManager selection;
    private final OutgoingPaymentTracker outgoing;
    private final java.util.function.Supplier<com.jonsman.autogamble.config.AutoGambleConfig> config;
    private final java.util.Random prefixRandom = new java.util.Random();
    private PrefixPlayerDiscovery discovery = new PrefixPlayerDiscovery(prefixRandom, 1, 3);
    private final EligibleRecipientQueue eligibleRecipientQueue = new EligibleRecipientQueue();
    private long nextQueueQueryAt, nextBalanceProbeAt;
    private boolean hasEligibleBalance;
    private long queueCandidatesChecked, queueCandidatesAccepted;
    private static final long QUEUE_QUERY_INTERVAL = 200_000_000L;
    private static final long BALANCE_EVIDENCE_TTL_MILLIS = 6L * 60 * 60 * 1000;
    private int prefixMin = 1, prefixMax = 3;
    private final FailedTargetBlacklist failed = new FailedTargetBlacklist();
    private final BaltopDatabase baltop;
    private final java.util.Random targetingRandom = new java.util.Random();
    private TargetMethod activeMethod;
    private TargetMethod lastMethod;
    private List<String> externalCandidates = List.of();
    private CandidateRetryQueue retryQueue = new CandidateRetryQueue(List.of());
    private long verificationGeneration, verificationRequestedAt, nextVerificationAt;
    private boolean verificationPending;
    private String verificationName = "", lastSuggestionPrefix = "", lastRejectedUsername = "", lastRejectionReason = "", lastSuccessfulTarget = "", lastResult = "WAITING";
    private List<String> verificationPrefixes = List.of();
    private int verificationStep, candidateChecks, suggestionRequests, onlineMatches, verificationFailures, paymentsAttempted, paymentsSent;
    private long lastPaymentMillis;
    private Candidate verifiedTarget;
    private long verifiedAt;
    private boolean targetingCycleComplete;
    private final EnumSet<TargetMethod> exhaustedMethods = EnumSet.noneOf(TargetMethod.class);
    private static final long VERIFICATION_TIMEOUT = 3_000_000_000L, VERIFICATION_INTERVAL = 250_000_000L;
    private boolean sendingPayment;
    private final DispatchGate gate = new DispatchGate();
    public MinecraftPaymentDispatcher(Minecraft client, PlayerSelectionManager selection, OutgoingPaymentTracker outgoing,
                                     java.util.function.Supplier<com.jonsman.autogamble.config.AutoGambleConfig> config) {
        this(client, selection, outgoing, config, null);
    }
    public MinecraftPaymentDispatcher(Minecraft client, PlayerSelectionManager selection, OutgoingPaymentTracker outgoing,
                                     java.util.function.Supplier<com.jonsman.autogamble.config.AutoGambleConfig> config,
                                     BaltopDatabase baltop) {
        this.client = client; this.selection = selection; this.outgoing = outgoing; this.config = config;
        this.baltop = baltop;
    }
    public void beginTick() { gate.beginTick(); }
    @Override public boolean connected() {
        return client != null && client.player != null && client.level != null
                && client.getConnection() != null && client.getConnection().getConnection().isConnected();
    }
    @Override public boolean inputBlocked() { return client == null || client.gui.screen() != null || client.gui.overlay() != null; }
    public void resetSession() { discovery.reset(); eligibleRecipientQueue.clear(); nextQueueQueryAt=nextBalanceProbeAt=0; hasEligibleBalance=false; queueCandidatesChecked=queueCandidatesAccepted=0; failed.reset(); resetTargetingCycle(); }
    public void cancelDiscovery() { discovery.cancel(); eligibleRecipientQueue.clear(); nextQueueQueryAt=nextBalanceProbeAt=0; hasEligibleBalance=false; resetTargetingCycle(); }
    @Override public void finishDiscovery() {
        if (config.get().minimumPaymentBalance.signum()==0 || ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED) discovery.cancel();
        if (ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED) resetTargetingCycle();
    }
    /** Called once per client tick, independently of the Auto Pay deadline. */
    public void refillRecipients(long now) {
        if (ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED || !connected() || inputBlocked()) return;
        var c=config.get();
        if (!c.enabled || !c.autoPayEnabled || c.minimumPaymentBalance.signum()<=0) return;
        if (prefixMin!=c.minimumPrefixLength || prefixMax!=c.maximumPrefixLength) {
            discovery.cancel(); eligibleRecipientQueue.clear();
            prefixMin=c.minimumPrefixLength; prefixMax=c.maximumPrefixLength;
            discovery=new PrefixPlayerDiscovery(prefixRandom,prefixMin,prefixMax);
        }
        if (!eligibleRecipientQueue.needsRefill(now)) return;
        if (nextBalanceProbeAt==0 || now-nextBalanceProbeAt>=0) {
            hasEligibleBalance=baltop!=null && baltop.hasRecentBalanceAtLeast(c.minimumPaymentBalance,
                    System.currentTimeMillis(),BALANCE_EVIDENCE_TTL_MILLIS);
            nextBalanceProbeAt=now+5_000_000_000L;
        }
        if (!hasEligibleBalance) return;
        if (discovery.ready()) {
            var filled=eligibleRecipientQueue.addApproved(discovery.candidates(),c.minimumPaymentBalance,
                    name->baltop==null?null:baltop.entryOf(name),now,System.currentTimeMillis(),BALANCE_EVIDENCE_TTL_MILLIS);
            queueCandidatesChecked+=filled.checked(); queueCandidatesAccepted+=filled.accepted();
            discovery.cancel();
        }
        if (nextQueueQueryAt!=0 && now-nextQueueQueryAt<0) return;
        var activeDiscovery=discovery;
        discovery.poll(now,selection,false).ifPresent(request -> {
            nextQueueQueryAt=now+QUEUE_QUERY_INTERVAL;
            var connection=client.getConnection();
            String local=client.player.getGameProfile().name();
            try {
                var context=connection.getCommands().parse(request.command().substring(1),connection.getSuggestionsProvider())
                        .getContext().build(request.command());
                connection.getSuggestionsProvider().customSuggestion(context).whenComplete((result,error) ->
                    client.execute(() -> {
                        if (client.getConnection()!=connection || discovery!=activeDiscovery) return;
                        var current=config.get();
                        if (!current.enabled || !current.autoPayEnabled || current.minimumPaymentBalance.signum()==0) {
                            discovery.cancel(); return;
                        }
                        discovery.complete(request.token(),error==null?result.getList().stream().map(s->s.getText()).toList():List.of(),
                                local,current.excludeNumericOnlyNames,failed,selection,false,System.nanoTime());
                    }));
            } catch (RuntimeException ex) {
                discovery.complete(request.token(),List.of(),local,c.excludeNumericOnlyNames,failed,selection,false,now);
            }
        });
    }
    public List<String> upcomingRecipients() {
        if (config.get().minimumPaymentBalance.signum()==0) return List.of();
        return eligibleRecipientQueue.snapshot(System.nanoTime()).stream().limit(5).map(Candidate::username).toList();
    }
    public String playerSource() {
        if (!ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED) return "RANDOM_PREFIX_SUGGESTIONS";
        return activeMethod == null ? "WEIGHTED_TARGETING" : activeMethod.name();
    }
    public String discoveryStatus() {
        return "Method: " + (activeMethod == null ? "waiting" : activeMethod.label()) + ", Prefix Length Range: " + config.get().minimumPrefixLength + "\u2013" + config.get().maximumPrefixLength
            + ", Last Prefix Length: " + discovery.lastLength() + ", Prefix Attempts This Cycle: " + discovery.attempts()
            + ", Last Auto Pay Prefix: " + discovery.prefix() + ", Last Candidate Count: " + discovery.count()
            + ", Last Selected Player: " + discovery.selected() + ", Numeric-Only Filter: "
            + (config.get().excludeNumericOnlyNames ? "ON" : "OFF") + ", Failed Target Blacklist: " + failed.size(System.nanoTime())
            + ", Eligible Queue: " + eligibleRecipientQueue.snapshot(System.nanoTime()).size()
            + ", Balance Checked: " + queueCandidatesChecked + ", Accepted: " + queueCandidatesAccepted;
    }
    public TargetingDiagnostics diagnostics() {
        var snapshot = baltop == null ? null : baltop.snapshot();
        var configNow = config.get();
        String local = connected() ? client.player.getGameProfile().name() : ""; long now = System.nanoTime();
        var pool=snapshot==null ? new TargetingCandidates.Pool(0,0,0,0,0,0,List.of())
                : TargetingCandidates.summary(snapshot,configNow,local,selection,failed,now);
        TargetMethod shown=activeMethod==null?lastMethod:activeMethod;
        return new TargetingDiagnostics(!ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED ? "Smart Random (legacy; baltop inactive)" : shown==null ? "Waiting" : shown.label(),pool.total(),pool.balanceEligible(),
                pool.localExcluded(),pool.recentExcluded(),pool.invalidExcluded(),pool.blockedExcluded(),pool.candidates().size(),candidateChecks,suggestionRequests,onlineMatches,verificationFailures,
                paymentsAttempted,paymentsSent,verificationName,lastSuggestionPrefix,lastRejectedUsername,lastRejectionReason,
                lastSuccessfulTarget,lastPaymentMillis == 0 ? -1 : System.currentTimeMillis()-lastPaymentMillis,lastResult);
    }
    public void manualCommand() { if (!sendingPayment) failed.unrelatedCommand(); }
    public void receiveFailure(ReceivedMessage message) {
        failed.receive(message, System.nanoTime()).ifPresent(name ->
            org.slf4j.LoggerFactory.getLogger("autogamble").info("[AutoGamble] Temporarily excluding failed Auto Pay target {} for 10 minutes", name));
    }
    @Override public boolean prepare(long now) {
        if (!ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED) return prepareLegacy(now);
        if (!connected() || inputBlocked()) return false;
        var c = config.get();
        if (targetingCycleComplete) return true;
        if (prefixMin != c.minimumPrefixLength || prefixMax != c.maximumPrefixLength) {
            discovery.cancel(); prefixMin = c.minimumPrefixLength; prefixMax = c.maximumPrefixLength;
            discovery = new PrefixPlayerDiscovery(prefixRandom, prefixMin, prefixMax);
        }
        if (activeMethod == null && !chooseMethod(c, now)) return targetingCycleComplete;
        if (activeMethod != TargetMethod.SMART_RANDOM || !externalCandidates.isEmpty()) return prepareExternal(now, c);
        var activeDiscovery = discovery;
        discovery.poll(now, selection, c.preferUnpaidPlayers).ifPresent(request -> {
            var connection = client.getConnection();
            String local = client.player.getGameProfile().name();
            try {
                PaySuggestionService.request(connection,request.prefix()).whenComplete((result, error) ->
                    client.execute(() -> {
                        if (client.getConnection() != connection || discovery != activeDiscovery) return;
                        var current = config.get();
                        if (!current.enabled || !current.autoPayEnabled) { discovery.cancel(); return; }
                        discovery.complete(request.token(), error == null ? result : List.of(),
                            local, current.excludeNumericOnlyNames, failed, selection, current.preferUnpaidPlayers, System.nanoTime());
                    }));
            } catch (RuntimeException ex) {
                discovery.complete(request.token(), List.of(), local, c.excludeNumericOnlyNames, failed, selection, c.preferUnpaidPlayers, now);
            }
        });
        if (discovery.ready() && discovery.candidates().isEmpty()) {
            exhaustedMethods.add(TargetMethod.SMART_RANDOM); discovery.cancel(); activeMethod = null;
            return !chooseMethod(c, now) && targetingCycleComplete;
        }
        if (discovery.ready()) {
            externalCandidates = discovery.candidates().stream().map(Candidate::username).distinct().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            Collections.shuffle(externalCandidates,targetingRandom);
            retryQueue=new CandidateRetryQueue(externalCandidates);
            return prepareExternal(now,c);
        }
        return false;
    }
    /** Exact 1.2.4 prefix discovery path; no baltop selection or exact-name second query. */
    private boolean prepareLegacy(long now) {
        if (!connected() || inputBlocked()) return false;
        var c = config.get();
        if (c.minimumPaymentBalance.signum()>0) return !eligibleRecipientQueue.snapshot(now).isEmpty();
        if (prefixMin != c.minimumPrefixLength || prefixMax != c.maximumPrefixLength) {
            discovery.cancel();
            prefixMin = c.minimumPrefixLength; prefixMax = c.maximumPrefixLength;
            discovery = new PrefixPlayerDiscovery(prefixRandom, prefixMin, prefixMax);
        }
        var activeDiscovery = discovery;
        discovery.poll(now, selection, c.preferUnpaidPlayers).ifPresent(request -> {
            var connection = client.getConnection();
            String local = client.player.getGameProfile().name();
            try {
                var context = connection.getCommands().parse(request.command().substring(1), connection.getSuggestionsProvider())
                        .getContext().build(request.command());
                connection.getSuggestionsProvider().customSuggestion(context).whenComplete((result, error) ->
                    client.execute(() -> {
                        if (client.getConnection() != connection || discovery != activeDiscovery) return;
                        var current = config.get();
                        if (!current.enabled || !current.autoPayEnabled) { discovery.cancel(); return; }
                        discovery.complete(request.token(), error == null ? result.getList().stream().map(s -> s.getText()).toList() : List.of(),
                            local, current.excludeNumericOnlyNames, failed, selection, current.preferUnpaidPlayers, System.nanoTime());
                    }));
            } catch (RuntimeException ex) {
                discovery.complete(request.token(), List.of(), local, c.excludeNumericOnlyNames, failed, selection, c.preferUnpaidPlayers, now);
            }
        });
        return discovery.ready();
    }
    @Override public List<Candidate> eligiblePlayers() {
        if (!connected()) return List.of();
        if (!ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED) {
            if (config.get().minimumPaymentBalance.signum()>0) return eligibleRecipientQueue.snapshot(System.nanoTime());
            List<Candidate> discovered=discovery.candidates().stream()
                    .filter(p -> PrefixPlayerDiscovery.validName(p.username(),client.player.getGameProfile().name(),config.get().excludeNumericOnlyNames)
                            && !failed.contains(p.username(),System.nanoTime())).toList();
            return discovered;
        }
        return verifiedTarget == null || System.nanoTime()-verifiedAt > 2_000_000_000L ? List.of() : List.of(verifiedTarget);
    }
    @Override public boolean dispatch(Candidate target, String amount) {
        if (!connected() || inputBlocked() || !client.isSameThread() || !eligiblePlayers().contains(target)) return false;
        if (amount == null || !amount.matches("[0-9]+(?:\\.[0-9]{1,2})?")) return false;
        if (!ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED) {
            if (config.get().minimumPaymentBalance.signum()>0 && !PaymentBalanceFilter.approved(
                    baltop==null?null:baltop.entryOf(target.username()),config.get().minimumPaymentBalance,
                    System.currentTimeMillis(),BALANCE_EVIDENCE_TTL_MILLIS)) {
                eligibleRecipientQueue.remove(target.username()); return false;
            }
            if (config.get().minimumPaymentBalance.signum()==0) discovery.selected(target.username());
            boolean sent=sendPayment(target.username(),new java.math.BigDecimal(amount),OutgoingPaymentTracker.Source.ADVERTISING)==Result.SENT;
            if (sent && config.get().minimumPaymentBalance.signum()>0) eligibleRecipientQueue.paid(target.username());
            if (sent && analytics!=null && !config.get().dryRunMode)
                analytics.targetingPayment(TargetMethod.SMART_RANDOM,target.username(),new java.math.BigDecimal(amount),System.currentTimeMillis());
            return sent;
        }
        if (activeMethod == TargetMethod.SMART_RANDOM) discovery.selected(target.username());
        TargetMethod suppliedBy = activeMethod;
        paymentsAttempted++;
        boolean sent = sendPayment(target.username(), new java.math.BigDecimal(amount), OutgoingPaymentTracker.Source.ADVERTISING) == Result.SENT;
        if (sent) {
            if (!config.get().dryRunMode) paymentsSent++;
            lastSuccessfulTarget=target.username(); lastPaymentMillis=System.currentTimeMillis();
            lastResult=config.get().dryRunMode?"DRY RUN":"PAYMENT SENT";
        }
        else reject(target.username(),"COMMAND_RATE_LIMIT");
        if (sent && analytics != null && !config.get().dryRunMode)
            analytics.targetingPayment(suppliedBy, target.username(), new java.math.BigDecimal(amount), System.currentTimeMillis());
        return sent;
    }
    @Override public Optional<Candidate> nextQueuedRecipient(long now, boolean preferUnpaid) {
        if (ExperimentalFeatures.BALTOP_PAYMENT_FEATURE_ENABLED || config.get().minimumPaymentBalance.signum()==0) return Optional.empty();
        for (int i=0;i<EligibleRecipientQueue.TARGET_SIZE;i++) {
            var next=eligibleRecipientQueue.next(now,preferUnpaid,selection::wasPaid);
            if (next.isEmpty()) return next;
            String name=next.get().username();
            if (PrefixPlayerDiscovery.validName(name,client.player.getGameProfile().name(),config.get().excludeNumericOnlyNames)
                    && !failed.contains(name,now)
                    && PaymentBalanceFilter.approved(baltop==null?null:baltop.entryOf(name),config.get().minimumPaymentBalance,
                            System.currentTimeMillis(),BALANCE_EVIDENCE_TTL_MILLIS)) return next;
            eligibleRecipientQueue.remove(name);
        }
        return Optional.empty();
    }

    private boolean chooseMethod(AutoGambleConfig c, long now) {
        if (exhaustedMethods.isEmpty()) {
            candidateChecks=suggestionRequests=onlineMatches=verificationFailures=paymentsAttempted=paymentsSent=0;
            verificationName=""; lastRejectedUsername=""; lastRejectionReason=""; lastResult="SELECTING";
        }
        String local = client.player.getGameProfile().name();
        List<String> money = baltop == null ? List.of() : TargetingCandidates.summary(baltop.snapshot(),c,local,selection,failed,now).candidates();
        EnumSet<TargetMethod> available = EnumSet.noneOf(TargetMethod.class);
        if (c.smartRandomWeight > 0) available.add(TargetMethod.SMART_RANDOM);
        if (c.moneyLeaderboardWeight > 0 && !money.isEmpty()) available.add(TargetMethod.MONEY_LEADERBOARD);
        available.removeAll(exhaustedMethods);
        var selected = WeightedTargetSelector.select(c, available, targetingRandom);
        if (selected.isEmpty()) {
            targetingCycleComplete = true; lastResult="NO_CANDIDATES";
            if (baltop != null && !baltop.snapshot().entries().isEmpty() && BaltopDatabase.filter(baltop.snapshot().entries(),
                    c.leaderboardMinimumBalance,c.leaderboardMaximumBalance,"").isEmpty()) reject("","OUTSIDE_BALANCE_RANGE");
            else reject("","NO_CANDIDATES");
            return false;
        }
        activeMethod = selected.get();
        lastMethod=activeMethod;
        if (analytics != null) analytics.targetingAttempt(activeMethod);
        externalCandidates = switch (activeMethod) {
            case MONEY_LEADERBOARD -> new ArrayList<>(money);
            case ECONOMY_ACTIVE, EXPERIMENTAL -> List.of();
            case SMART_RANDOM -> List.of();
        };
        if (!externalCandidates.isEmpty()) Collections.shuffle(externalCandidates, targetingRandom);
        retryQueue=new CandidateRetryQueue(externalCandidates);
        return true;
    }
    private boolean prepareExternal(long now, AutoGambleConfig c) {
        if (verifiedTarget != null) {
            if (now-verifiedAt <= 2_000_000_000L) return true;
            reject(verifiedTarget.username(),"VERIFICATION_EXPIRED"); verifiedTarget=null;
        }
        if (verificationPending) {
            if (now - verificationRequestedAt < VERIFICATION_TIMEOUT) return false;
            verificationPending = false; verificationGeneration++; verificationStep++;
            nextVerificationAt = now + VERIFICATION_INTERVAL;
            lastResult="AUTOCOMPLETE_TIMEOUT";
            if (verificationStep >= verificationPrefixes.size()) finishFailedVerification("AUTOCOMPLETE_TIMEOUT");
        }
        if (now < nextVerificationAt) return false;
        if (!verificationName.isEmpty()) { requestExactVerification(now); return false; }
        int budget = activeMethod == TargetMethod.MONEY_LEADERBOARD ? c.baltopMaxChecksPerCycle : Math.min(c.baltopMaxChecksPerCycle,10);
        if (candidateChecks >= budget) { finishCandidateBudget(); return true; }
        Optional<String> next;
        while ((next=retryQueue.next()).isPresent()) {
            String name = next.get();
            if (selection.recentlyPaid(name, now)) { if (analytics != null) analytics.targetingRepeatPrevented(activeMethod); reject(name,"RECENT_TARGET"); continue; }
            if (failed.contains(name, now)) { reject(name,"BLOCKED_TARGET"); continue; }
            if (name.equalsIgnoreCase(client.player.getGameProfile().name())) { reject(name,"LOCAL_PLAYER"); continue; }
            verificationPrefixes=OnlineVerification.prefixes(name);
            if (verificationPrefixes.isEmpty()) { reject(name,"INVALID_USERNAME"); continue; }
            verificationName=name; verificationStep=0; candidateChecks++;
            requestExactVerification(now); return false;
        }
        // The selected pool was entirely offline; reselect from the remaining enabled methods.
        exhaustedMethods.add(activeMethod); activeMethod = null; externalCandidates = List.of(); retryQueue=new CandidateRetryQueue(List.of());
        return !chooseMethod(c, now) && targetingCycleComplete;
    }
    private void finishCandidateBudget() {
        lastResult="NO_ONLINE_MATCH_AFTER_"+candidateChecks+"_CANDIDATES";
        LoggerFactory.getLogger("autogamble").info("[AutoGamble] {}: {} candidate checks, {} suggestion requests, {} matches; last rejection {} {}",
                activeMethod,candidateChecks,suggestionRequests,onlineMatches,lastRejectedUsername,lastRejectionReason);
        targetingCycleComplete=true;
    }
    private void reject(String name,String reason) { lastRejectedUsername=name; lastRejectionReason=reason; }
    private void finishFailedVerification(String reason) {
        boolean onlineFailure=reason.startsWith("AUTOCOMPLETE");
        if(onlineFailure) verificationFailures++;
        reject(verificationName,reason); lastResult=reason; verificationName=""; verificationPrefixes=List.of(); verificationStep=0;
        if (analytics != null && activeMethod != null) {
            if(onlineFailure) analytics.targetingOfflineRejected(activeMethod);
            else if(reason.equals("RECENT_TARGET")) analytics.targetingRepeatPrevented(activeMethod);
        }
    }
    private void requestExactVerification(long now) {
        var connection = client.getConnection(); long token = ++verificationGeneration;
        verificationPending = true; verificationRequestedAt = now;
        suggestionRequests++;
        String name=verificationName, prefix=verificationPrefixes.get(verificationStep);
        lastSuggestionPrefix=prefix; lastResult="Checking /pay "+prefix;
        try {
            PaySuggestionService.request(connection,prefix).whenComplete((result, error) -> client.execute(() -> {
                if (token != verificationGeneration || client.getConnection() != connection) return;
                verificationPending = false; nextVerificationAt = System.nanoTime() + VERIFICATION_INTERVAL;
                if (client.player == null) { finishFailedVerification("DISCONNECTED"); return; }
                boolean exact = error == null && OnlineVerification.exactUsername(result,name,client.player.getGameProfile().name());
                if (exact && selection.recentlyPaid(name,System.nanoTime())) { finishFailedVerification("RECENT_TARGET"); return; }
                if (exact && failed.contains(name,System.nanoTime())) { finishFailedVerification("BLOCKED_TARGET"); return; }
                if (exact && !PrefixPlayerDiscovery.validName(name,client.player.getGameProfile().name(),config.get().excludeNumericOnlyNames)) {
                    finishFailedVerification("INVALID_USERNAME"); return;
                }
                if (exact) {
                    verifiedTarget = new Candidate(null, name); verifiedAt=System.nanoTime();
                    verificationName=""; verificationPrefixes=List.of(); verificationStep=0;
                    onlineMatches++; lastResult="ONLINE MATCH: "+name;
                    if (analytics != null) analytics.targetingValidCandidate(activeMethod);
                } else {
                    verificationStep++;
                    if (verificationStep >= verificationPrefixes.size()) finishFailedVerification(error == null ? "AUTOCOMPLETE_NO_EXACT_MATCH" : "AUTOCOMPLETE_ERROR");
                    else lastResult="Retrying shorter prefix for "+name;
                }
            }));
        } catch (RuntimeException ex) {
            verificationPending = false; nextVerificationAt = now + VERIFICATION_INTERVAL; verificationStep++;
            if (verificationStep >= verificationPrefixes.size()) finishFailedVerification("AUTOCOMPLETE_ERROR");
        }
    }
    private void resetTargetingCycle() {
        verificationGeneration++; verificationPending = false; verifiedTarget = null; verifiedAt=0; activeMethod = null;
        externalCandidates = List.of(); retryQueue=new CandidateRetryQueue(List.of()); nextVerificationAt = 0; verificationName=""; verificationPrefixes=List.of(); verificationStep=0;
        targetingCycleComplete = false; exhaustedMethods.clear();
    }
   public boolean sendWarning(String username, String message) {
      AutoGambleConfig c = this.config.get();
      if (!c.enabled || !c.gambleEnabled || !c.spamPaymentWarningEnabled || !this.connected() || this.inputBlocked() || !this.client.isSameThread()) {
         return false;
      } else if (SpamWarningCommand.validText(message)
         && username != null
         && username.matches("[A-Za-z0-9_]{3,16}")
         && !username.equalsIgnoreCase(this.client.player.getGameProfile().name())
         && this.gate.reserve()) {
         try {
            SpamWarningCommand.execute(c.dryRunMode, username, message, cmd -> this.client.getConnection().sendCommand(cmd));
         } catch (RuntimeException var5) {
            LoggerFactory.getLogger("autogamble").warn("[AutoGamble] Warning dispatch failed; not retrying ambiguous send", var5);
         }

         return true;
      } else {
         return false;
      }
   }


    @Override public Result sendPayment(String username, java.math.BigDecimal amount, OutgoingPaymentTracker.Source source) {
        var c = config.get();
        boolean sourceEnabled = switch (source) {
            case ADVERTISING -> c.enabled && c.autoPayEnabled;
            case BALANCE_RULE -> c.enabled && c.automaticBalancePaymentsEnabled;
            case GAMBLE_PAYOUT -> c.enabled && c.gambleEnabled;
            case LOSING_BET_TIP -> c.enabled && c.gambleEnabled && !c.tippingPermanentlyDisabled;
            case TIP_DISABLE_PURCHASE -> !c.dryRunMode;
        };
        if (!sourceEnabled || !connected() || inputBlocked() || !client.isSameThread()) return Result.RETRY_LATER;
        if (username == null || !username.matches("[A-Za-z0-9_]{2,16}")
                || username.equalsIgnoreCase(client.player.getGameProfile().name())
                || (source == OutgoingPaymentTracker.Source.ADVERTISING
                    && eligiblePlayers().stream().noneMatch(p -> p.username().equalsIgnoreCase(username)))) return Result.RETRY_LATER;
        if (source == OutgoingPaymentTracker.Source.BALANCE_RULE && (balance == null || balance.current(System.nanoTime()) == null
                || balance.current(System.nanoTime()).compareTo(amount) < 0)) return Result.RETRY_LATER;
        String formatted = AmountFormatter.format(amount);
        if (!gate.reserve()) return Result.RETRY_LATER;
        return PaymentExecution.execute(c.dryRunMode, username, new java.math.BigDecimal(formatted), source,
                System.nanoTime(), c.outgoingPaymentTrackingWindowMs, outgoing, command -> {
                    failed.dispatched(username, source, System.nanoTime());
                    sendingPayment = true;
                    try { client.getConnection().sendCommand(command); }
                    finally { sendingPayment = false; if (balance != null) balance.invalidate(); }
                }, () -> {
                    var paidAmount = new java.math.BigDecimal(formatted);
                    if ((source == OutgoingPaymentTracker.Source.LOSING_BET_TIP
                            || source == OutgoingPaymentTracker.Source.TIP_DISABLE_PURCHASE) && tipping != null) {
                        tipping.dispatched(username, paidAmount, source, System.currentTimeMillis());
                    } else {
                        if (history != null) history.record(com.jonsman.autogamble.history.PaymentHistory.Direction.PAID, username, paidAmount, source.name());
                        if (analytics != null) analytics.outgoing(username, paidAmount, source, System.currentTimeMillis());
                    }
                });
    }
}
