package main

// Babai Console print engine: reading a Petpooja KOT print and adding the token on top.
// A line-for-line port of Bridge Print's receipt.mjs (decodeReceipt, receiptBitmap,
// parseReceiptText, with the Ownly fixes) and print.mjs (renderJob), so slips look the same.

import (
	"bytes"
	"compress/zlib"
	"encoding/binary"
	"errors"
	"io"
	"regexp"
	"sort"
	"strings"
)

type rasterImage struct {
	Width, Height int
	Pixels        []byte
}

// decodeReceipt reads length-delimited graphic blocks and plain text; compressed pixels are
// never read as text.
func decodeReceipt(raw []byte) ([]rasterImage, string, error) {
	var images []rasterImage
	var text strings.Builder
	n := len(raw)
	u16 := func(at int) int { return int(binary.LittleEndian.Uint16(raw[at:])) }
	for i := 0; i < n; {
		if raw[i] == 29 && i+2 < n && raw[i+1] == 118 && raw[i+2] == 48 && i+8 <= n {
			row, height := u16(i+4), u16(i+6)
			size := row * height
			if i+8+size > n {
				return images, text.String(), errors.New("incomplete raster image")
			}
			if row > 0 && row <= 256 && height > 0 && height <= 10000 {
				images = append(images, rasterImage{row * 8, height, raw[i+8 : i+8+size]})
			}
			i += 8 + size
			text.WriteByte('\n')
			continue
		}
		if raw[i] == 29 && i+2 < n && (raw[i+1] == 56 || raw[i+1] == 40) && raw[i+2] == 76 {
			header := 5
			if raw[i+1] == 56 {
				header = 7
			}
			if i+header > n {
				break
			}
			var length int
			if header == 7 {
				length = int(binary.LittleEndian.Uint32(raw[i+3:]))
			} else {
				length = u16(i + 3)
			}
			if length < 0 || i+header+length > n {
				break
			}
			p := raw[i+header : i+header+length]
			if len(p) >= 10 && p[0] == 48 && p[1] == 112 {
				width, height := int(binary.LittleEndian.Uint16(p[6:])), int(binary.LittleEndian.Uint16(p[8:]))
				size := (width + 7) / 8 * height
				if width > 0 && width <= 2048 && height > 0 && height <= 10000 && size <= 4<<20 {
					var px []byte
					switch p[2] {
					case 49:
						if zr, err := zlib.NewReader(bytes.NewReader(p[10:])); err == nil {
							px, _ = io.ReadAll(io.LimitReader(zr, int64(size)+1))
							zr.Close()
						}
					case 48:
						px = p[10:]
					}
					if len(px) == size {
						images = append(images, rasterImage{width, height, px})
					}
				}
			}
			i += header + length
			text.WriteByte('\n')
			continue
		}
		if raw[i] == 27 {
			var cmd byte
			if i+1 < n {
				cmd = raw[i+1]
			}
			if cmd == 42 && i+5 <= n {
				size := u16(i + 3)
				if raw[i+2] >= 32 {
					size *= 3
				}
				i += 5 + size
				text.WriteByte('\n')
				continue
			}
			switch cmd {
			case 51, 61, 97, 69, 74, 82, 116, 33, 45, 32:
				i += 3
			case 36, 99:
				i += 4
			default:
				i += 2
			}
			continue
		}
		if raw[i] == 29 {
			if i+1 < n && raw[i+1] == 40 && i+5 <= n {
				i += 5 + u16(i+3)
				continue
			}
			i += 3
			continue
		}
		c := raw[i]
		i++
		if c == 10 || c == 13 {
			text.WriteByte('\n')
		} else if c >= 32 && c <= 126 {
			text.WriteByte(c)
		}
	}
	return images, text.String(), nil
}

