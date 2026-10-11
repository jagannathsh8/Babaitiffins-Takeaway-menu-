package main

// Reads the live kitchen orders from the Petpooja billing station on the shop network, the same
// way the Petpooja KDS app on the TV does (read-only: the hub never changes anything there).
//
//   1. POST http://<billing PC>:3000/petpooja_server/kiosk_get_default_data  -> sync_code
//   2. POST .../petpooja_server/get_kot_data_for_kitchen_view (every few seconds) -> one row per KOT item
//   3. POST .../petpooja_server/touch_items (every few minutes) -> item on/off list

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	petpoojaPort    = 3000
	clientID        = "android_kds" // what the KDS app sends
	intraVersion    = 7
	pollEvery       = 4 * time.Second
	stockEvery      = 3 * time.Minute
	userJSON        = `{"site_user_language_profile":0}`
	kdsAppVersion   = "5.0.1"
	orderTypesAll   = "1,2,3" // delivery, pick-up, dine-in
	releaseOnDispat = "10"    // keep ready KOTs until they are dispatched
)

// Row is one item line from get_kot_data_for_kitchen_view (fields the KDS app reads).
type Row struct {
	ID          *int64   `json:"id"`
	KotUniqueID *int64   `json:"kot_unique_id"` // token number
	TableNo     string   `json:"table_no"`
	OrderType   *int     `json:"order_type"` // 1 delivery, 2 pick-up, 3 dine-in
	KotStatus   string   `json:"kot_status"` // "9" ready, "10" dispatched, "0" cancelled
	Created     string   `json:"created"`
	POID        string   `json:"p_o_id"`
	OUID        string   `json:"o_u_id"`
	Brand       string   `json:"tp_virtual_brand_name"`
	Name        string   `json:"name"`
	Category    string   `json:"category"`
	Quantity    *float64 `json:"quantity"`
	Status      *int     `json:"status"` // item: 9 ready, 10 dispatched, -9 cancelled
	KotItemID   *int64   `json:"kot_item_id"`
}

// Kot is one KOT with its items (rows grouped by id).
type Kot struct {
	ID        int64   `json:"id"`
	Token     int64   `json:"tk,omitempty"`
	Table     string  `json:"tb,omitempty"`
	OrderType int     `json:"ot"`
	Status    string  `json:"st"`
	Created   int64   `json:"c"` // ms, local time of the billing PC
	POID      string  `json:"po,omitempty"`
	Channel   string  `json:"ch,omitempty"`
	Items     []KItem `json:"i"`
}

type KItem struct {
	N string  `json:"n"`
	C string  `json:"c,omitempty"`
	Q float64 `json:"q"`
	S int     `json:"s"`
}

type StockItem struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Category string `json:"category"`
	InStock  *int   `json:"in_stock"`
}

var (
	ppMu       sync.Mutex
	syncCode   string
	ppOKAt     time.Time
	ppErr      string
	ppErrAt    time.Time
	ppRows     int
	ppSample   string // first part of the last raw answer (shown on the hub page)
	ppKots     []Kot
	ppStock    []StockItem
	ppStockAt  time.Time
	ppClient   = &http.Client{Timeout: 10 * time.Second}
	pollWakeup = make(chan struct{}, 1)
)

// petpoojaLoop polls the billing station for the life of the program.
func petpoojaLoop() {
	for {
		start := time.Now()
		if c := getConfig(); c.Server != "" && !c.Paused {
			host := c.Server
			if err := pollOnce(host); err != nil {
				notePP(err)
			}
		}
		wait := pollEvery - time.Since(start)
		if wait < 500*time.Millisecond {
			wait = 500 * time.Millisecond
		}
		select {
		case <-time.After(wait):
		case <-pollWakeup:
		}
	}
}

func wakePoll() {
	select {
	case pollWakeup <- struct{}{}:
	default:
	}
}

func notePP(err error) {
	ppMu.Lock()
	defer ppMu.Unlock()
	if err.Error() != ppErr || time.Since(ppErrAt) > 10*time.Minute {
		log.Printf("petpooja: %v", err)
	}
	ppErr, ppErrAt = err.Error(), time.Now()
}

