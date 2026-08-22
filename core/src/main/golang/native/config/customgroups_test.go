package config

// NOTE: this package (cfa/native/config) transitively imports the
// android-only cfa/native/app, so `go test` does not link on a non-android
// host. Build/type-check it with the android toolchain instead:
//
//	GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
//	  go vet -tags "cmfa,with_gvisor" ./native/config/
//
// The cases below are pure-function regressions (matchRuleTarget,
// orderByPriority) guarding the C1/M1 review findings; they execute
// wherever an android test environment is available.

import (
	"reflect"
	"testing"
)

func TestMatchRuleTarget(t *testing.T) {
	cases := []struct {
		name       string
		rules      []string
		wantIdx    int
		wantTarget string
	}{
		{"no rules", nil, -1, ""},
		{"empty rules", []string{}, -1, ""},
		{"single MATCH", []string{"MATCH,DIRECT"}, 0, "DIRECT"},
		{"MATCH with spaces", []string{"MATCH, DIRECT "}, 0, "DIRECT"},
		{"lowercase match", []string{"match,PROXY"}, 0, "PROXY"},
		// M1 regression: MATCH carries no params, mihomo takes item[1] and
		// ignores the rest. Splitting on the first comma only would yield
		// "DIRECT,no-resolve" → unresolvable target → hard-fail.
		{"MATCH with trailing field", []string{"MATCH,DIRECT,no-resolve"}, 0, "DIRECT"},
		{"last MATCH wins", []string{"MATCH,DIRECT", "DOMAIN,x,PROXY", "MATCH,PROXY"}, 2, "PROXY"},
		{"non-empty but no MATCH", []string{"DOMAIN-SUFFIX,example.com,DIRECT"}, -1, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			idx, target := matchRuleTarget(c.rules)
			if idx != c.wantIdx || target != c.wantTarget {
				t.Fatalf("matchRuleTarget(%v) = (%d, %q), want (%d, %q)",
					c.rules, idx, target, c.wantIdx, c.wantTarget)
			}
		})
	}
}

func TestOrderByPriority(t *testing.T) {
	servers := []string{"NL", "CH", "PL", "DE", "FI"}

	cases := []struct {
		name       string
		priorities map[string]int
		want       []string
	}{
		{
			"no priorities keeps subscription order",
			nil,
			[]string{"NL", "CH", "PL", "DE", "FI"},
		},
		{
			"assigned sort first by number, rest keep order",
			map[string]int{"DE": 1, "PL": 2},
			[]string{"DE", "PL", "NL", "CH", "FI"},
		},
		{
			"ties fall back to subscription order (stable)",
			map[string]int{"CH": 5, "FI": 5},
			[]string{"CH", "FI", "NL", "PL", "DE"},
		},
		{
			"unknown server name in priorities is ignored",
			map[string]int{"XX": 1, "NL": 3},
			[]string{"NL", "CH", "PL", "DE", "FI"},
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := orderByPriority(servers, c.priorities)
			if !reflect.DeepEqual(got, c.want) {
				t.Fatalf("orderByPriority(%v, %v) = %v, want %v",
					servers, c.priorities, got, c.want)
			}
		})
	}
}
