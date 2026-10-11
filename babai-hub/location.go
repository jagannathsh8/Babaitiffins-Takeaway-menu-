package main

// Where the outlet is, so the customers' page can pick the nearest outlet for them.
// Accepts "12.9356, 77.7425", a Google Maps link with coordinates, or a short maps.app.goo.gl
// link (followed once to find the coordinates).

import (
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var (
	reAt    = regexp.MustCompile(`@(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)`)
	rePair  = regexp.MustCompile(`(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})`)
	rePlace = regexp.MustCompile(`!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)`) // exact pin in place links
)

func parseLatLng(s string) (float64, float64, bool) {
	s, _ = url.QueryUnescape(s)
	for _, re := range []*regexp.Regexp{rePlace, reAt, rePair} {
		if m := re.FindStringSubmatch(s); m != nil {
			lat, e1 := strconv.ParseFloat(m[1], 64)
			lng, e2 := strconv.ParseFloat(m[2], 64)
			if e1 == nil && e2 == nil && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180 && (lat != 0 || lng != 0) {
				return lat, lng, true
			}
		}
	}
	return 0, 0, false
}

// resolveLocation turns what the owner typed into coordinates.
func resolveLocation(in string) (float64, float64, error) {
	in = strings.TrimSpace(in)
	if in == "" {
		return 0, 0, nil
	}
	if lat, lng, ok := parseLatLng(in); ok {
		return lat, lng, nil
	}
	if strings.HasPrefix(in, "http") {
		c := &http.Client{Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
		link := in
		for i := 0; i < 4; i++ { // short link -> long link (a few hops)
			resp, err := c.Get(link)
			if err != nil {
				break
			}
			resp.Body.Close()
			loc := resp.Header.Get("Location")
			if loc == "" {
				break
			}
			if lat, lng, ok := parseLatLng(loc); ok {
				return lat, lng, nil
			}
			link = loc
		}
	}
	return 0, 0, errors.New("couldn't find the location in that. Paste the two numbers, e.g. 12.9356, 77.7425 (Google Maps: long-press the shop, copy the numbers at the top)")
}
