package tunnel

import (
	"math"
	"sort"
	"strings"

	"github.com/dlclark/regexp2"

	"cfa/native/fleet"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

type SortMode int

const (
	Default SortMode = iota
	Title
	Delay
	Fleet
)

type Proxy struct {
	Name     string `json:"name"`
	Title    string `json:"title"`
	Subtitle string `json:"subtitle"`
	Type     string `json:"type"`
	Delay    int    `json:"delay"`
	IsGroup  bool   `json:"isGroup"`

	// Fleet-status annotation, empty/zero when the hourly feed has no
	// fresh verdict for this server (unknown node, stale data, or a
	// nested group — groups are never annotated). The UI draws the
	// YouTube/Gemini badges from these fields; see cfa/native/fleet.
	FleetGemini       string `json:"fleetGemini"`
	FleetGeminiDetail string `json:"fleetGeminiDetail"`
	FleetYoutubeGl    string `json:"fleetYoutubeGl"`
	FleetExitIp       string `json:"fleetExitIp"`
	FleetReachable    bool   `json:"fleetReachable"`
	FleetCheckedAt    int64  `json:"fleetCheckedAt"`
}

type ProxyGroup struct {
	Type    string   `json:"type"`
	Now     string   `json:"now"`
	Proxies []*Proxy `json:"proxies"`
}

type sortableProxyList struct {
	list []*Proxy
	less func(a, b *Proxy) bool
}

func (s *sortableProxyList) Len() int {
	return len(s.list)
}

func (s *sortableProxyList) Less(i, j int) bool {
	return s.less(s.list[i], s.list[j])
}

func (s *sortableProxyList) Swap(i, j int) {
	s.list[i], s.list[j] = s.list[j], s.list[i]
}

func QueryProxyGroupNames(excludeNotSelectable bool) []string {
	mode := tunnel.Mode()

	if mode == tunnel.Direct {
		return []string{}
	}

	global := tunnel.Proxies()["GLOBAL"].Adapter().(outboundgroup.ProxyGroup)
	proxies := global.Providers()[0].Proxies()
	result := make([]string, 0, len(proxies)+1)

	if mode == tunnel.Global {
		result = append(result, "GLOBAL")
	}

	for _, p := range proxies {
		if g, ok := p.Adapter().(outboundgroup.ProxyGroup); ok {
			if !excludeNotSelectable || p.Type() == C.Selector {
				if g.Hidden() {
					continue
				}
				result = append(result, p.Name())
			}
		}
	}

	return result
}

func QueryProxyGroup(name string, sortMode SortMode, uiSubtitlePattern *regexp2.Regexp) *ProxyGroup {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Query group `%s`: not found", name)

		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Query group `%s`: invalid type %s", name, p.Type().String())

		return nil
	}

	proxies := convertProxies(g.Proxies(), uiSubtitlePattern)
	// 	proxies := collectProviders(g.Providers(), uiSubtitlePattern)

	switch sortMode {
	case Title:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				return strings.Compare(a.Title, b.Title) < 0
			},
		}

		sort.Sort(wrapper)
	case Delay:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				return a.Delay < b.Delay
			},
		}

		sort.Sort(wrapper)
	case Fleet:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				if ra, rb := fleetRank(a), fleetRank(b); ra != rb {
					return ra < rb
				}

				if da, db := delayRank(a.Delay), delayRank(b.Delay); da != db {
					return da < db
				}

				return strings.Compare(a.Title, b.Title) < 0
			},
		}

		sort.Sort(wrapper)
	case Default:
	default:
	}

	return &ProxyGroup{
		Type:    g.Type().String(),
		Now:     g.Now(),
		Proxies: proxies,
	}
}