// receiptBitmap stacks the raster images into one 24-bit BMP for Windows OCR.
func receiptBitmap(images []rasterImage) ([]byte, error) {
	if len(images) == 0 {
		return nil, nil
	}
	width, height := 0, 16
	for _, im := range images {
		if im.Width > width {
			width = im.Width
		}
		height += im.Height + 12
	}
	width += 32
	if height > 10000 {
		return nil, errors.New("receipt too tall for text recognition")
	}
	stride := (width*3 + 3) / 4 * 4
	bmp := make([]byte, 54+stride*height)
	for i := 54; i < len(bmp); i++ {
		bmp[i] = 255
	}
	copy(bmp, "BM")
	le := binary.LittleEndian
	le.PutUint32(bmp[2:], uint32(len(bmp)))
	le.PutUint32(bmp[10:], 54)
	le.PutUint32(bmp[14:], 40)
	le.PutUint32(bmp[18:], uint32(width))
	le.PutUint32(bmp[22:], uint32(int32(-height)))
	le.PutUint16(bmp[26:], 1)
	le.PutUint16(bmp[28:], 24)
	le.PutUint32(bmp[34:], uint32(stride*height))
	top := 8
	for _, im := range images {
		rowBytes := (im.Width + 7) / 8
		for y := 0; y < im.Height; y++ {
			for x := 0; x < im.Width; x++ {
				if im.Pixels[y*rowBytes+x>>3]&(128>>(x&7)) != 0 {
					at := 54 + (top+y)*stride + (x+16)*3
					bmp[at], bmp[at+1], bmp[at+2] = 0, 0, 0
				}
			}
		}
		top += im.Height + 12
	}
	return bmp, nil
}

// ReceiptMeta is what was read from a slip (Bridge Print's job.metadata).
type ReceiptMeta struct {
	Brand        string `json:"brand"`
	OrderID      string `json:"orderId"`
	DisplayBrand string `json:"displayBrand,omitempty"`
	Method       string `json:"method,omitempty"`
	Warning      string `json:"warning,omitempty"`
}

var (
	reToingHeading  = regexp.MustCompile(`(?i)\bto(?:ing|wing)\s+by\s+swiggy\b`)
	rePickupBarcode = regexp.MustCompile(`(?i)\bpick\s*up\s+barcode\b`)
	reExclSpecial   = regexp.MustCompile(`(?i)\b(?:kot|passcode|otp|date|time)\b`)
	reDigits10to20  = regexp.MustCompile(`^\d{10,20}$`)
	reCompactDate   = regexp.MustCompile(`^(?:19|20)\d{6}(?:[01]\d[0-5]\d(?:[0-5]\d)?)?$`)
	reCandidate     = regexp.MustCompile(`(?i)order\s*(?:type|from|source)|(?:delivery|service)\s*type`)
	reSkipBrandLine = regexp.MustCompile(`(?i)barcode|delivery partner`)
	reExplicit      = regexp.MustCompile(`(?i)(?:order\s*(?:type|from|source)|(?:delivery|service)\s*type)\s*[:#-]\s*([A-Z][A-Z0-9 .&-]{1,30})`)
	reIgnoredLabel  = regexp.MustCompile(`(?i)^(?:kot|otp|order(?:\s*(?:id|no\.?|number))?|table(?:\s*(?:id|no\.?|number))?|bill(?:\s*(?:id|no\.?|number))?|token|customer|phone|mobile|payment|total|gst|invoice|cashier|date|time|(?:delivery\s+)?passcode)$`)
	reSourceID      = regexp.MustCompile(`(?i)^\s*([A-Z][A-Z0-9 .&-]{1,23})\s*[:#-]\s*([A-Z0-9][A-Z0-9-]{1,39})\s*$`)
	reOcrIDLine     = regexp.MustCompile(`(?i)^\s*([A-Z][A-Z0-9 .&-]{1,23}?)\s*[:#;-]\s*([0-9OoIl|][0-9OoIl| -]{4,48}[0-9OoIl|])\s*$`)
	reOcrish        = regexp.MustCompile(`^[0-9OoIl|]{10,}$`)
	reOcrLine       = regexp.MustCompile(`^[0-9OoIl| -]+$`)
	reAllDigits     = regexp.MustCompile(`^\d+$`)
	reDigits6to24   = regexp.MustCompile(`^\d{6,24}$`)
	reOrderLabel    = regexp.MustCompile(`(?i)\b(?:online\s+|external\s+|zomato\s+|swiggy\s+)?order\s*(?:id|no\.?|number|#)\s*[:#-]?\s*([A-Z0-9][A-Z0-9-]{1,39})?\s*$`)
	reIDValue       = regexp.MustCompile(`(?i)^[A-Z0-9][A-Z0-9-]{1,39}$`)
	reHasDigit      = regexp.MustCompile(`\d`)
	reSpaces        = regexp.MustCompile(`\s+`)
	receiptSources  = []struct {
		re   *regexp.Regexp
		name string
	}{
		{regexp.MustCompile(`(?i)\bzomato\b`), "Zomato"}, {regexp.MustCompile(`(?i)\bswiggy\b`), "Swiggy"},
		{regexp.MustCompile(`(?i)\bownly\b`), "Ownly"}, {regexp.MustCompile(`(?i)\bmagicpin\b`), "Magicpin"},
		{regexp.MustCompile(`(?i)\bondc\b`), "ONDC"}, {regexp.MustCompile(`(?i)\bself[ -]*service\b`), "Self service"},
		{regexp.MustCompile(`(?i)\bpick[ -]*up\b|\btake[ -]*away\b|\bparcel\b`), "Pick up"},
		{regexp.MustCompile(`(?i)\bdine[ -]*in\b`), "Dine in"},
	}
)

