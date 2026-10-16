package main

// Bridge Print's "Petpooja live" (petpooja-live.mjs): the open orders as the Petpooja Token app
// sees them (POST :3000/petpooja_server/get_pending_orders), giving each print its live status
// (preparing / Food Ready) and OTP for the token TV. Only when "Use live Petpooja status on TV"
// is on, as before. Long order numbers are kept exactly (no rounding).

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

type liveState struct {
	Enabled     bool   `json:"enabled"`
	Status      string `json:"status"`
	ServerIP    string `json:"serverIp"`
	Automatic   bool   `json:"automatic"`
	LastSync    string `json:"lastSync"`
	LastMessage string `json:"lastMessage"`
	OrderCount  int    `json:"orderCount"`
}

var (
	liveMu sync.Mutex
	live   = liveState{Status: "off", LastMessage: "Save the Petpooja server IP to start."}
	liveHC = &http.Client{Timeout: 6 * time.Second}
)

func liveSnapshot() liveState {
	liveMu.Lock()
	defer liveMu.Unlock()
	return live
}

func petpoojaLiveLoop() {
	for {
		liveSync()
		time.Sleep(3 * time.Second)
	}
}

var reIPv4 = regexp.MustCompile(`(?:^|::ffff:)(\d{1,3}(?:\.\d{1,3}){3})$`)

func sourceIP(v string) string {
	v = strings.TrimSpace(v)
	if v == "::1" {
		return "127.0.0.1"
	}
	if m := reIPv4.FindStringSubmatch(strings.ToLower(v)); m != nil {
		return m[1]
	}
	return ""
}

func jsonClean(v any) string {
	switch x := v.(type) {
	case nil:
		return ""
	case string:
		return strings.TrimSpace(x)
	case json.Number:
		return x.String()
	case bool:
		if x {
			return "true"
		}
		return "false"
	}
	return strings.TrimSpace(fmt.Sprint(v))
}

func livePost(url string, body any) (map[string]any, error) {
	b, _ := json.Marshal(body)
	resp, err := liveHC.Post(url, "application/json", bytes.NewReader(b))
	if err != nil {
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			return nil, errors.New("Petpooja server did not answer on port 3000.")
		}
		return nil, errors.New("Petpooja server did not answer on port 3000.")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("Petpooja returned HTTP %d", resp.StatusCode)
	}
	dec := json.NewDecoder(io.LimitReader(resp.Body, 16<<20))
	dec.UseNumber() // 19-digit order numbers stay exact
	var out map[string]any
	if err := dec.Decode(&out); err != nil {
		return nil, errors.New("Petpooja sent something unexpected")
	}
	return out, nil
}

func liveSync() {
	prMu.Lock()
	s := prSettings
	auto := ""
	for i := len(prJobs) - 1; i >= 0 && auto == ""; i-- {
		auto = sourceIP(prJobs[i].Source)
	}
	prMu.Unlock()
	server := s.PetpoojaIP
	if server == "" && getConfig().Server != "" {
		server = hostOnly(getConfig().Server)
	}
	if server == "" {
		server = auto
	}
	liveMu.Lock()
	live.Enabled, live.ServerIP, live.Automatic = s.KdsEnabled, server, s.PetpoojaIP == "" && server != ""
	if !s.KdsEnabled {
		live.Status, live.LastMessage = "off", "Live Petpooja status is off."
		liveMu.Unlock()
		return
	}
	if server == "" {
		live.Status, live.LastMessage = "setup", "Send one Petpooja print to detect its server, or enter the server IP manually."
		liveMu.Unlock()
		return
	}
	live.Status = "connecting"
	liveMu.Unlock()
	err := liveFetch(server, s)
	liveMu.Lock()
	if err != nil {
		live.Status, live.LastMessage = "error", err.Error()
	}
	liveMu.Unlock()
}

