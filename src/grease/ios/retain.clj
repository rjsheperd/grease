(ns grease.ios.retain
  "Centralised ARC retention registry.

  Every ObjC object that must survive beyond its immediate creation scope
  should be registered here. The registry holds a strong reference, preventing
  ARC from collecting the object until [[release!]] is called.

  Usage pattern:
    (retain! ::my-delegate ptr :delegate)
    ;; … use ptr …
    (release! ::my-delegate)

  Bulk teardown by kind (e.g. when dismissing a screen):
    (release-kind! :delegate)

  Emergency reset (REPL use only — leaks all retained objects):
    (reset-all!)")

(def ^:private registry
  "Map of {key {:ptr <objc-pointer> :kind <keyword> :created-at <epoch-ms>}}"
  (atom {}))

;; ── Public API ──────────────────────────────────────────────────────────────

(defn retain!
  "Register `ptr` under `key` with the given `kind` keyword.

  `key` is caller-chosen — a namespace-qualified keyword, a gensym, a path —
  whatever is meaningful at the call site. Returns `key`.

  If `key` is already registered the old entry is replaced (the previous ptr
  is not explicitly released — the caller is responsible for sending any
  teardown messages before re-registering)."
  [key ptr kind]
  (swap! registry assoc key {:ptr ptr :kind kind :created-at (System/currentTimeMillis)})
  key)

(defn release!
  "Remove the entry for `key` and return its ptr, or nil if absent.

  The caller should send any final ObjC messages to the returned ptr
  (e.g. `removeObserver:`, `stopUpdatingLocation`) before dropping the
  reference."
  [key]
  (let [entry (get @registry key)]
    (swap! registry dissoc key)
    (:ptr entry)))

(defn release-kind!
  "Release all entries whose `:kind` equals `kind`. Returns the released ptrs.

  Useful for bulk teardown — e.g. release every `:delegate` object owned by
  a screen when the screen is dismissed."
  [kind]
  (let [matches (filter #(= kind (:kind (val %))) @registry)
        ptrs    (map (comp :ptr val) matches)]
    (swap! registry #(apply dissoc % (map key matches)))
    (vec ptrs)))

(defn retained
  "Return the retained ptr for `key`, or nil if not registered."
  [key]
  (get-in @registry [key :ptr]))

(defn retained-keys
  "Return the set of all registered keys. Useful for REPL introspection."
  []
  (set (keys @registry)))

(defn retained-count
  "Return the number of currently retained objects."
  []
  (count @registry))

(defn reset-all!
  "Clear the entire registry. Emergency REPL escape hatch — leaks all
  retained objects (ARC will not reclaim them until the process exits)."
  []
  (reset! registry {}))