func ocrDigits(v string) string {
	r := strings.NewReplacer("O", "0", "o", "0", "I", "1", "l", "1", "|", "1", "-", "")
	return reSpaces.ReplaceAllString(r.Replace(v), "")
}

func specialLongID(line string) string {
	if line == "" || reExclSpecial.MatchString(line) {
		return ""
	}
	compact := strings.NewReplacer("-", "").Replace(reSpaces.ReplaceAllString(line, ""))
	if !reDigits10to20.MatchString(compact) || reCompactDate.MatchString(compact) {
		return ""
	}
	return compact
}

// parseReceiptText finds the source (brand) and order ID in a KOT's text (Bridge Print's rules).
func parseReceiptText(text string) ReceiptMeta {
	var lines []string
	for _, s := range strings.Split(strings.ReplaceAll(text, "\r", ""), "\n") {
		if s = strings.TrimSpace(s); s != "" {
			lines = append(lines, s)
		}
	}
	head := lines
	if len(head) > 18 {
		head = head[:18]
	}
	isToing := false
	for _, l := range lines {
		if reToingHeading.MatchString(l) {
			isToing = true
			break
		}
	}
	var candidates []string
	for _, l := range lines {
		if reCandidate.MatchString(l) {
			candidates = append(candidates, l)
		}
	}
	brand := ""
	for _, line := range append(append([]string{}, candidates...), head...) {
		if reSkipBrandLine.MatchString(line) {
			continue
		}
		found, count := "", 0
		for _, s := range receiptSources {
			if s.re.MatchString(line) {
				found = s.name
				count++
			}
		}
		if count == 1 {
			brand = found
			break
		}
	}
	if brand == "" {
		for _, l := range candidates {
			if m := reExplicit.FindStringSubmatch(l); m != nil {
				brand = reSpaces.ReplaceAllString(strings.TrimSpace(m[1]), " ")
				break
			}
		}
	}
	orderID := ""
	if isToing {
		type anchor struct{ index, limit int }
		var anchors []anchor
		for i, l := range lines {
			if reToingHeading.MatchString(l) {
				anchors = append(anchors, anchor{i, 2})
			}
		}
		for i, l := range lines {
			if rePickupBarcode.MatchString(l) {
				anchors = append(anchors, anchor{i, 4})
			}
		}
	outer:
		for _, a := range anchors {
			for off := 1; off <= a.limit && a.index+off < len(lines); off++ {
				if v := specialLongID(lines[a.index+off]); v != "" {
					orderID = v
					break outer
				}
			}
		}
	}
	if orderID == "" {
		for _, line := range head {
			m := reSourceID.FindStringSubmatch(line)
			if m != nil && reHasDigit.MatchString(m[2]) && !reIgnoredLabel.MatchString(strings.TrimSpace(m[1])) {
				orderID = m[2]
				if brand == "" {
					brand = reSpaces.ReplaceAllString(strings.TrimSpace(m[1]), " ")
				}
				break
			}
		}
	}
	// Ownly: the bold last 4 digits come out as a separate word, and 0 / 1 as O / l.
	if orderID == "" {
		for _, line := range head {
			m := reOcrIDLine.FindStringSubmatch(line)
			if m == nil || reIgnoredLabel.MatchString(strings.TrimSpace(m[1])) {
				continue
			}
			if d := ocrDigits(m[2]); reDigits6to24.MatchString(d) {
				orderID = d
				if brand == "" {
					brand = reSpaces.ReplaceAllString(strings.TrimSpace(m[1]), " ")
				}
				break
			}
		}
	}
	if orderID != "" && reOcrish.MatchString(orderID) && float64(len(reHasDigit.FindAllString(orderID, -1))) >= float64(len(orderID))*0.7 {
		orderID = ocrDigits(orderID)
	}
	// The plain digits under the KOT barcode (13+ digits, so never a phone number).
	var longIDs []string
	seen := map[string]bool{}
	for _, line := range lines {
		if !reOcrLine.MatchString(line) {
			continue
		}
		if v := specialLongID(ocrDigits(line)); len(v) >= 13 && !seen[v] {
			seen[v] = true
			longIDs = append(longIDs, v)
		}
	}
	if orderID != "" && reAllDigits.MatchString(orderID) {
		var longer []string
		for _, v := range longIDs {
			if len(v) > len(orderID) && strings.HasPrefix(v, orderID) {
				longer = append(longer, v)
			}
		}
		sort.SliceStable(longer, func(a, b int) bool { return len(longer[a]) > len(longer[b]) })
		if len(longer) > 0 {
			orderID = longer[0]
		}
	} else if orderID == "" && len(longIDs) == 1 {
		orderID = longIDs[0]
	}
	for i := 0; i < len(lines) && orderID == ""; i++ {
		m := reOrderLabel.FindStringSubmatch(lines[i])
		if m == nil {
			continue
		}
		v := m[1]
		if v == "" && i+1 < len(lines) {
			v = lines[i+1]
		}
		if v != "" && reIDValue.MatchString(v) && reHasDigit.MatchString(v) {
			orderID = v
		}
	}
	out := ReceiptMeta{Brand: brand, OrderID: orderID}
	if isToing {
		out.DisplayBrand = "Toing / Swiggy"
	}
	return out
}

