# governor

The itonami actor pattern's governor layer, written once.

**Maturity: `:implemented`.** 15 tests / 104 assertions green (`clojure -M:test`),
`clojure -M:lint` warnings 0, zero dependencies.

```clojure
;; deps.edn
io.github.kotoba-lang/governor
{:git/url "https://github.com/kotoba-lang/governor.git"
 :git/sha "<pin>"}
```

---

## Why this exists

Every actor in this workspace seals its intelligence into one node that may only
*propose*, and puts an independent Governor between that proposal and any write,
disclosure, filing, payment or authentication (CLAUDE.md Actors section,
ADR-2607011000).

The domain rules differ completely between actors — a GTIN allocator checks a
check digit, a law firm checks 利益相反, a formation agent checks a sanctions
list. **The verdict assembly around those rules does not differ, and it was
copied by hand into 376 repositories.**

Measured 2026-07-30 across those 376 `governor.cljc` files:

| | |
|---|---|
| same five-line verdict shape | **346**, differing only in the local name of the escalation trigger (`always-risky?` ×146, `risky-op?` ×107, `stakes?` ×13, `escalating-op?` ×9, …) |
| `confidence-floor` = `0.6` | **365** (5 use `0.4`, 5 use `0.7` — deliberate) |
| **drifted** | **1** |

The drift is `cloud-itonami-isco-5419`:

```clojure
:escalate? (or hard? low? escalating-op?)          ; ← what it has
:escalate? (and (not hard?) (or low? escalating-op?))  ; ← what 346 siblings have
```

It is **not** exploitable through the standard graph — the router tests `:hard?`
first, so the operation still lands in `:hold`. But the verdict handed to every
*other* reader is wrong: a console, an approval queue or a report that trusts
`:escalate?` will show a permanently-refused operation as awaiting sign-off, and
invite a human to try to approve something that can never be approved.

This is the third instance of the same failure mode this workspace has recorded:

- `cacao.clj`, copied into ~25 repos with a "keep in sync" comment, then
  **actually diverged** (`denrei` uses shared `ed25519.core` and multi-cap
  grants; `gijiroku` hand-rolls JDK Ed25519 and has no multi-cap) — CLAUDE.md,
  ADR-2607268000.
- `langchain-store`'s `enc`/`dec` codec, copied complete-identical into 190 —
  ADR-2607141600.
- this.

A hand-copied invariant is not an invariant.

---

## What belongs here, and what does not

**Here** — the parts that are genuinely identical everywhere:

| fn | what it is |
|---|---|
| `verdict` | the five-line assembly: `{:ok? :violations :confidence :hard? :escalate? :escalation-reason}` |
| `disposition` | the conditional edge out of `:govern` → `:hold` \| `:request-approval` \| `:commit` |
| `no-actuation` | `:effect` must be `:propose` |
| `missing-subject` | the requesting party must be registered |
| `unknown-scope` | the cited matter/case/file must exist |
| `scope-owner-mismatch` | and must belong to the requesting party |
| `violations` | collect rule results, dropping nils |
| `conformance-failures` / `conformant?` | prove a verdict is well-formed — **including actors that keep their own `check`** |

**Not here** — domain rules, store protocols, detail wording. Each actor's
`:detail` strings are user-facing text in that actor's language and idiom, and
its Store is its own. So every function takes the **record already read**, never
a store, and every rule keyword and detail string is overridable. This library
holds no ambient authority: it reads nothing, writes nothing, calls nothing, and
has no dependencies.

---

## Adoption

Incrementally, on the next occasion you touch a governor — **never as a
fleet-wide rewrite** (CLAUDE.md: 「触るついでに漸進移行」). Three sizes:

### 1. One line — the verdict

```clojure
;; before
{:ok? (and (not hard?) (not low?) (not always-risky?))
 :violations hard :confidence conf :hard? hard?
 :escalate? (and (not hard?) (or low? always-risky?))}

;; after
(gov/verdict {:violations hard :confidence conf :escalating-op? always-risky?})
```

You get `:escalation-reason` for free, which a console can show the approver.

### 2. One test — conformance, without changing any code

The cheapest useful adoption. Keep your `check` exactly as it is and pin its
output:

```clojure
(is (empty? (gov/conformance-failures (governor/check req ctx proposal store))))
```

This is what catches the isco-5419 class. It costs one line and one test dep.

### 3. The provenance rules

```clojure
(gov/verdict
 {:violations (gov/violations
               (gov/no-actuation proposal)
               (gov/missing-subject client)
               (gov/unknown-scope matter {:applies? (some? (:matter-id proposal))})
               (gov/scope-owner-mismatch matter request)
               ;; your domain's rules go here, unchanged
               (when (> hours ceiling) {:rule :over-scope :detail "受任範囲超過"}))
  :confidence (:confidence proposal)
  :escalating-op? (contains? always-escalate-ops (:op proposal))})
```

Renaming for actors whose party is not a "client":

```clojure
(gov/missing-subject officer {:rule :no-officer :detail "未登録の職員"})
(gov/scope-owner-mismatch file request {:owner-key :org-id})
(gov/no-actuation proposal {:effect :production})   ; animeka-actor
```

---

## The guarantee

`verdict`'s three dispositions are mutually exclusive and **`:hard?` wins**.

A hard violation is never reported as escalatable. It is not a thing a human can
wave through, and saying otherwise invites someone to try. `disposition` tests
`:hard?` first for the same reason, so even a malformed verdict from somewhere
else fails closed.

`conformance-failures` checks nine properties in total — the exclusivity above,
that an escalation carries a reason, that a hold names what it refused, and that
every violation carries a `:rule` keyword a consumer can branch on.

---

## Adopters

| repo | adoption |
|---|---|
| `cloud-itonami/lawfirm` | full — `verdict` + provenance rules + conformance test |
| `cloud-itonami/cloud-itonami-isco-2611` | full |
| `cloud-itonami/cloud-itonami-isco-5419` | full — this is where the drift was fixed |

The remaining ~373 keep their hand-written verdict until someone has a reason to
open the file. That is the intended pace: a migration that requires touching 376
repositories at once is a migration that does not happen, and one that rewrites
them unread is how the `west.yml` pin regression of `90852b86` happened.