func pollOnce(host string) error {
	base := serverBase(host)
	ppMu.Lock()
	code := syncCode
	ppMu.Unlock()
	if code == "" {
		c, err := fetchSyncCode(base, host)
		if err != nil {
			return err
		}
		ppMu.Lock()
		syncCode, code = c, c
		ppMu.Unlock()
	}
	rows, raw, err := fetchKots(base, code)
	if err != nil {
		ppMu.Lock()
		syncCode = "" // ask for a fresh sync code next time
		ppMu.Unlock()
		return err
	}
	kots := groupRows(rows)
	now := time.Now()
	ppMu.Lock()
	ppOKAt, ppRows, ppKots = now, len(rows), kots
	ppSample = sample(raw)
	stockDue := now.Sub(ppStockAt) >= stockEvery
	ppMu.Unlock()
	onBoard(kots, now)
	if stockDue {
		if items, err := fetchStock(base, code); err == nil {
			ppMu.Lock()
			ppStock, ppStockAt = items, now
			ppMu.Unlock()
		} else {
			ppMu.Lock()
			ppStockAt = now // try again later; orders matter more
			ppMu.Unlock()
		}
	}
	return nil
}

// serverBase: "192.168.1.5" -> "http://192.168.1.5:3000/"; a given port is kept.
func serverBase(host string) string {
	h := strings.TrimSpace(host)
	h = strings.TrimPrefix(strings.TrimPrefix(h, "http://"), "https://")
	if i := strings.IndexAny(h, "/?#"); i >= 0 {
		h = h[:i]
	}
	if _, _, err := net.SplitHostPort(h); err != nil {
		h = net.JoinHostPort(h, strconv.Itoa(petpoojaPort))
	}
	return "http://" + h + "/"
}

func hostOnly(host string) string {
	b := strings.TrimSuffix(strings.TrimPrefix(serverBase(host), "http://"), "/")
	if h, _, err := net.SplitHostPort(b); err == nil {
		return h
	}
	return b
}

// resetPetpooja forgets the session (new address saved).
func resetPetpooja() {
	ppMu.Lock()
	syncCode, ppErr, ppErrAt, ppOKAt, ppKots, ppStock, ppSample = "", "", time.Time{}, time.Time{}, nil, nil, ""
	ppStockAt = time.Time{}
	ppMu.Unlock()
	wakePoll()
}

func ppPost(url string, body any, out any) ([]byte, error) {
	b, _ := json.Marshal(body)
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(b))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	req.Header.Set("x-app-client", "android-kds")
	resp, err := ppClient.Do(req)
	if err != nil {
		var ne net.Error
		if errors.As(err, &ne) && ne.Timeout() {
			return nil, errors.New("Petpooja billing PC not answering (is Petpooja open? right IP?)")
		}
		return nil, errors.New("can't reach the Petpooja billing PC (check the IP and that this PC is on the shop network)")
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 16<<20))
	if resp.StatusCode != http.StatusOK {
		return raw, fmt.Errorf("Petpooja answered HTTP %d", resp.StatusCode)
	}
	if err := json.Unmarshal(raw, out); err != nil {
		return raw, errors.New("Petpooja sent something unexpected (see 'What Petpooja sent')")
	}
	return raw, nil
}

func fetchSyncCode(base, host string) (string, error) {
	req := map[string]any{
		"intra_server_version": intraVersion,
		"kds_details_obj":      map[string]string{"ip": hostOnly(host), "version": kdsAppVersion, "language_id": "0"},
		"user":                 userJSON,
		"clientIdentifier":     clientID,
	}
	var res struct {
		SyncCode string `json:"sync_code"`
		Message  string `json:"message"`
		Error    any    `json:"error"`
		Data     struct {
			SyncCode string `json:"sync_code"`
		} `json:"data"`
	}
	raw, err := ppPost(base+"petpooja_server/kiosk_get_default_data", req, &res)
	if err != nil {
		ppMu.Lock()
		ppSample = sample(raw)
		ppMu.Unlock()
		return "", err
	}
	code := res.SyncCode
	if code == "" {
		code = res.Data.SyncCode
	}
	if code == "" {
		ppMu.Lock()
		ppSample = sample(raw)
		ppMu.Unlock()
		msg := strings.TrimSpace(res.Message)
		if msg == "" {
			msg = "no sync code in the answer"
		}
		return "", errors.New("Petpooja didn't accept the connection: " + msg)
	}
	return code, nil
}

func fetchKots(base, code string) ([]Row, []byte, error) {
	req := map[string]any{
		"intra_server_version": intraVersion,
		"is_addon_combo":       0,
		"sync_code":            code,
		"category_ids":         "-1",
		"kot_ids":              []int64{},
		"order_type":           orderTypesAll,
		"release_kot_on":       releaseOnDispat,
		"user":                 userJSON,
		"clientIdentifier":     clientID,
		"sub_order_type_ids":   "",
		"item_ids":             "-1",
		"table_ids":            "-1",
	}
	var res struct {
		Message string            `json:"message"`
		Error   json.RawMessage   `json:"error"`
		Data    []json.RawMessage `json:"data"`
	}
	raw, err := ppPost(base+"petpooja_server/get_kot_data_for_kitchen_view", req, &res)
	if err != nil {
		return nil, raw, err
	}
	rows := make([]Row, 0, len(res.Data))
	for _, d := range res.Data {
		var r Row
		if json.Unmarshal(d, &r) == nil && r.ID != nil {
			rows = append(rows, r)
		}
	}
	if len(res.Data) == 0 && truthy(res.Error) {
		msg := strings.TrimSpace(res.Message)
		if msg == "" {
			msg = "error " + string(res.Error)
		}
		return nil, raw, errors.New("Petpooja refused the request: " + msg)
	}
	return rows, raw, nil
}

