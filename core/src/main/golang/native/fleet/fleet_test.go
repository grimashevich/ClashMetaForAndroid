package fleet

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/metacubex/mihomo/constant"
)

// resetCache drops the package-level snapshot so each test starts from
// a cold read of its own temp home.
func resetCache() {
	mu.Lock()
	current, lastMod, lastSize = nil, 0, 0
	mu.Unlock()

	lastAttempt.Store(0)
}

// writeFeed points the native home dir at a temp dir and writes a feed
// into it, returning the path so tests can rewrite it.
func writeFeed(t *testing.T, body string) string {
	t.Helper()

	home := t.TempDir()
	constant.SetHomeDir(home)
	resetCache()

	path := filepath.Join(home, FileName)
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatalf("write feed: %s", err)
	}

	return path
}

func feed(nodes string) string {
	return feedSchema(2, nodes)
}

func feedSchema(schema int, nodes string) string {
	return fmt.Sprintf(`{"schema":%d,"updated_at":%d,"nodes":{%s}}`,
		schema, time.Now().Unix(), nodes)
}

// node writes a schema 2 row whose Gemini verdict was measured in the
// same sweep that produced the row.
func node(key, name, gemini, gl string, reachable bool, checkedAt time.Time) string {
	return nodeAt(key, name, gemini, gl, reachable, checkedAt, checkedAt)
}

// nodeAt separates the two timestamps schema 2 distinguishes: when the
// row was refreshed, and when the Gemini verdict inside it was actually
// obtained.
func nodeAt(key, name, gemini, gl string, reachable bool, checkedAt, geminiCheckedAt time.Time) string {
	return nodeAtBoth(key, name, gemini, gl, reachable, checkedAt, geminiCheckedAt, checkedAt)
}

// nodeAtBoth additionally backdates the YouTube attribution, which the
// feed keeps just as sticky as the Gemini verdict.
func nodeAtBoth(key, name, gemini, gl string, reachable bool, checkedAt, geminiCheckedAt, youtubeCheckedAt time.Time) string {
	return fmt.Sprintf(
		`%q:{"proxy":%q,"name":%q,"exit_ip":"203.0.113.7","youtube_gl":%q,`+
			`"youtube_gl_fresh":true,"youtube_gl_checked_at":%d,`+
			`"gemini":%q,"gemini_fresh":%t,"gemini_checked_at":%d,"gemini_age_seconds":%d,`+
			`"gemini_probe":"ok","gemini_detail":%q,"measured":true,"reachable":%t,"checked_at":%d}`,
		key, key, name, gl, youtubeCheckedAt.Unix(),
		gemini, geminiCheckedAt.Equal(checkedAt), geminiCheckedAt.Unix(),
		int64(checkedAt.Sub(geminiCheckedAt).Seconds()),
		"detail for "+name, reachable, checkedAt.Unix(),
	)
}

func TestNormalizeName(t *testing.T) {
	tests := []struct {
		in   string
		want string
	}{
		{"🇨🇭 Швейцария", "швейцария"},
		{"Швейцария", "швейцария"},
		{"🇫🇮 Финляндия ", "финляндия"},
		{"Швейцария-2", "швейцария2"},
		{"NL-Amsterdam", "nlamsterdam"},
		{"", ""},
	}

	for _, tt := range tests {
		if got := normalizeName(tt.in); got != tt.want {
			t.Errorf("normalizeName(%q) = %q, want %q", tt.in, got, tt.want)
		}
	}
}

func TestStripTransportSuffix(t *testing.T) {
	tests := []struct {
		in   string
		want string
	}{
		{"Швейцария-tcp", "Швейцария"},
		{"Швейцария-2-tcp", "Швейцария-2"},
		{"Швейцария-TCP", "Швейцария"},
		{"Node-ws", "Node"},
		// not a transport tag: must survive, or "Германия-Берлин" would
		// collapse onto "Германия"
		{"Германия-Берлин", "Германия-Берлин"},
		{"Швейцария", "Швейцария"},
	}

	for _, tt := range tests {
		if got := stripTransportSuffix(tt.in); got != tt.want {
			t.Errorf("stripTransportSuffix(%q) = %q, want %q", tt.in, got, tt.want)
		}
	}
}

