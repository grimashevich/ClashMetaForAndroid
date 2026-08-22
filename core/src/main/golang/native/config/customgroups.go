package config

import (
	"encoding/json"
	"os"
	"sort"
	"strings"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// Custom balancer groups injected on top of every loaded profile, so
// they exist regardless of what the Marzban (or any other) subscription
// template defines. See docs/balancers_design.md in the outer
// vpn-leak-testing repo for the full design.
const (
	customGroupStrategy     = "🎛 Стратегия"
	customGroupPriority     = "⚡ Приоритет"
	customGroupRUFirst      = "🇷🇺 Сначала RU"
	customGroupForeignFirst = "🌍 Сначала зарубежные"

	prioritiesFileName   = "priorities.json"
	knownServersFileName = "known_servers.json"
)

type prioritiesFile struct {
	Version    int            `json:"version"`
	Priorities map[string]int `json:"priorities"`
}

type knownServersFile struct {
	Version int      `json:"version"`
	Servers []string `json:"servers"`
}

func prioritiesPath() string {
	return constant.Path.Resolve(prioritiesFileName)
}

func knownServersPath() string {
	return constant.Path.Resolve(knownServersFileName)
}

// readPriorities returns the user-assigned server priorities (written
// by the Kotlin "Приоритеты серверов" screen). Missing or malformed
// file simply means "no priorities assigned".
func readPriorities() map[string]int {
	data, err := os.ReadFile(prioritiesPath())
	if err != nil {
		return nil
	}

	var f prioritiesFile
	if err := json.Unmarshal(data, &f); err != nil {
		log.Warnln("[CustomGroups] parse %s: %s", prioritiesFileName, err.Error())
		return nil
	}

	return f.Priorities
}

// collectServerNames returns the plain-proxy names of a profile in
// subscription order (groups and providers excluded).
func collectServerNames(cfg *config.RawConfig) []string {
	servers := make([]string, 0, len(cfg.Proxy))
	for _, proxy := range cfg.Proxy {
		if name, ok := proxy["name"].(string); ok && name != "" {
			servers = append(servers, name)
		}
	}
	return servers
}

// writeKnownServers dumps the server names of the ACTIVE profile
// (subscription order) so the priorities UI can list servers without
// parsing YAML.
//
// Called only from the active Load() path — NOT from the processor
// chain. The processor runs on every UnmarshalAndPatch, including the
// background FetchAndValid() of *inactive* profiles; writing the sidecar
// there would clobber the global file with an inactive profile's servers
// and make the UI show the wrong list (Jules CRITICAL). known_servers.json
// is intentionally global (single active profile at a time), matching the
// global priorities.json the UI writes.
//
// Uses os.CreateTemp for a unique temp name so two concurrent reloads
// can't interleave writes onto one fixed ".tmp" path and rename a
// half-written payload over the live file (Jules MAJOR).
func writeKnownServers(servers []string) {
	f := knownServersFile{Version: 1, Servers: servers}
	data, err := json.Marshal(&f)
	if err != nil {
		return
	}

	dir := constant.Path.HomeDir()
	tmp, err := os.CreateTemp(dir, knownServersFileName+".*.tmp")
	if err != nil {
		log.Warnln("[CustomGroups] create temp for %s: %s", knownServersFileName, err.Error())
		return
	}
	tmpName := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		log.Warnln("[CustomGroups] write %s: %s", knownServersFileName, err.Error())
		return
	}
	if err := tmp.Chmod(0o600); err != nil {
		log.Warnln("[CustomGroups] chmod %s: %s", knownServersFileName, err.Error())
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		log.Warnln("[CustomGroups] close %s: %s", knownServersFileName, err.Error())
		return
	}
	if err := os.Rename(tmpName, knownServersPath()); err != nil {
		os.Remove(tmpName)
		log.Warnln("[CustomGroups] rename %s: %s", knownServersFileName, err.Error())
	}
}

// orderByPriority sorts server names by (assigned priority asc,
// subscription order). Servers without an assigned priority keep
// subscription order after all prioritized ones.
func orderByPriority(servers []string, priorities map[string]int) []string {
	type entry struct {
		name     string
		priority int
		assigned bool
		index    int
	}

	entries := make([]entry, 0, len(servers))
	for i, name := range servers {
		p, ok := priorities[name]
		entries = append(entries, entry{name: name, priority: p, assigned: ok, index: i})
	}

	sort.SliceStable(entries, func(i, j int) bool {
		a, b := entries[i], entries[j]
		if a.assigned != b.assigned {
			return a.assigned
		}
		if a.assigned && a.priority != b.priority {
			return a.priority < b.priority
		}
		return a.index < b.index
	})

	ordered := make([]string, 0, len(entries))
	for _, e := range entries {
		ordered = append(ordered, e.name)
	}
	return ordered
}