// truthy: an "error" field that means an error (not missing, null, false, 0, "0" or "").
func truthy(v json.RawMessage) bool {
	s := strings.Trim(strings.TrimSpace(string(v)), `"`)
	switch strings.ToLower(s) {
	case "", "null", "false", "0":
		return false
	}
	return true
}

func fetchStock(base, code string) ([]StockItem, error) {
	req := map[string]any{"intra_server_version": intraVersion, "sync_code": code, "user": userJSON, "clientIdentifier": clientID}
	var res struct {
		Items []StockItem `json:"items"`
	}
	if _, err := ppPost(base+"petpooja_server/touch_items", req, &res); err != nil {
		return nil, err
	}
	return res.Items, nil
}

// groupRows turns item rows into KOTs, oldest first.
func groupRows(rows []Row) []Kot {
	byID := map[int64]*Kot{}
	var order []int64
	for _, r := range rows {
		id := *r.ID
		k := byID[id]
		if k == nil {
			k = &Kot{ID: id, Table: strings.TrimSpace(r.TableNo), Status: r.KotStatus, POID: r.POID,
				Created: parseCreated(r.Created), Channel: channel(r)}
			if r.KotUniqueID != nil {
				k.Token = *r.KotUniqueID
			}
			if r.OrderType != nil {
				k.OrderType = *r.OrderType
			}
			byID[id] = k
			order = append(order, id)
		}
		if r.KotStatus != "" {
			k.Status = r.KotStatus
		}
		q := 1.0
		if r.Quantity != nil && *r.Quantity > 0 {
			q = *r.Quantity
		}
		s := 0
		if r.Status != nil {
			s = *r.Status
		}
		name := strings.TrimSpace(r.Name)
		if name == "" {
			continue
		}
		k.Items = append(k.Items, KItem{N: name, C: strings.TrimSpace(r.Category), Q: q, S: s})
	}
	out := make([]Kot, 0, len(order))
	for _, id := range order {
		out = append(out, *byID[id])
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Created < out[j].Created })
	return out
}

func channel(r Row) string {
	if b := strings.TrimSpace(r.Brand); b != "" {
		return b
	}
	if strings.TrimSpace(r.OUID) != "" {
		return "Online"
	}
	return ""
}

var createdFormats = []string{"2006-01-02 15:04:05", "2006-01-02T15:04:05", "2006-01-02 15:04", "02-01-2006 15:04:05"}

func parseCreated(s string) int64 {
	s = strings.TrimSpace(s)
	for _, f := range createdFormats {
		if t, err := time.ParseInLocation(f, s, time.Local); err == nil {
			return t.UnixMilli()
		}
	}
	return 0
}

func sample(raw []byte) string {
	s := string(raw)
	if len(s) > 1500 {
		s = s[:1500] + " …"
	}
	return s
}

func petpoojaStatus() map[string]any {
	ppMu.Lock()
	defer ppMu.Unlock()
	okAgo, errAgo := -1, -1
	if !ppOKAt.IsZero() {
		okAgo = int(time.Since(ppOKAt).Seconds())
	}
	if !ppErrAt.IsZero() && ppErrAt.After(ppOKAt) {
		errAgo = int(time.Since(ppErrAt).Seconds())
	}
	var kots []map[string]any
	for i, k := range ppKots {
		if i >= 60 {
			break
		}
		var items []string
		for _, it := range k.Items {
			items = append(items, fmt.Sprintf("%g× %s", it.Q, it.N))
		}
		kots = append(kots, map[string]any{"tk": k.Token, "tb": k.Table, "ot": k.OrderType, "st": k.Status,
			"ch": k.Channel, "items": strings.Join(items, ", ")})
	}
	off := 0
	for _, s := range ppStock {
		if s.InStock != nil && *s.InStock == 0 {
			off++
		}
	}
	return map[string]any{"ppOkAgo": okAgo, "ppErr": ppErr, "ppErrAgo": errAgo, "ppRows": ppRows,
		"ppKots": kots, "ppSample": ppSample, "ppStock": len(ppStock), "ppStockOff": off}
}
