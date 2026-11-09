package main

// The Console's own KOT slip (the approved design): drawn as a picture the width of the paper
// (576 dots on 80 mm, 384 on 58 mm) and sent to the printer as an ESC/POS raster image, so it looks
// the same on every thermal printer (Epson, TVS, Retsol ...), then the printer cuts.
//
//	Laid out like Petpooja's own KOT (4.12), with the slot on top:
//	SLOT 14 · date · KOT - n · "Zomato : 8816162299" (last 4 bold) · OTP · Delivery · Item / Qty. ·
//	items with add-ons and notes · total · Customer Notes · "Scan to Mark food ready" + barcode
//	(the order ID on delivery; the KOT's own code on dine in / pick up / self service).

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"math"
	"strings"
	"sync"
	"time"

	"golang.org/x/image/font"
	"golang.org/x/image/font/gofont/gobold"
	"golang.org/x/image/font/gofont/goitalic"
	"golang.org/x/image/font/gofont/gomedium"
	"golang.org/x/image/font/gofont/goregular"
	"golang.org/x/image/font/opentype"
	"golang.org/x/image/math/fixed"
)

type slipItem struct {
	N  string
	Q  float64
	A  []KAddon
	Nt string
}

type slipData struct {
	Label   string // "SLOT" (online) / "TOKEN"
	Token   int
	Cancel  bool
	Station string // "DOSA COUNTER": a line's printer
	When    time.Time
	KotNo   string
	Brand   string // "Swiggy", or "Dine in · Table 4"
	OrderID string // platform order ID, online only
	Code    string // the barcode: the order ID (delivery) or kotScanCode (every other order)
	Type    string // DELIVERY / PICK UP / DINE IN / SELF SERVICE
	Items   []slipItem
	Note    string // the customer's note
	Also    string // what else is on the order (a station slip)
	OTP     string // the rider's OTP / delivery passcode (bill slips, online orders)
	OTPName string // "OTP" (Zomato) or "Delivery Passcode" (Swiggy)
}

var (
	fontOnce                  sync.Once
	fontBold, fontReg, fontIt *opentype.Font
	fontMed                   *opentype.Font
	faceMu                    sync.Mutex
	faces                     = map[string]font.Face{}
)

func slipFace(kind string, size float64) font.Face {
	fontOnce.Do(func() {
		fontBold, _ = opentype.Parse(gobold.TTF)
		fontReg, _ = opentype.Parse(goregular.TTF)
		fontIt, _ = opentype.Parse(goitalic.TTF)
		fontMed, _ = opentype.Parse(gomedium.TTF)
	})
	key := fmt.Sprintf("%s%.1f", kind, size)
	faceMu.Lock()
	defer faceMu.Unlock()
	if f := faces[key]; f != nil {
		return f
	}
	src := fontReg
	switch kind {
	case "b":
		src = fontBold
	case "i":
		src = fontIt
	case "m":
		src = fontMed // "regular" text: a little heavier, so thermal paper shows it clearly
	}
	f, err := opentype.NewFace(src, &opentype.FaceOptions{Size: size, DPI: 72, Hinting: font.HintingFull})
	if err != nil {
		return nil
	}
	faces[key] = f
	return f
}

type slipCanvas struct {
	img   *image.Gray
	w     int
	y     int
	scale float64 // 1 on 80 mm, smaller on 58 mm
}

func newCanvas(w int) *slipCanvas {
	c := &slipCanvas{img: image.NewGray(image.Rect(0, 0, w, 4000)), w: w, y: 8, scale: float64(w) / 528}
	draw.Draw(c.img, c.img.Bounds(), image.White, image.Point{}, draw.Src)
	return c
}

func (c *slipCanvas) face(kind string, size float64) font.Face {
	s := size * c.scale
	if size < 60 && s < 17 {
		s = 17 // never too small to read
	}
	return slipFace(kind, s)
}

func textWidth(f font.Face, s string) int { return font.MeasureString(f, s).Ceil() }

func (c *slipCanvas) drawText(f font.Face, s string, x, baseline int, col color.Gray) {
	d := &font.Drawer{Dst: c.img, Src: image.NewUniform(col), Face: f, Dot: fixed.P(x, baseline)}
	d.DrawString(s)
}