func liveFetch(server string, s PrintSettings) error {
	base := "http://" + server + ":3000/petpooja_server/"
	code := s.PetpoojaSyncCode
	if code == "" {
		liveMu.Lock()
		live.LastMessage = "Getting restaurant details from Petpooja…"
		liveMu.Unlock()
		setup, err := livePost(base+"get_token_default_data", map[string]any{"clientIdentifier": "token", "intra_server_version": "1"})
		if err != nil {
			return err
		}
		if e := jsonClean(setup["error"]); e != "" && e != "0" && e != "false" {
			return errors.New(firstNonEmpty(jsonClean(setup["message"]), "Petpooja setup was rejected"))
		}
		code = jsonClean(setup["sync_code"])
		if code == "" {
			return errors.New("Petpooja did not return its restaurant code.")
		}
		prMu.Lock()
		prSettings.PetpoojaSyncCode = code
		_ = savePrintSettingsLocked()
		prMu.Unlock()
	}
	res, err := livePost(base+"get_pending_orders", map[string]any{"sync_code": code, "clientIdentifier": "token", "intra_server_version": "1",
		"online": "1", "pickup": "1", "delivery": "1", "dinein": "1"})
	if err != nil {
		return err
	}
	if jsonClean(res["error"]) == "1" {
		return errors.New(firstNonEmpty(jsonClean(res["message"]), "Petpooja rejected the request"))
	}
	var list []map[string]any
	for _, k := range []string{"data", "pos_data", "dine_data"} {
		if arr, ok := res[k].([]any); ok {
			for _, x := range arr {
				if m, ok := x.(map[string]any); ok {
					list = append(list, m)
				}
			}
		}
	}
	byKey := map[string]map[string]any{}
	for _, o := range list {
		for _, k := range []string{"client_order_id", "auth_no", "id", "web_id", "kot_id"} {
			if v := strings.ToLower(jsonClean(o[k])); v != "" {
				byKey[v] = o
			}
		}
		for k, v := range o { // delivered time, when Petpooja gives one
			if strings.Contains(strings.ToLower(k), "deliver") && strings.Contains(strings.ToLower(k), "time") {
				if t := parseCreated(jsonClean(v)); t > 0 {
					noteDelivered(jsonClean(o["client_order_id"]), t)
				}
			}
		}
	}
	changed := 0
	now := nowISO()
	prMu.Lock()
	for _, j := range prJobs {
		if j.Metadata == nil || j.Metadata.OrderID == "" {
			continue
		}
		o := byKey[strings.ToLower(j.Metadata.OrderID)]
		cur := j.Petpooja
		if o == nil {
			if cur != nil && cur.Active != nil && *cur.Active {
				f := false
				c := *cur
				c.Active, c.RemovedAt, c.UpdatedAt = &f, now, now
				j.Petpooja = &c
				saveJobLocked(j)
				changed++
			}
			continue
		}
		t := true
		st := "preparing"
		raw := 0
		fmt.Sscan(jsonClean(o["status"]), &raw)
		done := jsonClean(o["mark_as_done_date"])
		if raw == 5 || (done != "" && !strings.EqualFold(done, "null")) {
			st = "ready"
		}
		v := JobLive{Active: &t, Status: st, RawStatus: raw, OTP: jsonClean(o["otp"]), Brand: jsonClean(o["user_name"]),
			OrderID: firstNonEmpty(jsonClean(o["client_order_id"]), jsonClean(o["auth_no"]), jsonClean(o["id"]))}
		if cur == nil || cur.Active == nil || !*cur.Active || cur.Status != v.Status || cur.RawStatus != v.RawStatus || cur.OTP != v.OTP || cur.Brand != v.Brand || cur.OrderID != v.OrderID {
			v.UpdatedAt = now
			if cur != nil {
				v.ReadyAt = cur.ReadyAt
			}
			j.Petpooja = &v
			saveJobLocked(j)
			changed++
		}
	}
	prMu.Unlock()
	liveMu.Lock()
	live.Status, live.LastSync, live.OrderCount = "connected", now, len(list)
	live.LastMessage = fmt.Sprintf("Connected · %d active orders", len(list))
	if changed > 0 {
		live.LastMessage += fmt.Sprintf(" · %d updated", changed)
	}
	liveMu.Unlock()
	return nil
}
