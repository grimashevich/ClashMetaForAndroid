package fleet

import (
	"fmt"
	"os"
	"path/filepath"
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

	lastStat.Store(0)
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
	return fmt.Sprintf(`{"schema":1,"updated_at":%d,"nodes":{%s}}`, time.Now().Unix(), nodes)
}

func node(key, name, gemini, gl string, reachable bool, checkedAt time.Time) string {
	return fmt.Sprintf(
		`%q:{"proxy":%q,"name":%q,"exit_ip":"203.0.113.7","youtube_gl":%q,"gemini":%q,"gemini_detail":null,"reachable":%t,"checked_at":%d}`,
		key, key, name, gl, gemini, reachable, checkedAt.Unix(),
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
			node("Латвия-tcp", "Латвия", "", "LV", true, now),
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
		// gemini field empty: no usable verdict
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
	lastStat.Store(0)

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