func TestEntryOfMatchesSubscriptionNames(t *testing.T) {
	now := time.Now()
	writeFeed(t, feed(
		node("Швейцария-tcp", "Швейцария", "available", "CH", true, now)+","+
			node("Швейцария-2-tcp", "Швейцария-2", "available", "CH", true, now)+","+
			node("Финляндия-tcp", "Финляндия", "blocked", "RU", true, now),
	))

	// the flag emoji and the -tcp tag must both fold away, and the
	// numbered twin must not be swallowed by its prefix twin
	cases := map[string]string{
		"🇨🇭 Швейцария":   "Швейцария",
		"🇨🇭 Швейцария-2": "Швейцария-2",
		"🇫🇮 Финляндия":   "Финляндия",
		"Финляндия-tcp":  "Финляндия",
	}

	for proxy, want := range cases {
		entry, ok := EntryOf(proxy)
		if !ok {
			t.Fatalf("EntryOf(%q): no verdict, want %q", proxy, want)
		}
		if entry.Name != want {
			t.Errorf("EntryOf(%q).Name = %q, want %q", proxy, entry.Name, want)
		}
	}

	if _, ok := EntryOf("🇱🇻 Латвия"); ok {
		t.Error("EntryOf(unknown node) returned a verdict")
	}
}

func TestEntryOfRejectsStale(t *testing.T) {
	stale := time.Now().Add(-FreshMaxAge - time.Minute)
	writeFeed(t, feed(node("Литва-tcp", "Литва", "available", "LT", true, stale)))

	if _, ok := EntryOf("🇱🇹 Литва"); ok {
		t.Error("stale entry was served; want it treated as absent")
	}
}

func TestRegionOf(t *testing.T) {
	now := time.Now()
	writeFeed(t, feed(
		node("Швейцария-tcp", "Швейцария", "available", "CH", true, now)+","+
			node("Литва-tcp", "Литва", "blocked", "RU", true, now)+","+
			node("Италия-tcp", "Италия", "available", "RU", true, now)+","+
			node("Чехия-tcp", "Чехия", "available", "CZ", false, now)+","+
			node("Латвия-tcp", "Латвия", "unknown", "LV", true, now),
	))

	tests := []struct {
		proxy       string
		wantClass   string
		wantCountry string
		wantOK      bool
	}{
		{"🇨🇭 Швейцария", classForeign, "CH", true},
		{"🇱🇹 Литва", classRU, "RU", true},
		// Gemini wins over the YouTube attribution: an exit YouTube
		// places in RU but Gemini answers for is still a foreign exit
		{"🇮🇹 Италия", classForeign, "RU", true},
		// prober could not reach it: no usable verdict
		{"🇨🇿 Чехия", "", "", false},
		// no data for this node: not a verdict, and specifically not a
		// reason to treat it as Russian
		{"🇱🇻 Латвия", "", "", false},
		{"🇩🇪 Германия", "", "", false},
	}

	for _, tt := range tests {
		class, country, ok := RegionOf(tt.proxy)
		if ok != tt.wantOK || class != tt.wantClass || country != tt.wantCountry {
			t.Errorf("RegionOf(%q) = (%q, %q, %v), want (%q, %q, %v)",
				tt.proxy, class, country, ok, tt.wantClass, tt.wantCountry, tt.wantOK)
		}
	}
}

// The Gemini verdict is the one signal that decides routing, so the
// three values it can take are pinned down here rather than left to the
// callers' switch statements.
func TestGeminiValues(t *testing.T) {
	now := time.Now()
	writeFeed(t, feed(
		node("Швейцария-tcp", "Швейцария", "available", "CH", true, now)+","+
			node("Литва-tcp", "Литва", "blocked", "RU", true, now)+","+
			node("Латвия-tcp", "Латвия", "unknown", "LV", true, now)+","+
			// schema 1's value, and anything a future producer invents
			node("Чехия-tcp", "Чехия", "error", "CZ", true, now)+","+
			node("Польша-tcp", "Польша", "", "PL", true, now),
	))

	tests := []struct {
		proxy  string
		gemini string
	}{
		{"🇨🇭 Швейцария", geminiAvailable},
		{"🇱🇹 Литва", geminiBlocked},
		{"🇱🇻 Латвия", geminiUnknown},
		{"🇨🇿 Чехия", geminiUnknown},
		{"🇵🇱 Польша", geminiUnknown},
	}

	for _, tt := range tests {
		entry, ok := EntryOf(tt.proxy)
		if !ok {
			t.Errorf("EntryOf(%q) missing; the row must still be served", tt.proxy)
			continue
		}
		if entry.Gemini != tt.gemini {
			t.Errorf("EntryOf(%q).Gemini = %q, want %q", tt.proxy, entry.Gemini, tt.gemini)
		}

		_, _, verdict := RegionOf(tt.proxy)
		if want := tt.gemini != geminiUnknown; verdict != want {
			t.Errorf("RegionOf(%q) ok = %v, want %v", tt.proxy, verdict, want)
		}
	}
}

