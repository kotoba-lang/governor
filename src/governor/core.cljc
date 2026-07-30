(ns governor.core
  "The itonami actor pattern's governor layer, written once.

  Every actor in this workspace seals its intelligence into one node that may
  only *propose*, and puts an independent Governor between that proposal and
  any write, disclosure, filing, payment or authentication (CLAUDE.md Actors
  section, ADR-2607011000). The domain rules differ completely between
  actors — a GTIN allocator checks a check digit, a law firm checks 利益相反,
  a formation agent checks a sanctions list. **The verdict assembly around
  those rules does not differ, and it was copied by hand into 376
  repositories.**

  Measured 2026-07-30 across those 376 `governor.cljc` files:

    * 346 assemble the verdict with the same five-line shape, differing only
      in the local name of the escalation trigger (`always-risky?` ×146,
      `risky-op?` ×107, `stakes?` ×13, `escalating-op?` ×9, …).
    * 365 declare `confidence-floor` as `0.6`; 5 use `0.4`, 5 use `0.7`.
    * **1 has drifted.** `cloud-itonami-isco-5419` computes
      `:escalate? (or hard? low? escalating-op?)` where every sibling computes
      `(and (not hard?) (or low? escalating-op?))`.

  That last one is the reason this library exists rather than a style guide.
  The drift is not exploitable through the standard graph — the router tests
  `:hard?` first, so the operation still lands in `:hold` — but the verdict it
  hands to every *other* reader is wrong: a console, an approval queue or a
  report that trusts `:escalate?` will show a permanently-refused operation as
  awaiting sign-off, and invite a human to try to approve something that can
  never be approved. This is the same failure mode CLAUDE.md already records
  for `cacao.clj` (copied into ~25 repos with a \"keep in sync\" comment, then
  actually diverged) and for `langchain-store`'s codec (copied
  complete-identical into 190). A hand-copied invariant is not an invariant.

  ## Two dialects, and why one of them cannot drift

  The fleet writes the verdict two ways, and the split is not arbitrary:

  * **boolean** (346 actors) — `{:ok? :hard? :escalate?}`, three independent
    flags. This is the dialect that drifted, and it is *able* to drift because
    nothing in the shape stops two flags from contradicting each other. Use
    `verdict` + `conformance-failures`.
  * **enum** (24 actors) — `{:decision :proceed|:hold|:human-approval}`, one
    value. A verdict cannot say both 「permanently refused」 and 「awaiting
    sign-off」 because there is only one slot. **The isco-5419 defect is
    unrepresentable here.** Use `decision`.

  New actors should prefer the enum. `verdict->decision` and
  `decision->verdict` translate, so a console can consume either.

  ## The missing-confidence default

  Both constructors treat an absent `:confidence` as **0.0**, never 1.0.

  This is worth stating because the fleet does not agree with itself. All 346
  boolean actors default to 0.0 and one of them tests it explicitly — a
  proposal with no :confidence key at all is treated as 0.0 confidence, never
  silently treated as trustworthy. All 24 enum actors default to **1.0** —
  and `cloud-itonami-isco-4321`'s own advisor docstring says the opposite of
  what its governor does — its advisor docstring reads: LLM parse failures
  always yield :confidence 0.0 (never fabricate confidence), which forces the
  governor to escalate/hold.

  A proposal that does not say how confident it is has not said it is
  confident. Defaulting to 1.0 means an omission auto-proceeds, which is the
  one direction a governor must never fail in.

  ## What belongs here, and what does not

  **Here:** the verdict arithmetic, the routing decision derived from it, the
  four provenance checks whose logic is genuinely identical everywhere, and a
  conformance check an actor's own test suite can call to prove its verdict is
  well-formed.

  **Not here:** domain rules, store protocols, detail wording. Each actor's
  `:detail` strings are user-facing text in that actor's language and idiom,
  and its Store is its own — so every function below takes the record or value
  already read, never a store, and every rule keyword and detail string can be
  overridden. This library holds no ambient authority: it reads nothing,
  writes nothing, and calls nothing.

  ## Migration

  Adopt incrementally, on the next occasion you touch a governor — never as a
  fleet-wide rewrite (CLAUDE.md: \"触るついでに漸進移行\"). The smallest useful
  adoption is one line:

  ```clojure
  ;; before
  {:ok? (and (not hard?) (not low?) (not always-risky?))
   :violations hard :confidence conf :hard? hard?
   :escalate? (and (not hard?) (or low? always-risky?))}

  ;; after
  (gov/verdict {:violations hard :confidence conf :escalating-op? always-risky?})
  ```")

(def default-confidence-floor
  "Below this, a proposal escalates for human sign-off regardless of what it
  proposes. 0.6 is what 365 of the 376 surveyed actors use; the outliers (0.4,
  0.7) are deliberate and pass their own value to `verdict`."
  0.6)

;; ---------------------------------------------------------------------------
;; Shared provenance rules
;;
;; Each returns a violation map or nil, so they compose through `violations`.
;; They take the value already read from the actor's own store — passing the
;; store itself would make this library depend on 376 different protocols.
;; ---------------------------------------------------------------------------

(defn no-actuation
  "The proposal must be a proposal.

  The single invariant every actor in the fleet shares: the intelligence node
  produces `{:effect :propose}` and nothing else, so no path exists from a
  model's output to an effect that has not passed the gate. `:effect` is the
  expected value, for the few actors that name their non-acting effect
  something else (`animeka-actor` uses `:production`)."
  ([proposal] (no-actuation proposal nil))
  ([proposal {:keys [effect rule detail]
              :or {effect :propose rule :no-actuation}}]
   (when (not= effect (:effect proposal))
     {:rule rule
      :detail (or detail
                  (str "effect は " effect " のみ許可"
                       "（governor を経ない直接の書込・提出・送金・署名は行わない）"))})))

(defn missing-subject
  "The party the request is made on behalf of must be registered.

  `record` is what the actor's store returned for the requesting party — nil
  means the actor was asked to act for someone it has no record of. Actors
  name this party differently (client / officer / practitioner / seeker), so
  `:rule` and `:detail` are overridable; the default keyword `:no-client` is
  what 300+ of the surveyed actors already emit."
  ([record] (missing-subject record nil))
  ([record {:keys [rule detail] :or {rule :no-client}}]
   (when (nil? record)
     {:rule rule :detail (or detail "未登録の主体に対する操作は不可")})))

(defn unknown-scope
  "The entity the proposal cites must exist.

  `scope` is the matter / case / allocation / file the proposal names —
  whatever this actor scopes work to. `:applies?` lets a caller restrict the
  check to the ops that need a scope, so an op that legitimately has none does
  not trip it."
  ([scope] (unknown-scope scope nil))
  ([scope {:keys [rule detail applies?] :or {rule :unknown-matter applies? true}}]
   (when (and applies? (nil? scope))
     {:rule rule :detail (or detail "未登録の対象に対する操作は不可")})))

(defn scope-owner-mismatch
  "The cited entity must belong to the requesting party.

  Cross-tenant leakage in one comparison: without it, knowing an id is enough
  to act on another party's file. `owner-key` is the field carrying ownership
  on both the scope and the request (`:client-id` for most actors)."
  ([scope request] (scope-owner-mismatch scope request nil))
  ([scope request {:keys [owner-key rule detail]
                   :or {owner-key :client-id rule :matter-wrong-client}}]
   (when (and (some? scope)
              (not= (get scope owner-key) (get request owner-key)))
     {:rule rule
      :detail (or detail
                  (str "対象 " (pr-str (get scope owner-key))
                       " は要求者 " (pr-str (get request owner-key)) " のものではない"))})))

(defn violations
  "Collect rule results into the violation vector `verdict` takes, dropping
  nils. Accepts single violations and seqs of them, so a domain helper
  returning a vector composes with a single-rule helper returning one map."
  [& results]
  (into []
        (comp (mapcat #(if (map? %) [%] %)) (filter some?))
        results))

;; ---------------------------------------------------------------------------
;; The verdict
;; ---------------------------------------------------------------------------

(defn verdict
  "Assemble the governor's verdict — the five lines that were copied 376 times.

  Inputs:
    `:violations`       domain HARD violations. Non-empty ⇒ `:hard?`.
    `:confidence`       the proposal's self-reported confidence (nil ⇒ 0.0).
    `:escalating-op?`   true when the op requires human sign-off *by its
                        nature*, at any confidence. Actors call this
                        `always-risky?` / `risky-op?` / `stakes?`.
    `:confidence-floor` optional, defaults to 0.6.
    `:extra`            optional map merged into the result, for actors that
                        carry an additional key (e.g. `:high-stakes?`).

  Output: `{:ok? :violations :confidence :hard? :escalate? :escalation-reason}`.

  The three dispositions are mutually exclusive and `:hard?` wins. A hard
  violation is **never** reported as escalatable — it is not a thing a human
  can wave through, and saying otherwise invites someone to try. That is
  precisely the guarantee the one drifted copy in the fleet lost."
  [{:keys [violations confidence escalating-op? confidence-floor extra]}]
  (let [vs (vec violations)
        hard? (boolean (seq vs))
        conf (or confidence 0.0)
        floor (or confidence-floor default-confidence-floor)
        low? (< conf floor)
        risky? (boolean escalating-op?)]
    (merge
     {:ok? (and (not hard?) (not low?) (not risky?))
      :violations vs
      :confidence conf
      :hard? hard?
      :escalate? (and (not hard?) (or low? risky?))
      :escalation-reason (cond
                           hard? nil
                           risky? :counsel-decision
                           low? :low-confidence)}
     extra)))

(defn disposition
  "Route a verdict: `:hold` | `:request-approval` | `:commit`.

  The conditional edge out of `:govern` in every actor's graph. Checking
  `:hard?` first is load-bearing — it is what kept the drifted copy from
  actually mis-routing, and it stays first here so a malformed verdict from
  anywhere still fails closed."
  [{:keys [hard? escalate?]}]
  (cond hard? :hold
        escalate? :request-approval
        :else :commit))

;; ---------------------------------------------------------------------------
;; The enum dialect — one slot, so a contradictory verdict cannot be written
;; ---------------------------------------------------------------------------

(def decisions
  "The enum dialect's complete vocabulary.

    :proceed        — commit
    :human-approval — escalate for sign-off; the operation is legitimate
    :hold           — refused; no approval path"
  #{:proceed :human-approval :hold})

(defn decision
  "Assemble an enum-dialect verdict.

  Same inputs as `verdict`. Returns
  `{:decision :violations :confidence :reason}` — `:reason` is `nil` on
  `:proceed`, `:violations` on `:hold`, and `:counsel-decision` /
  `:low-confidence` on `:human-approval`.

  The precedence is the same and for the same reason: violations first, then
  the op's own nature, then confidence. What differs is that there is exactly
  one output slot, so the drift `conformance-failures` exists to catch in the
  boolean dialect has nowhere to live here."
  [{:keys [violations confidence escalating-op? confidence-floor extra]}]
  (let [vs (vec violations)
        conf (or confidence 0.0)
        floor (or confidence-floor default-confidence-floor)]
    (merge
     (cond
       (seq vs) {:decision :hold :violations vs :confidence conf :reason :violations}
       escalating-op? {:decision :human-approval :violations [] :confidence conf
                       :reason :counsel-decision}
       (< conf floor) {:decision :human-approval :violations [] :confidence conf
                       :reason :low-confidence}
       :else {:decision :proceed :violations [] :confidence conf :reason nil})
     extra)))

(defn verdict->decision
  "Boolean dialect -> enum. Uses `disposition`, so a malformed verdict still
  fails closed."
  [{:keys [violations confidence escalation-reason] :as v}]
  (let [d (case (disposition v)
            :hold :hold
            :request-approval :human-approval
            :commit :proceed)]
    {:decision d
     :violations (vec violations)
     :confidence confidence
     :reason (case d
               :hold :violations
               :human-approval escalation-reason
               nil)}))

(defn decision->verdict
  "Enum -> boolean dialect, for a consumer that only speaks the flags."
  [{:keys [decision violations confidence reason]}]
  {:ok? (= :proceed decision)
   :violations (vec violations)
   :confidence confidence
   :hard? (= :hold decision)
   :escalate? (= :human-approval decision)
   :escalation-reason (when (= :human-approval decision) reason)})

(defn decision-conformance-failures
  "Every way `d` is not a well-formed enum verdict."
  [{:keys [decision violations confidence reason] :as d}]
  (cond-> []
    (not (map? d))
    (conj {:check :shape :detail "decision is not a map"})

    (not (contains? decisions decision))
    (conj {:check :unknown-decision
           :detail (str "decision " (pr-str decision) " が語彙 " (pr-str decisions) " に無い")})

    (and (= :hold decision) (empty? violations))
    (conj {:check :hold-without-violations
           :detail "hold なのに violations が空 — 何を拒否したか示せない"})

    (and (not= :hold decision) (seq violations))
    (conj {:check :violations-without-hold
           :detail "violations があるのに hold でない"})

    (and (= :human-approval decision) (nil? reason))
    (conj {:check :approval-without-reason
           :detail "human-approval なのに reason が無い（承認者に理由を示せない）"})

    (and (= :proceed decision) (some? reason))
    (conj {:check :reason-on-proceed :detail "proceed なのに reason がある"})

    (not (number? confidence))
    (conj {:check :confidence-missing :detail "confidence が数値でない"})

    (some #(not (:rule %)) (or violations []))
    (conj {:check :violation-without-rule
           :detail "rule キーワードの無い violation がある"})))

(defn decision-conformant?
  "True when `d` is a well-formed enum verdict."
  [d]
  (empty? (decision-conformance-failures d)))

;; ---------------------------------------------------------------------------
;; Conformance — call this from your actor's test suite
;; ---------------------------------------------------------------------------

(defn conformance-failures
  "Every way `v` is not a well-formed verdict, as `{:check :detail}` maps.
  Empty means well-formed.

  An actor that keeps its own `check` implementation can still pin its output
  against this — which is the point. The four properties below are what a
  hand-copy loses silently, and asserting them costs one line:

  ```clojure
  (is (empty? (gov/conformance-failures (governor/check req ctx proposal store))))
  ```"
  [{:keys [ok? hard? escalate? escalation-reason violations confidence] :as v}]
  (cond-> []
    (not (map? v))
    (conj {:check :shape :detail "verdict is not a map"})

    (and hard? escalate?)
    (conj {:check :hard-is-not-escalatable
           :detail "hard? と escalate? が同時に true — HARD hold は人が承認できるものではない"})

    (and ok? (or hard? escalate?))
    (conj {:check :ok-is-exclusive
           :detail "ok? が true なのに hard? / escalate? も true"})

    (and (not ok?) (not hard?) (not escalate?))
    (conj {:check :no-disposition
           :detail "ok? / hard? / escalate? がすべて false — 処理先が決まらない"})

    (and escalate? (nil? escalation-reason))
    (conj {:check :escalation-without-reason
           :detail "escalate? が true だが escalation-reason が無い（承認者に理由を示せない）"})

    (and (not escalate?) (some? escalation-reason))
    (conj {:check :reason-without-escalation
           :detail "escalate? でないのに escalation-reason がある"})

    (and hard? (empty? violations))
    (conj {:check :hard-without-violations
           :detail "hard? が true だが violations が空 — 何を拒否したか示せない"})

    (and (seq violations) (not hard?))
    (conj {:check :violations-without-hard
           :detail "violations があるのに hard? が false"})

    (not (number? confidence))
    (conj {:check :confidence-missing :detail "confidence が数値でない"})

    (some #(not (:rule %)) (or violations []))
    (conj {:check :violation-without-rule
           :detail "rule キーワードの無い violation がある（消費側が分岐できない）"})))

(defn conformant?
  "True when `v` is a well-formed verdict."
  [v]
  (empty? (conformance-failures v)))
