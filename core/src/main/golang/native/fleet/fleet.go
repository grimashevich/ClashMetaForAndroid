// Package fleet consumes the fleet-status sidecar: an hourly,
// server-side sweep that reports, per VPN node, whether Gemini answers
// and which country YouTube attributes to the exit IP.
//
// The Kotlin side downloads the feed and writes it to
// <home>/fleet_status.json (atomic temp+rename); this package only ever
// reads it. Reads are lazy — a stat throttled to once every couple of
// seconds detects a rewrite — so a refresh performed by *either* process
// (UI or service) is picked up without any JNI or AIDL round trip.
//
// Two consumers:
//   - the geo-split balancer groups, via outboundgroup.SetRegionProvider:
//     a Gemini verdict measured from a datacenter beats probing
//     google.com through every server from the phone;
//   - the proxy list UI, via EntryOf, which draws the YouTube/Gemini
//     badges from the same data.
//
// See docs/fleet_status.md in the outer vpn-leak-testing repo.
package fleet

import (
	"encoding/json"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

const (
	// FileName is the sidecar the Kotlin fetcher writes, next to
	// priorities.json / known_servers.json in the native home dir.
	FileName = "fleet_status.json"

	// FreshMaxAge bounds how long a verdict stays usable. The feed is
	// refreshed hourly, so this tolerates six missed runs before the
	// client falls back to on-device probing. Everything (icons,
	// classification, sorting) uses this one constant, so staleness can
	// never mean two different things in two layers.
	FreshMaxAge = 6 * time.Hour

	// reloadThrottle bounds how often the sidecar is stat()ed. classOf
	// runs per proxy per dial, so this must stay cheap.
	reloadThrottle = 2 * time.Second

	geminiAvailable = "available"
	geminiBlocked   = "blocked"

	// geminiUnknown is schema 2's explicit "no data": the sweep could
	// not obtain an answer, or the last real one aged out. It is not a
	// refusal and must never move a node into the Russian bucket —
	// only geminiBlocked does that. Anything unrecognised folds here,
	// including schema 1's "error", which conflated "our own check
	// failed" (quota exhausted, connection dropped) with a Google
	// verdict and used to push healthy nodes out of the foreign bucket.
	geminiUnknown = "unknown"

	classRU      = "ru"
	classForeign = "foreign"
)

// supportedSchemas lists the payload shapes this build understands. An
// unknown schema is ignored (and logged) rather than half-parsed.
//
// Schema 2 removed the "error" gemini value, kept the last real verdict
// across failed checks, and added the gemini_* freshness fields. Schema
// 1 payloads still parse correctly — "error" folds to "unknown" — so a
// sidecar written by an older build, or a producer rolled back, keeps
// working instead of silently disabling every verdict.
var supportedSchemas = map[int]bool{1: true, 2: true}

// transportSuffixes are the trailing `-xxx` tags the feed's node keys
// carry (`Швейцария-tcp`) but subscription proxy names usually do not.
// Stripped from both sides before matching.
var transportSuffixes = map[string]bool{
	"tcp":     true,
	"udp":     true,
	"ws":      true,
	"wss":     true,
	"grpc":    true,
	"h2":      true,
	"http":    true,
	"quic":    true,
	"tls":     true,
	"reality": true,
	"xhttp":   true,
}

// Entry is one node's verdict, as handed to the UI. Gemini is always
// one of "available", "blocked" or "unknown" — the feed's raw value is
// folded on the way in, so no consumer has to know the wire vocabulary.
type Entry struct {
	Proxy        string
	Name         string
	ExitIP       string
	YoutubeGL    string
	Gemini       string
	GeminiDetail string
	Reachable    bool
	CheckedAt    int64

	// GeminiCheckedAt is when the Gemini verdict was actually obtained.
	// Since schema 2 this can be much older than CheckedAt: a failed
	// check no longer overwrites the last real answer, so the row is
	// refreshed hourly while the verdict inside it may not be. Zero
	// means the feed did not report it (schema 1) and CheckedAt stands
	// in.
	GeminiCheckedAt int64
}

// freshAt applies the one freshness rule used everywhere. A timestamp
// in the future (clock skew between phone and prober) counts as fresh:
// the data is new, our clock is what is wrong.
func freshAt(at int64, now time.Time) bool {
	if at <= 0 {
		return false
	}
	return now.Sub(time.Unix(at, 0)) < FreshMaxAge
}

// fresh reports whether the node's row is recent enough to act on.
func (e Entry) fresh(now time.Time) bool {
	return freshAt(e.CheckedAt, now)
}

// geminiFresh reports whether the Gemini verdict itself is still
// usable. The producer already expires verdicts older than six hours
// into "unknown"; the rule is repeated here because the sidecar on disk
// can be much older than the sweep that wrote it (phone offline, every
// refresh failing), and what matters is the age of the measurement, not
// the age of the download.
func (e Entry) geminiFresh(now time.Time) bool {
	if e.GeminiCheckedAt > 0 {
		return freshAt(e.GeminiCheckedAt, now)
	}

	return freshAt(e.CheckedAt, now)
}

// rawNode mirrors only the fields acted on. The feed also carries
// gemini_fresh (was it re-measured in the last run) and
// gemini_age_seconds (the producer's own view of the age) — both are
// derivable from gemini_checked_at, which is the one we trust because
// it is comparable against our own clock.
type rawNode struct {
	Proxy           string  `json:"proxy"`
	Name            string  `json:"name"`
	ExitIP          string  `json:"exit_ip"`
	YoutubeGL       string  `json:"youtube_gl"`
	Gemini          string  `json:"gemini"`
	GeminiDetail    *string `json:"gemini_detail"`
	GeminiCheckedAt int64   `json:"gemini_checked_at"`
	Reachable       bool    `json:"reachable"`
	CheckedAt       int64   `json:"checked_at"`
}

type rawFile struct {
	Schema    int                `json:"schema"`
	UpdatedAt int64              `json:"updated_at"`
	Nodes     map[string]rawNode `json:"nodes"`
}

// snapshot is an immutable parse of one sidecar revision: normalised
// name -> entry. Built once per rewrite, then only read.
type snapshot struct {
	updatedAt int64
	byKey     map[string]*Entry
}

var (
	mu      sync.RWMutex
	current *snapshot

	// the file revision last *examined* (not necessarily loaded): a
	// corrupt or future-schema payload is remembered too, so it is
	// diagnosed once instead of re-read and re-logged every couple of
	// seconds until someone fixes it
	lastMod  int64
	lastSize int64

	// serialises reload work: concurrent callers wait for the in-flight
	// load instead of racing past it (see maybeReload)
	loadMu sync.Mutex

	// unix nanos of the end of the last load attempt, so the hot path
	// skips the syscall — and so waiters can tell "an attempt just
	// finished" from "an attempt is in flight"
	lastAttempt atomic.Int64
)

func path() string {
	return constant.Path.Resolve(FileName)
}

// lastAttemptAt reports when the last load attempt finished. Zero
// (never attempted) reads as the epoch, which is always "due".
func lastAttemptAt() time.Time {
	return time.Unix(0, lastAttempt.Load())
}

// normalizeName folds a name to its matching key: letters and digits
// only, lowercased. That drops the flag emoji, spaces and punctuation
// that subscription names carry ("🇨🇭 Швейцария" -> "швейцария") without
// needing a list of flags. Cyrillic folds through unicode.ToLower like
// any other script.
func normalizeName(name string) string {
	var b strings.Builder
	b.Grow(len(name))

	for _, r := range name {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(unicode.ToLower(r))
		}
	}

	return b.String()
}