// The point of schema 2: a check that could not run does not erase the
// last real answer, so a verdict older than its row still counts.
func TestGeminiVerdictSurvivesItsRow(t *testing.T) {
	now := time.Now()
	measured := now.Add(-3 * time.Hour)
	writeFeed(t, feed(nodeAt("Литва-tcp", "Литва", "blocked", "RU", true, now, measured)))

	class, _, ok := RegionOf("🇱🇹 Литва")
	if !ok || class != classRU {
		t.Fatalf("RegionOf = (%q, %v), want (ru, true): a verdict inside the freshness window counts", class, ok)
	}
}

// … but only up to the same six hours everything else uses. Past that
// the row stays (exit IP, YouTube) while the verdict reads "unknown".
func TestStaleGeminiVerdictReadsUnknown(t *testing.T) {
	now := time.Now()
	measured := now.Add(-FreshMaxAge - time.Minute)
	writeFeed(t, feed(nodeAt("Литва-tcp", "Литва", "blocked", "RU", true, now, measured)))

	entry, ok := EntryOf("🇱🇹 Литва")
	if !ok {
		t.Fatal("row was dropped; want it served with an unknown verdict")
	}
	if entry.Gemini != geminiUnknown {
		t.Errorf("Gemini = %q, want unknown", entry.Gemini)
	}
	if entry.GeminiDetail != "" {
		t.Errorf("GeminiDetail = %q, want it cleared with the verdict", entry.GeminiDetail)
	}
	if entry.ExitIP == "" {
		t.Error("ExitIP was cleared; only the verdict expires")
	}
	if _, _, verdict := RegionOf("🇱🇹 Литва"); verdict {
		t.Error("expired verdict still classified the node")
	}
}

// The country attribution expires on the same six-hour rule, and on its
// own clock: it labels the exit, so a badge must not claim a country
// nobody has measured lately. It decides nothing about routing.
func TestStaleYoutubeAttributionDrops(t *testing.T) {
	now := time.Now()
	writeFeed(t, feed(nodeAtBoth("Литва-tcp", "Литва", "blocked", "RU", true,
		now, now, now.Add(-FreshMaxAge-time.Minute))))

	entry, ok := EntryOf("🇱🇹 Литва")
	if !ok {
		t.Fatal("row was dropped; want it served without the country")
	}
	if entry.YoutubeGL != "" {
		t.Errorf("YoutubeGL = %q, want it cleared", entry.YoutubeGL)
	}

	class, country, verdict := RegionOf("🇱🇹 Литва")
	if !verdict || class != classRU {
		t.Errorf("RegionOf = (%q, %v), want (ru, true): the Gemini verdict is unaffected", class, verdict)
	}
	if country != "" {
		t.Errorf("country = %q, want empty", country)
	}
}

// A sidecar left behind by an older build must keep working rather than
// disabling every verdict.
func TestSchema1StillParses(t *testing.T) {
	now := time.Now()
	writeFeed(t, feedSchema(1,
		node("Швейцария-tcp", "Швейцария", "available", "CH", true, now)))

	class, _, ok := RegionOf("🇨🇭 Швейцария")
	if !ok || class != classForeign {
		t.Fatalf("RegionOf on a schema 1 feed = (%q, %v), want (foreign, true)", class, ok)
	}
}

func TestUnknownSchemaIgnored(t *testing.T) {
	home := t.TempDir()
	constant.SetHomeDir(home)
	resetCache()

	body := fmt.Sprintf(`{"schema":99,"updated_at":%d,"nodes":{%s}}`,
		time.Now().Unix(), node("Литва-tcp", "Литва", "available", "LT", true, time.Now()))

	if err := os.WriteFile(filepath.Join(home, FileName), []byte(body), 0o644); err != nil {
		t.Fatalf("write feed: %s", err)
	}

	if _, ok := EntryOf("🇱🇹 Литва"); ok {
		t.Error("a future schema was parsed; want it ignored")
	}
}