// text: one line; align "l" / "c" / "r"; inv = white on a black band.
func (c *slipCanvas) text(s, kind string, size float64, align string, inv bool, x0 int) {
	f := c.face(kind, size)
	m := f.Metrics()
	asc, desc := m.Ascent.Ceil(), m.Descent.Ceil()
	w := textWidth(f, s)
	x := x0
	switch align {
	case "c":
		x = (c.w - w) / 2
	case "r":
		x = c.w - w
	}
	pad := 0
	if inv {
		pad = int(6 * c.scale)
		draw.Draw(c.img, image.Rect(0, c.y, c.w, c.y+asc+desc+2*pad), image.Black, image.Point{}, draw.Src)
		c.drawText(f, s, x, c.y+pad+asc, color.Gray{255})
		c.y += asc + desc + 2*pad + 6
		return
	}
	c.drawText(f, s, x, c.y+asc, color.Gray{0})
	c.y += asc + desc + 4
}

// row: text on the left, text on the right, one line.
func (c *slipCanvas) row(l, r, kind string, size float64, rkind string, rsize float64, indent int) {
	fl, fr := c.face(kind, size), c.face(rkind, rsize)
	asc := max(fl.Metrics().Ascent.Ceil(), fr.Metrics().Ascent.Ceil())
	desc := max(fl.Metrics().Descent.Ceil(), fr.Metrics().Descent.Ceil())
	rw := textWidth(fr, r)
	// the left text never runs into the right one
	room := c.w - rw - 16 - indent
	lines := wrapText(fl, l, room)
	for i, ln := range lines {
		c.drawText(fl, ln, indent, c.y+asc, color.Gray{0})
		if i == 0 {
			c.drawText(fr, r, c.w-rw, c.y+asc, color.Gray{0})
		}
		c.y += asc + desc + 2
	}
	c.y += 4
}

// wrap: indented lines, word by word; box = a frame around them.
func (c *slipCanvas) wrap(s, kind string, size float64, indent int, box bool) {
	f := c.face(kind, size)
	inner := c.w - indent
	pad := 0
	if box {
		pad = 12
		inner -= 2 * pad
	}
	y0 := c.y
	if box {
		c.y += 6
	}
	asc, desc := f.Metrics().Ascent.Ceil(), f.Metrics().Descent.Ceil()
	for _, ln := range wrapText(f, s, inner) {
		c.drawText(f, ln, indent+pad, c.y+asc, color.Gray{0})
		c.y += asc + desc + 2
	}
	if box {
		c.y += 6
		c.rect(indent, y0, c.w-1, c.y, 3)
		c.y += 8
	} else {
		c.y += 2
	}
}

// addon: an add-on in a thin box, so the kitchen never misses it.
func (c *slipCanvas) addon(s string, indent int) {
	f := c.face("i", 25)
	asc, desc := f.Metrics().Ascent.Ceil(), f.Metrics().Descent.Ceil()
	pad := 8
	lines := wrapText(f, s, c.w-indent-2*pad-4)
	w := 0
	for _, ln := range lines {
		w = max(w, textWidth(f, ln))
	}
	y0 := c.y
	c.y += 4
	for _, ln := range lines {
		c.drawText(f, ln, indent+pad, c.y+asc, color.Gray{0})
		c.y += asc + desc + 2
	}
	c.y += 3
	c.rect(indent, y0, min(c.w-1, indent+w+2*pad), c.y, 2)
	c.y += 6
}

func wrapText(f font.Face, s string, room int) []string {
	words := strings.Fields(s)
	var lines []string
	line := ""
	for _, w := range words {
		try := strings.TrimSpace(line + " " + w)
		if line != "" && textWidth(f, try) > room {
			lines = append(lines, line)
			line = w
			continue
		}
		line = try
	}
	if line != "" || len(lines) == 0 {
		lines = append(lines, line)
	}
	return lines
}

func (c *slipCanvas) rect(x0, y0, x1, y1, t int) {
	for i := 0; i < t; i++ {
		for x := x0; x <= x1; x++ {
			c.img.SetGray(x, y0+i, color.Gray{0})
			c.img.SetGray(x, y1-i, color.Gray{0})
		}
		for y := y0; y <= y1; y++ {
			c.img.SetGray(x0+i, y, color.Gray{0})
			c.img.SetGray(x1-i, y, color.Gray{0})
		}
	}
}

func (c *slipCanvas) dash() {
	c.y += 4
	for x := 0; x < c.w; x += 14 {
		draw.Draw(c.img, image.Rect(x, c.y, min(x+7, c.w), c.y+2), image.Black, image.Point{}, draw.Src)
	}
	c.y += 12
}