// matchRuleTarget finds the target of the last MATCH rule, or "" when
// the profile has none (e.g. `rules: []` — mihomo then routes unmatched
// traffic to DIRECT).
//
// MATCH carries no payload or params, so mihomo's rule parser takes only
// the SECOND comma field as the target and ignores any trailing fields
// (see rules/common/base.go: `case "MATCH": target = item[1]`). We must
// match that exactly: splitting on the first comma only and keeping the
// remainder would turn a valid `MATCH,DIRECT,no-resolve` into the target
// "DIRECT,no-resolve", which resolves to no proxy/group and hard-fails
// the whole config load.
func matchRuleTarget(rules []string) (int, string) {
	for i := len(rules) - 1; i >= 0; i-- {
		parts := strings.Split(rules[i], ",")
		if len(parts) >= 2 && strings.EqualFold(strings.TrimSpace(parts[0]), "MATCH") {
			return i, strings.TrimSpace(parts[1])
		}
	}
	return -1, ""
}

// patchCustomGroups injects the custom balancer groups over the loaded
// profile:
//
//   - "⚡ Приоритет"          fallback ordered by user-assigned priorities
//   - "🇷🇺 Сначала RU"        geo-split preferring Google-RU servers
//   - "🌍 Сначала зарубежные" geo-split preferring non-RU servers
//   - "🎛 Стратегия"          selector choosing between the above
//
// The MATCH rule (or a new one, when the profile has no rules) is
// pointed at the strategy selector. The selector's first entry is the
// profile's previous MATCH target (DIRECT when there were no rules), so
// default routing behaviour is unchanged until the user explicitly
// picks a balancer.
func patchCustomGroups(cfg *config.RawConfig, _ string) error {
	servers := collectServerNames(cfg)

	// NOTE: the sidecar (known_servers.json) is deliberately NOT written
	// here. This processor runs on every UnmarshalAndPatch, including the
	// background validation of inactive profiles; the sidecar is written
	// only from the active Load() path (see writeKnownServers).

	if len(servers) == 0 {
		// provider-based or empty profile: nothing to group
		return nil
	}

	// Collision set must include BOTH existing group names AND proxy
	// names: mihomo hard-fails the whole load if an injected group name
	// duplicates a proxy name too (config.go parses proxies into the same
	// name space it checks groups against — "the duplicate name"). So a
	// subscription with a server literally named like one of our groups
	// would otherwise turn an already-valid profile unloadable.
	existing := map[string]bool{}
	for _, name := range servers {
		existing[name] = true
	}
	for _, group := range cfg.ProxyGroup {
		if name, ok := group["name"].(string); ok {
			existing[name] = true
		}
	}
	for _, name := range []string{customGroupStrategy, customGroupPriority, customGroupRUFirst, customGroupForeignFirst} {
		if existing[name] {
			// The profile already defines one of our names. Re-injecting
			// would make mihomo fail the whole load on the duplicate, so
			// leave the profile exactly as the subscription defined it.
			log.Warnln("[CustomGroups] profile already defines name %q, skipping injection", name)
			return nil
		}
	}

	matchIdx, matchTarget := matchRuleTarget(cfg.Rule)
	if matchTarget == "" {
		matchTarget = "DIRECT"
	}

	strategyChoices := []string{matchTarget, customGroupPriority, customGroupRUFirst, customGroupForeignFirst}
	// Existing top-level groups (e.g. the subscription's url-test) stay
	// selectable as strategies too, unless one of them already is the
	// MATCH target (then it's already first in the list).
	for _, group := range cfg.ProxyGroup {
		if name, ok := group["name"].(string); ok && name != matchTarget {
			strategyChoices = append(strategyChoices, name)
		}
	}

	injected := []map[string]any{
		{
			"name":    customGroupStrategy,
			"type":    "select",
			"proxies": strategyChoices,
		},
		{
			"name":    customGroupPriority,
			"type":    "fallback",
			"proxies": orderByPriority(servers, readPriorities()),
		},
		{
			"name":    customGroupRUFirst,
			"type":    "geo-split",
			"prefer":  "ru",
			"proxies": servers,
		},
		{
			"name":    customGroupForeignFirst,
			"type":    "geo-split",
			"prefer":  "foreign",
			"proxies": servers,
		},
	}

	cfg.ProxyGroup = append(injected, cfg.ProxyGroup...)

	newMatchRule := "MATCH," + customGroupStrategy
	if matchIdx >= 0 {
		cfg.Rule[matchIdx] = newMatchRule
	} else {
		cfg.Rule = append(cfg.Rule, newMatchRule)
	}

	return nil
}
