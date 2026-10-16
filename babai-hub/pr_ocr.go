package main

// Windows OCR for KOT pictures (as Bridge Print did), but kept running: two PowerShell readers
// start once and then read each slip in a fraction of a second, instead of starting PowerShell
// (1-2 s) for every print. Only used to confirm which order a print is; printing never waits
// for it longer than the wait time.

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const ocrScript = `$ErrorActionPreference='Stop'
[Console]::OutputEncoding=[System.Text.Encoding]::UTF8
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$null=[Windows.Storage.StorageFile,Windows.Storage,ContentType=WindowsRuntime]
$null=[Windows.Graphics.Imaging.BitmapDecoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime]
$null=[Windows.Media.Ocr.OcrEngine,Windows.Foundation,ContentType=WindowsRuntime]
$null=[Windows.Globalization.Language,Windows.Globalization,ContentType=WindowsRuntime]
$asTask=([System.WindowsRuntimeSystemExtensions].GetMethods()|Where-Object{$_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation` + "`" + `1'})[0]
function Await($Op,$Type){$t=$asTask.MakeGenericMethod($Type).Invoke($null,@($Op));$t.GetAwaiter().GetResult()}
$engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new('en-US'))
if(-not $engine){$engine=[Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()}
[Console]::Out.WriteLine('READY')
while($true){
  $path=[Console]::In.ReadLine()
  if($path -eq $null){break}
  try{
    if(-not $engine){throw 'Windows English text recognition is not installed'}
    $file=Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($path)) ([Windows.Storage.StorageFile])
    $stream=Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    try{
      $decoder=Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
      $bitmap=Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
      try{$result=Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
        $text=($result.Lines|ForEach-Object{$_.Text}) -join "` + "`" + `n"
        [Console]::Out.WriteLine((@{text=$text}|ConvertTo-Json -Compress))
      }finally{$bitmap.Dispose()}
    }finally{$stream.Dispose()}
  }catch{[Console]::Out.WriteLine((@{error=$_.Exception.Message}|ConvertTo-Json -Compress))}
}
`

type ocrWorker struct {
	mu   sync.Mutex
	cmd  *exec.Cmd
	in   io.WriteCloser
	out  *bufio.Reader
	dead bool
}

var (
	ocrPool    = make(chan *ocrWorker, 2)
	ocrOK      bool
	ocrProblem = "not started"
	ocrStateMu sync.Mutex
)

func ocrStart() {
	if !ocrAvailable() {
		setOCRState(false, "Windows OCR is only on Windows")
		return
	}
	for i := 0; i < 2; i++ {
		ocrPool <- &ocrWorker{dead: true}
	}
	// warm one up so the first slip is quick
	go func() {
		w := <-ocrPool
		_ = w.ensure()
		ocrPool <- w
	}()
}

func setOCRState(ok bool, s string) {
	ocrStateMu.Lock()
	ocrOK, ocrProblem = ok, s
	ocrStateMu.Unlock()
}

func ocrState() (bool, string) {
	ocrStateMu.Lock()
	defer ocrStateMu.Unlock()
	return ocrOK, ocrProblem
}

func (w *ocrWorker) ensure() error {
	if !w.dead {
		return nil
	}
	script := filepath.Join(printDir(), "ocr-reader.ps1")
	if b, err := os.ReadFile(script); err != nil || string(b) != ocrScript {
		if err := os.WriteFile(script, []byte(ocrScript), 0o644); err != nil {
			return err
		}
	}
	cmd := hiddenCmd("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script)
	in, err := cmd.StdinPipe()
	if err != nil {
		return err
	}
	outp, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	if err := cmd.Start(); err != nil {
		setOCRState(false, "can't start PowerShell: "+err.Error())
		return err
	}
	w.cmd, w.in, w.out = cmd, in, bufio.NewReaderSize(outp, 256<<10)
	line, err := w.readLine(20 * time.Second)
	if err != nil || strings.TrimSpace(line) != "READY" {
		w.kill()
		setOCRState(false, "Windows OCR didn't start")
		return errors.New("ocr didn't start")
	}
	w.dead = false
	setOCRState(true, "ready")
	return nil
}

func (w *ocrWorker) readLine(timeout time.Duration) (string, error) {
	type res struct {
		s   string
		err error
	}
	ch := make(chan res, 1)
	go func() {
		s, err := w.out.ReadString('\n')
		ch <- res{s, err}
	}()
	select {
	case r := <-ch:
		return r.s, r.err
	case <-time.After(timeout):
		w.kill()
		return "", errors.New("ocr timed out")
	}
}

func (w *ocrWorker) kill() {
	if w.cmd != nil && w.cmd.Process != nil {
		_ = w.cmd.Process.Kill()
		go w.cmd.Wait()
	}
	w.dead = true
}

// ocrReceipt reads the slip picture and parses it like Bridge Print.
func ocrReceipt(jobID string, images []rasterImage, text string) (ReceiptMeta, error) {
	if !ocrAvailable() {
		return ReceiptMeta{}, errors.New("no OCR here")
	}
	bmp, err := receiptBitmap(images)
	if err != nil || bmp == nil {
		return ReceiptMeta{}, errors.New("no picture")
	}
	path := filepath.Join(jobDir(), jobID+".receipt.bmp")
	if err := os.WriteFile(path, bmp, 0o600); err != nil {
		return ReceiptMeta{}, err
	}
	var w *ocrWorker
	select {
	case w = <-ocrPool:
	case <-time.After(10 * time.Second):
		return ReceiptMeta{}, errors.New("ocr busy")
	}
	defer func() { ocrPool <- w }()
	if err := w.ensure(); err != nil {
		return ReceiptMeta{}, err
	}
	if _, err := io.WriteString(w.in, path+"\n"); err != nil {
		w.kill()
		return ReceiptMeta{}, err
	}
	line, err := w.readLine(15 * time.Second)
	if err != nil {
		return ReceiptMeta{}, err
	}
	var r struct{ Text, Error string }
	if json.Unmarshal([]byte(line), &r) != nil {
		return ReceiptMeta{}, errors.New("ocr answer not readable")
	}
	if r.Error != "" {
		setOCRState(false, r.Error)
		return ReceiptMeta{}, errors.New(r.Error)
	}
	m := parseReceiptText(text + "\n" + r.Text)
	m.Method = "ocr"
	return m, nil
}