// idLine: "Zomato : 8816162299" centred, the last 4 bigger and bold (Petpooja's line, readable
// from across the pass).
func (c *slipCanvas) idLine(prefix, id string) {
	head, last := id, ""
	if len(id) > 4 {
		head, last = id[:len(id)-4], id[len(id)-4:]
	}
	fh, fb := c.face("b", 30), c.face("b", 46)
	w := textWidth(fh, prefix+head) + textWidth(fb, last)
	for w > c.w && len(head) > 2 { // a long ID on narrow paper: keep its end
		head = head[1:]
		w = textWidth(fh, prefix+"…"+head) + textWidth(fb, last)
	}
	if head != id[:max(0, len(id)-4)] {
		head = "…" + head
	}
	asc, desc := fb.Metrics().Ascent.Ceil(), fb.Metrics().Descent.Ceil()
	x := max(0, (c.w-w)/2)
	base := c.y + asc
	c.drawText(fh, prefix+head, x, base, color.Gray{0})
	c.drawText(fb, last, x+textWidth(fh, prefix+head), base, color.Gray{0})
	c.y += asc + desc + 4
}

// slotLine: "SLOT 14" on one line at the top, the number big.
func (c *slipCanvas) slotLine(label string, n int, big float64) {
	fl, fn := c.face("b", 40), c.face("b", big)
	num := fmt.Sprint(n)
	gap := int(14 * c.scale)
	w := textWidth(fl, label) + gap + textWidth(fn, num)
	asc := fn.Metrics().Ascent.Ceil()
	x := max(0, (c.w-w)/2)
	// the number's digits start near the top of its face: trim the empty space above them
	top := asc - int(big*c.scale*0.74)
	base := c.y + asc - top
	c.drawText(fl, label, x, base, color.Gray{0})
	c.drawText(fn, num, x+textWidth(fl, label)+gap, base, color.Gray{0})
	c.y = base + 14
}

// barcode: Code 128 of the order ID (subset C for digits, else B), modules whole dots wide.
func (c *slipCanvas) barcode(data string) {
	vals := code128Values(data)
	if vals == nil {
		return
	}
	var bits strings.Builder
	for _, v := range vals {
		bits.WriteString(code128Patterns[v])
	}
	bits.WriteString(code128Stop)
	n := bits.Len() + 20 // quiet zones
	mod := max(1, min(3, c.w/n))
	h := int(90 * c.scale)
	x := (c.w - bits.Len()*mod) / 2
	for i, b := range bits.String() {
		if b == '1' {
			draw.Draw(c.img, image.Rect(x+i*mod, c.y, x+(i+1)*mod, c.y+h), image.Black, image.Point{}, draw.Src)
		}
	}
	c.y += h + 4
	c.text(data, "m", 24, "c", false, 0)
}

// code128Values: start code, data values, check value.
func code128Values(s string) []int {
	if s == "" || len(s) > 40 {
		return nil
	}
	digits := digitsOnly(s) == s
	var vals []int
	if digits && len(s)%2 == 0 {
		vals = append(vals, 105) // start C
		for i := 0; i < len(s); i += 2 {
			vals = append(vals, int(s[i]-'0')*10+int(s[i+1]-'0'))
		}
	} else if digits && len(s) >= 5 {
		vals = append(vals, 105) // start C, last digit in B
		for i := 0; i+1 < len(s); i += 2 {
			vals = append(vals, int(s[i]-'0')*10+int(s[i+1]-'0'))
		}
		vals = append(vals, 100, int(s[len(s)-1])-32) // code B, then the last digit
	} else {
		vals = append(vals, 104) // start B
		for _, r := range s {
			if r < 32 || r > 126 {
				return nil
			}
			vals = append(vals, int(r)-32)
		}
	}
	sum := vals[0]
	for i := 1; i < len(vals); i++ {
		sum += vals[i] * i
	}
	return append(vals, sum%103)
}

func qtyStr(q float64) string {
	if q <= 0 {
		q = 1
	}
	if q == math.Trunc(q) {
		return fmt.Sprintf("%d", int(q))
	}
	return strings.TrimRight(strings.TrimRight(fmt.Sprintf("%.2f", q), "0"), ".")
}

// slipInk: a grey dot darker than this prints black. Above the half-way mark, so the soft edges of
// each letter print too: strokes come out solid instead of thin and broken.
const slipInk = 160

// slipMargin: blank paper each side (dots), so no letter touches or loses the paper's edge.
func slipMargin(w int) int { return w * 24 / 576 } // 24 dots (3 mm) on 80 mm, 16 on 58 mm