func TestAmbiguousKeysDropped(t *testing.T) {
	now := time.Now()
	// two nodes whose names normalise to the same key: neither may win
	writeFeed(t, feed(
		node("Литва-tcp", "Литва", "available", "LT", true, now)+","+
			node("Литва-ws", "Литва", "blocked", "RU", true, now),
	))

	if _, ok := EntryOf("🇱🇹 Литва"); ok {
		t.Error("ambiguous key resolved to a node; want no verdict")
	}
}

func TestReloadPicksUpRewrite(t *testing.T) {
	now := time.Now()
	path := writeFeed(t, feed(node("Литва-tcp", "Литва", "blocked", "RU", true, now)))

	if class, _, _ := RegionOf("🇱🇹 Литва"); class != classRU {
		t.Fatalf("initial class = %q, want ru", class)
	}

	// a refresh flips the verdict; the sidecar is rewritten in place
	if err := os.WriteFile(path, []byte(feed(node("Литва-tcp", "Литва", "available", "CH", true, now))), 0o644); err != nil {
		t.Fatalf("rewrite feed: %s", err)
	}

	// the stat is throttled, so a reader inside the window still sees
	// the old snapshot — that is intended, not a bug
	lastAttempt.Store(0)

	if class, country, _ := RegionOf("🇱🇹 Литва"); class != classForeign || country != "CH" {
		t.Fatalf("after rewrite = (%q, %q), want (foreign, CH)", class, country)
	}
}

func TestMissingFeedIsNotAVerdict(t *testing.T) {
	constant.SetHomeDir(t.TempDir())
	resetCache()

	if _, ok := EntryOf("🇱🇹 Литва"); ok {
		t.Error("verdict served without a feed file")
	}
}

// TestConcurrentColdLookups pins the startup race that shipped in the
// first build: a burst of lookups arriving before the first load must
// all see the snapshot, not just whichever goroutine won the throttle.
// When they don't, geo-split treats every server as unclassified and
// probes google.com through all of them — the exact cost this feature
// exists to avoid.
func TestConcurrentColdLookups(t *testing.T) {
	now := time.Now()
	writeFeed(t, feed(
		node("Швейцария-tcp", "Швейцария", "available", "CH", true, now)+","+
			node("Литва-tcp", "Литва", "blocked", "RU", true, now),
	))

	const workers = 16

	var wg sync.WaitGroup
	start := make(chan struct{})
	misses := make(chan string, workers)

	for i := 0; i < workers; i++ {
		wg.Add(1)

		go func(i int) {
			defer wg.Done()

			<-start

			name := "🇨🇭 Швейцария"
			want := classForeign
			if i%2 == 1 {
				name, want = "🇱🇹 Литва", classRU
			}

			if class, _, ok := RegionOf(name); !ok || class != want {
				misses <- name
			}
		}(i)
	}

	close(start)
	wg.Wait()
	close(misses)

	if n := len(misses); n > 0 {
		t.Fatalf("%d/%d concurrent cold lookups missed the snapshot", n, workers)
	}
}

// TestCorruptRewriteKeepsPreviousVerdicts covers the case where a
// refresh lands a payload we cannot use: the previous verdicts must
// survive. Serving nothing would silently hand every server back to the
// on-device probe; serving garbage would mislabel them.
func TestCorruptRewriteKeepsPreviousVerdicts(t *testing.T) {
	now := time.Now()
	path := writeFeed(t, feed(node("Литва-tcp", "Литва", "blocked", "RU", true, now)))

	if class, _, ok := RegionOf("🇱🇹 Литва"); !ok || class != classRU {
		t.Fatalf("initial class = %q (ok=%v), want ru", class, ok)
	}

	if err := os.WriteFile(path, []byte("<html>captcha</html>"), 0o644); err != nil {
		t.Fatalf("rewrite feed: %s", err)
	}

	lastAttempt.Store(0)

	if class, _, ok := RegionOf("🇱🇹 Литва"); !ok || class != classRU {
		t.Fatalf("after corrupt rewrite = %q (ok=%v), want the previous ru verdict", class, ok)
	}
}
