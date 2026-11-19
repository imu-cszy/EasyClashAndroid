package config

import (
	"encoding/json"
	"sync"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

// EasyClash: EasyTier 代理链合并注入。
//
// 背景：CMFA 的 Override 机制（patchOverride）把 JSON 直接 decode 进整个 RawConfig，
// 数组字段（proxies/proxy-groups）会被整体替换，无法用于向用户配置"追加"节点。
// 因此本文件提供独立的 session 级注入通道：
//
//   - Kotlin 侧在每次内核启动前调用 Bridge.nativeSetEasyTierProxies(json)；
//   - json 形如 {"proxies":[...],"proxy-groups":[...]}（见 MihomoConfigComposer）；
//   - patchEasyTier 在 patchOverride 之后执行：先剔除历史注入（按保留名），再追加；
//   - 内容仅存内存，不落盘（凭据安全，见 docs/SECURITY.md）。

var (
	easyTierMu        sync.RWMutex
	easyTierInjection string
)

// SetEasyTierProxies 设置（或以空串清除）EasyTier 注入内容。session 级，不持久化。
func SetEasyTierProxies(content string) {
	easyTierMu.Lock()
	defer easyTierMu.Unlock()
	easyTierInjection = content
}

func getEasyTierInjection() string {
	easyTierMu.RLock()
	defer easyTierMu.RUnlock()
	return easyTierInjection
}

type easyTierInjectionPayload struct {
	Proxies     []map[string]any `json:"proxies"`
	ProxyGroups []map[string]any `json:"proxy-groups"`
	// 需要前置的规则（如 UID,<本应用>,DIRECT，防止 EasyTier underlay 被 TUN 捕获成环）
	Rules []string `json:"rules"`
}

func patchEasyTier(cfg *config.RawConfig, _ string) error {
	content := getEasyTierInjection()
	if content == "" {
		return nil
	}

	var payload easyTierInjectionPayload
	if err := json.Unmarshal([]byte(content), &payload); err != nil {
		// 注入内容非法不阻断用户配置加载，仅告警
		log.Warnln("EasyClash: invalid easytier injection payload: %s", err.Error())
		return nil
	}

	injected := make(map[string]bool)
	for _, item := range payload.Proxies {
		if name, ok := item["name"].(string); ok {
			injected[name] = true
		}
	}
	for _, item := range payload.ProxyGroups {
		if name, ok := item["name"].(string); ok {
			injected[name] = true
		}
	}

	// 剔除历史注入/同名残留，避免重复 proxy 报错
	if len(injected) > 0 {
		cfg.Proxy = filterNamed(cfg.Proxy, injected)
		cfg.ProxyGroup = filterNamed(cfg.ProxyGroup, injected)
	}

	cfg.Proxy = append(cfg.Proxy, payload.Proxies...)
	cfg.ProxyGroup = append(cfg.ProxyGroup, payload.ProxyGroups...)

	// 注入规则前置于用户规则，保证最高优先级；同时去重，避免重复加载时叠加
	if len(payload.Rules) > 0 {
		keep := make([]string, 0, len(cfg.Rule))
		for _, r := range cfg.Rule {
			injected := false
			for _, ir := range payload.Rules {
				if r == ir {
					injected = true
					break
				}
			}
			if !injected {
				keep = append(keep, r)
			}
		}
		cfg.Rule = append(append([]string{}, payload.Rules...), keep...)
	}

	log.Infoln("EasyClash: injected %d easytier proxies, %d groups, %d rules",
		len(payload.Proxies), len(payload.ProxyGroups), len(payload.Rules))
	return nil
}

func filterNamed(list []map[string]any, remove map[string]bool) []map[string]any {
	result := make([]map[string]any, 0, len(list))
	for _, item := range list {
		if name, ok := item["name"].(string); ok && remove[name] {
			continue
		}
		result = append(result, item)
	}
	return result
}