// stripTransportSuffix removes one trailing "-tcp"/"-ws"/… tag. Must run
// *before* normalizeName, which eats the separating dash.
func stripTransportSuffix(name string) string {
	i := strings.LastIndex(name, "-")
	if i < 0 {
		return name
	}

	if transportSuffixes[strings.ToLower(strings.TrimSpace(name[i+1:]))] {
		return name[:i]
	}

	return name
}

// normalizeGemini folds the feed's value to one of the three the rest
// of the code knows. Everything unrecognised becomes "unknown": "we
// have no answer" is the only safe reading of a value we cannot
// interpret, because a wrong "blocked" would route traffic away from a
// working node.
func normalizeGemini(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case geminiAvailable:
		return geminiAvailable
	case geminiBlocked:
		return geminiBlocked
	default:
		return geminiUnknown
	}
}

// keysOf lists the normalised keys a name should be reachable by.
func keysOf(name string) []string {
	if name == "" {
		return nil
	}

	keys := []string{normalizeName(name)}

	if stripped := normalizeName(stripTransportSuffix(name)); stripped != keys[0] {
		keys = append(keys, stripped)
	}

	return keys
}

// buildIndex maps every normalised variant of every node to its entry.
// Keys that two different nodes both claim are dropped: silently picking
// one would mislabel a server, which is worse than showing no verdict.
func buildIndex(nodes map[string]rawNode) map[string]*Entry {
	byKey := make(map[string]*Entry, len(nodes)*2)
	ambiguous := map[string]bool{}

	for key, node := range nodes {
		detail := ""
		if node.GeminiDetail != nil {
			detail = *node.GeminiDetail
		}

		entry := &Entry{
			Proxy:           node.Proxy,
			Name:            node.Name,
			ExitIP:          node.ExitIP,
			YoutubeGL:       strings.ToUpper(node.YoutubeGL),
			Gemini:          normalizeGemini(node.Gemini),
			GeminiDetail:    detail,
			GeminiCheckedAt: node.GeminiCheckedAt,
			Reachable:       node.Reachable,
			CheckedAt:       node.CheckedAt,
		}

		for _, name := range []string{key, node.Proxy, node.Name} {
			for _, k := range keysOf(name) {
				if k == "" {
					continue
				}
				if prev, ok := byKey[k]; ok {
					if prev != entry {
						ambiguous[k] = true
					}
					continue
				}
				byKey[k] = entry
			}
		}
	}

	for k := range ambiguous {
		delete(byKey, k)
		log.Warnln("[Fleet] ambiguous node key %q claimed by several nodes, ignoring it", k)
	}

	return byKey
}