// Customization is frozen on a job when it arrives (Bridge Print's job.customization).
type Customization struct {
	Heading      string `json:"heading"`
	TokenLabel   string `json:"tokenLabel"`
	TokenEnabled bool   `json:"tokenEnabled"`
	AutoSource   bool   `json:"autoSource"`
	ShowOrderID  bool   `json:"showOrderId"`
}

func asciiOnly(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r >= 0x20 && r <= 0x7e {
			b.WriteRune(r)
		} else if r == '–' || r == '—' {
			b.WriteByte('-')
		}
	}
	return b.String()
}

// renderJob puts the heading, "<brand or label> <token>" and "Order ID: <last 4>" above the
// original bytes, which follow unchanged (Bridge Print's renderJob).
func renderJob(raw []byte, token int, c Customization, m ReceiptMeta) []byte {
	if !c.TokenEnabled && c.Heading == "" {
		return raw
	}
	var b bytes.Buffer
	b.Write([]byte{27, 64, 27, 97, 1})
	if c.Heading != "" {
		b.WriteString(asciiOnly(c.Heading) + "\n")
	}
	if c.TokenEnabled {
		label := ""
		if c.AutoSource {
			label = asciiOnly(m.Brand)
		}
		if label == "" {
			label = c.TokenLabel
		}
		if label == "" {
			label = "TOKEN"
		}
		b.Write([]byte{27, 69, 1, 29, 33, 34})
		b.WriteString(label + " " + itoa(token) + "\n")
		if c.ShowOrderID && m.OrderID != "" {
			id := asciiOnly(m.OrderID)
			if len(id) > 4 {
				id = id[len(id)-4:]
			}
			b.Write([]byte{29, 33, 1, 27, 69, 1})
			b.WriteString("Order ID: " + id + "\n")
		}
	}
	b.Write([]byte{29, 33, 0, 27, 69, 0, 27, 97, 0})
	b.WriteString("\n")
	b.Write([]byte{27, 64})
	b.Write(raw)
	return b.Bytes()
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var d [20]byte
	i := len(d)
	for n > 0 {
		i--
		d[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		d[i] = '-'
	}
	return string(d[i:])
}