func PatchSelector(selector, name string) bool {
	p := tunnel.Proxies()[selector]

	if p == nil {
		log.Warnln("Patch selector `%s`: not found", selector)

		return false
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	s, ok := g.(outboundgroup.SelectAble)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	if err := s.Set(name); err != nil {
		log.Warnln("Patch selector `%s`: %s", selector, err.Error())
	}

	log.Infoln("Patch selector %s -> %s", selector, name)

	closeConnByGroup(selector)

	return true
}

// fleetRank buckets a proxy for the fleet sort: exits that behave as
// foreign first, Russian-looking exits next, everything without a
// verdict (unknown nodes, stale feed, nested groups) last.
func fleetRank(p *Proxy) int {
	switch {
	case !p.FleetReachable:
		return 2
	case p.FleetGemini == "available":
		return 0
	case p.FleetGemini == "blocked":
		return 1
	default:
		return 2
	}
}

// delayRank orders latencies, pushing "no measurement" (0, what
// LastDelayForTestUrl reports for untested or dead servers) to the end
// instead of to the front where a plain numeric compare puts it.
func delayRank(delay int) int {
	if delay <= 0 {
		return math.MaxInt
	}

	return delay
}

// annotateFleet copies the fleet-status verdict onto a proxy row.
// Groups are skipped: the feed describes servers, and a group's badges
// would be whatever its name happened to normalise to.
func annotateFleet(p *Proxy) {
	if p.IsGroup {
		return
	}

	entry, ok := fleet.EntryOf(p.Name)
	if !ok {
		return
	}

	p.FleetGemini = entry.Gemini
	p.FleetGeminiDetail = entry.GeminiDetail
	p.FleetYoutubeGl = entry.YoutubeGL
	p.FleetExitIp = entry.ExitIP
	p.FleetReachable = entry.Reachable
	p.FleetCheckedAt = entry.CheckedAt
}

func convertProxies(proxies []C.Proxy, uiSubtitlePattern *regexp2.Regexp) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range proxies {
		name := p.Name()
		title := name
		subtitle := p.Type().String()

		if uiSubtitlePattern != nil {
			if _, ok := p.Adapter().(outboundgroup.ProxyGroup); !ok {
				runes := []rune(name)
				match, err := uiSubtitlePattern.FindRunesMatch(runes)
				if err == nil && match != nil {
					title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
					subtitle = string(runes[match.Index : match.Index+match.Length])
				}
			}
		}
		testURL := "https://www.gstatic.com/generate_204"
		for k := range p.ExtraDelayHistories() {
			if len(k) > 0 {
				testURL = k
				break
			}
		}
		_, isGroup := p.Adapter().(outboundgroup.ProxyGroup)

		// The "[RU]" country-code suffix that used to be appended here
		// (from the geo-split classifier) is gone: the proxy list now
		// draws YouTube/Gemini badges from the fleet feed instead, and
		// two annotations of the same fact only competed for row width.
		// Title stays a pure display string; Name is the selection
		// identity used by PatchSelector/Set and must never be decorated.
		row := &Proxy{
			Name:     name,
			Title:    strings.TrimSpace(title),
			Subtitle: strings.TrimSpace(subtitle),
			Type:     p.Type().String(),
			Delay:    int(p.LastDelayForTestUrl(testURL)),
			IsGroup:  isGroup,
		}

		annotateFleet(row)

		result = append(result, row)
	}
	return result
}

func collectProviders(providers []provider.ProxyProvider, uiSubtitlePattern *regexp2.Regexp) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range providers {
		for _, px := range p.Proxies() {
			name := px.Name()
			title := name
			subtitle := px.Type().String()

			if uiSubtitlePattern != nil {
				if _, ok := px.Adapter().(outboundgroup.ProxyGroup); !ok {
					runes := []rune(name)
					match, err := uiSubtitlePattern.FindRunesMatch(runes)
					if err == nil && match != nil {
						title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
						subtitle = string(runes[match.Index : match.Index+match.Length])
					}
				}
			}

			testURL := "https://www.gstatic.com/generate_204"
			for k := range px.ExtraDelayHistories() {
				if len(k) > 0 {
					testURL = k
					break
				}
			}
			_, isGroup := px.Adapter().(outboundgroup.ProxyGroup)

			// Same fleet annotation as convertProxies (kept in sync;
			// this provider path is currently unused but mirrors the live one).
			row := &Proxy{
				Name:     name,
				Title:    strings.TrimSpace(title),
				Subtitle: strings.TrimSpace(subtitle),
				Type:     px.Type().String(),
				Delay:    int(px.LastDelayForTestUrl(testURL)),
				IsGroup:  isGroup,
			}

			annotateFleet(row)

			result = append(result, row)
		}
	}

	return result
}