// maybeReload re-parses the sidecar when it changed on disk. Cheap and
// safe to call from hot paths: at most one stat per reloadThrottle, and
// a parse only when mtime or size moved.
//
// Concurrent callers *block* on the in-flight load rather than skipping
// it. An earlier version let them skip, which broke exactly the case
// that matters: at startup the UI query and a geo-split classification
// sweep hit this within microseconds of each other, one did the load and
// every other caller saw an empty snapshot, decided "no verdict" and
// probed google.com through each server anyway. Waiting costs one small
// file read; skipping cost the whole point of the feature.
func maybeReload() {
	if time.Since(lastAttemptAt()) < reloadThrottle {
		return
	}

	loadMu.Lock()
	defer loadMu.Unlock()

	// Re-check under the lock: whoever we queued behind has *finished*
	// an attempt by now (the timestamp is written at the end of one), so
	// a burst of lookups costs a single stat and every waiter leaves
	// with the snapshot the winner produced.
	if time.Since(lastAttemptAt()) < reloadThrottle {
		return
	}

	// stamped before the unlock, after reload() returns
	defer lastAttempt.Store(time.Now().UnixNano())

	reload()
}

// reload stats the sidecar and re-parses it when it changed. Always
// called with loadMu held.
func reload() {
	info, err := os.Stat(path())
	if err != nil {
		if !os.IsNotExist(err) {
			log.Warnln("[Fleet] stat %s: %s", FileName, err.Error())
		}

		// The file was removed (uninstall of the feature, storage
		// wipe): forget the snapshot instead of serving verdicts from a
		// file the user no longer has.
		mu.Lock()
		if current != nil {
			current, lastMod, lastSize = nil, 0, 0
			log.Infoln("[Fleet] %s disappeared, verdicts cleared", FileName)
		}
		mu.Unlock()

		return
	}

	mod, size := info.ModTime().UnixNano(), info.Size()

	mu.RLock()
	unchanged := mod == lastMod && size == lastSize
	mu.RUnlock()

	if unchanged {
		return
	}

	// Claim this revision before parsing it: whatever happens below, the
	// same bytes must not be examined again until the file changes.
	mu.Lock()
	lastMod, lastSize = mod, size
	mu.Unlock()

	data, err := os.ReadFile(path())
	if err != nil {
		log.Warnln("[Fleet] read %s: %s", FileName, err.Error())
		return
	}

	var parsed rawFile
	if err := json.Unmarshal(data, &parsed); err != nil {
		log.Warnln("[Fleet] parse %s: %s", FileName, err.Error())
		return
	}

	if !supportedSchemas[parsed.Schema] {
		log.Warnln("[Fleet] %s has unsupported schema %d — ignoring", FileName, parsed.Schema)
		return
	}

	snap := &snapshot{updatedAt: parsed.UpdatedAt, byKey: buildIndex(parsed.Nodes)}

	mu.Lock()
	current = snap
	mu.Unlock()

	age := time.Since(time.Unix(parsed.UpdatedAt, 0)).Truncate(time.Second)
	log.Infoln("[Fleet] loaded %d nodes (%d keys), feed age %s", len(parsed.Nodes), len(snap.byKey), age)
}