// renderSlip draws the slip on paper w dots wide, with a margin each side.
func renderSlip(d slipData, w int) *image.Gray {
	m := slipMargin(w)
	inner := drawSlip(d, w-2*m)
	out := image.NewGray(image.Rect(0, 0, w, inner.Bounds().Dy()+16))
	draw.Draw(out, out.Bounds(), image.White, image.Point{}, draw.Src)
	draw.Draw(out, image.Rect(m, 8, w-m, 8+inner.Bounds().Dy()), inner, inner.Bounds().Min, draw.Src)
	for i, v := range out.Pix { // pure black / white: the preview is exactly what the printer prints
		if v < slipInk {
			out.Pix[i] = 0
		} else {
			out.Pix[i] = 255
		}
	}
	return out
}

func drawSlip(d slipData, w int) *image.Gray {
	c := newCanvas(w)
	if d.Cancel {
		c.text("CANCELLED", "b", 64, "c", true, 0)
	}
	if d.Token > 0 {
		big := 110.0
		if w < 500 {
			big = 96 / c.scale // the number stays large on 58 mm
		}
		c.slotLine(d.Label, d.Token, big)
	}
	if d.Station != "" {
		c.text(strings.ToUpper(d.Station), "b", 40, "c", false, 0)
	}
	c.text(d.When.Format("02/01/06 15:04"), "m", 28, "c", false, 0)
	c.text("KOT - "+d.KotNo, "m", 28, "c", false, 0)
	switch {
	case d.OrderID != "":
		c.idLine(d.Brand+" : ", d.OrderID)
	case d.Brand != "":
		c.text(d.Brand, "b", 30, "c", false, 0)
	}
	if d.OTP != "" {
		name := d.OTPName
		if name == "" {
			name = "OTP"
		}
		c.text(name+" : "+d.OTP, "b", 32, "c", false, 0)
	}
	if d.Type != "" {
		c.text(d.Type, "b", 32, "c", false, 0)
	}
	c.dash()
	c.row("Item", "Qty.", "m", 26, "m", 26, 0)
	total := 0.0
	for _, it := range d.Items {
		q := it.Q
		if q <= 0 {
			q = 1
		}
		total += q
		c.row(it.N, qtyStr(q), "b", 30, "b", 32, 0)
		for _, a := range it.A {
			c.addon("+ "+a.N+" × "+qtyStr(a.Q), int(28*c.scale))
		}
		if it.Nt != "" {
			c.wrap("Note: "+it.Nt, "b", 25, int(28*c.scale), false)
		}
	}
	c.dash()
	c.row("", qtyStr(total), "b", 30, "b", 32, 0)
	c.dash()
	if d.Note != "" {
		c.wrap("Customer Notes: "+d.Note, "b", 30, 0, false)
	}
	if d.Also != "" {
		c.wrap("Also on this order: "+d.Also, "i", 24, 0, false)
	}
	if code := digitsOnly(d.Code); code != "" && !d.Cancel {
		c.y += 6
		c.text("Scan to Mark food ready", "b", 26, "c", false, 0)
		c.barcode(code)
	}
	c.y += 10
	return c.img.SubImage(image.Rect(0, 0, w, c.y)).(*image.Gray)
}

// escposSlip: the picture as ESC/POS (init, raster in bands, feed, cut). Every ESC/POS thermal
// printer understands it; printers without a cutter ignore the cut.
func escposSlip(img *image.Gray) []byte {
	var b bytes.Buffer
	b.Write([]byte{0x1b, 0x40})       // init
	b.Write([]byte{0x1b, 0x61, 0x00}) // left
	w := img.Bounds().Dx()
	h := img.Bounds().Dy()
	bw := (w + 7) / 8
	const band = 128
	for y0 := 0; y0 < h; y0 += band {
		rows := min(band, h-y0)
		b.Write([]byte{0x1d, 0x76, 0x30, 0x00, byte(bw), byte(bw >> 8), byte(rows), byte(rows >> 8)})
		for y := y0; y < y0+rows; y++ {
			for xb := 0; xb < bw; xb++ {
				var v byte
				for bit := 0; bit < 8; bit++ {
					x := xb*8 + bit
					if x < w && img.GrayAt(img.Bounds().Min.X+x, img.Bounds().Min.Y+y).Y < slipInk {
						v |= 0x80 >> bit
					}
				}
				b.WriteByte(v)
			}
		}
	}
	b.Write([]byte{0x1b, 0x64, 0x04})       // feed 4 lines so the cut is below the slip
	b.Write([]byte{0x1d, 0x56, 0x42, 0x00}) // feed and partial cut
	return b.Bytes()
}

func slipPNG(img *image.Gray) []byte {
	var b bytes.Buffer
	_ = png.Encode(&b, img)
	return b.Bytes()
}