// lookup resolves a proxy name against the current snapshot.
func lookup(proxyName string) (Entry, bool) {
	maybeReload()

	mu.RLock()
	snap := current
	mu.RUnlock()

	if snap == nil {
		return Entry{}, false
	}

	for _, key := range keysOf(proxyName) {
		if entry, ok := snap.byKey[key]; ok {
			return *entry, true
		}
	}

	return Entry{}, false
}

// EntryOf returns the fresh verdict for a proxy, if the feed covers it.
// Stale entries are reported as absent so the UI shows nothing rather
// than something wrong.
func EntryOf(proxyName string) (Entry, bool) {
	entry, ok := lookup(proxyName)
	if !ok {
		return Entry{}, false
	}

	now := time.Now()
	if !entry.fresh(now) {
		return Entry{}, false
	}

	if entry.Gemini != geminiUnknown && !entry.geminiFresh(now) {
		// The row is current but the verdict inside it aged out. Report
		// exactly what the producer would report — "unknown" — so the
		// badge, the sort and the balancer stop acting on it at the
		// same moment, and the refusal text does not outlive the
		// refusal it explains.
		entry.Gemini = geminiUnknown
		entry.GeminiDetail = ""
	}

	return entry, true
}

// RegionOf answers outboundgroup.RegionProvider: Gemini is the priority
// signal — it answers only for non-Russian exits, so "available" means
// the node behaves as foreign and "blocked" means Google treats it as
// Russian. Only those two values decide anything: "unknown" and a node
// the prober could not reach carry no usable verdict and are left to
// whatever was already decided, or to the on-device probe.
//
// The country returned alongside is the YouTube attribution, which is a
// separate (and sometimes disagreeing) signal — it labels the exit, it
// does not decide the bucket.
func RegionOf(proxyName string) (class string, country string, ok bool) {
	entry, found := EntryOf(proxyName)
	if !found || !entry.Reachable {
		return "", "", false
	}

	switch entry.Gemini {
	case geminiAvailable:
		return classForeign, entry.YoutubeGL, true
	case geminiBlocked:
		return classRU, entry.YoutubeGL, true
	default:
		// "unknown" is the absence of data, not a verdict: deciding
		// here would move a node on no evidence. Answering "no verdict"
		// keeps the classification the groups already hold (see
		// regionStore.remember on the mihomo side) until the next
		// hourly snapshot brings a real answer.
		return "", "", false
	}
}

// Install wires the feed into the geo-split balancer groups. Called once
// from delegate.Init, after the home dir is known.
func Install() {
	outboundgroup.SetRegionProvider(RegionOf)
}
