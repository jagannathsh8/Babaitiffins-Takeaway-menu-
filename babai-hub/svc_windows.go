//go:build windows

package main

// The Windows service "Babai Console". It is a small supervisor that runs the Console
// (app\<version>\babai-console.exe --run) and:
//   - restarts it at once if it stops or is ended in Task Manager (Windows restarts the service
//     itself the same way);
//   - takes a new version when the Console hands one over (app\next.txt, only in the quiet hours
//     with no KOT coming in), and keeps it only if it runs fine for a minute; otherwise it goes
//     back to the version before by itself (app\bad.txt, app\rollback.txt).
// If the service stops, the Console stops with it (a Windows job object), so ports never stay
// taken by a leftover copy.

import (
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/svc"
)

type consoleService struct{}

func runService() {
	dataDir = appDataDir()
	_ = os.MkdirAll(appVersionsDir(), 0o755)
	if f, err := os.OpenFile(filepath.Join(dataDir, "service.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
		log.SetOutput(f)
	}
	if err := svc.Run(serviceName, consoleService{}); err != nil {
		log.Printf("service: %v", err)
	}
}

func (consoleService) Execute(args []string, r <-chan svc.ChangeRequest, s chan<- svc.Status) (bool, uint32) {
	s <- svc.Status{State: svc.StartPending}
	stop := make(chan struct{})
	done := make(chan struct{})
	go func() { supervise(stop); close(done) }()
	s <- svc.Status{State: svc.Running, Accepts: svc.AcceptStop | svc.AcceptShutdown}
	for c := range r {
		switch c.Cmd {
		case svc.Interrogate:
			s <- c.CurrentStatus
		case svc.Stop, svc.Shutdown:
			s <- svc.Status{State: svc.StopPending}
			close(stop)
			select {
			case <-done:
			case <-time.After(15 * time.Second):
			}
			return false, 0
		}
	}
	return false, 0
}

func readTrim(p string) string {
	b, err := os.ReadFile(p)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func isBad(v string) bool {
	for _, x := range strings.Fields(readTrim(filepath.Join(appVersionsDir(), "bad.txt"))) {
		if x == v {
			return true
		}
	}
	return false
}

func markBad(v string) {
	f, err := os.OpenFile(filepath.Join(appVersionsDir(), "bad.txt"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err == nil {
		fmt.Fprintln(f, v)
		f.Close()
	}
}

func unmarkBad(v string) {
	var keep []string
	for _, x := range strings.Fields(readTrim(filepath.Join(appVersionsDir(), "bad.txt"))) {
		if x != v {
			keep = append(keep, x)
		}
	}
	_ = os.WriteFile(filepath.Join(appVersionsDir(), "bad.txt"), []byte(strings.Join(keep, "\n")), 0o644)
}

func versionExe(v string) string { return filepath.Join(appVersionsDir(), v, "babai-console.exe") }

func exists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

// killOnClose: a job object that ends the Console when the service process goes away.
func killOnClose() windows.Handle {
	h, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return 0
	}
	info := windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION{}
	info.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	_, _ = windows.SetInformationJobObject(h, windows.JobObjectExtendedLimitInformation, uintptr(unsafe.Pointer(&info)), uint32(unsafe.Sizeof(info)))
	return h
}

func supervise(stop chan struct{}) {
	job := killOnClose()
	backoff := time.Second
	for {
		dir := appVersionsDir()
		cur, next := readTrim(filepath.Join(dir, "current.txt")), readTrim(filepath.Join(dir, "next.txt"))
		ver, trial := cur, false
		if next != "" && next != cur && !isBad(next) && exists(versionExe(next)) {
			ver, trial = next, true
		}
		exe := versionExe(ver)
		if ver == "" || !exists(exe) {
			exe, ver = exePath(), version // the installed program itself
		}
		health := filepath.Join(dir, "healthy-"+ver+".txt")
		_ = os.Remove(health)
		out, _ := os.OpenFile(filepath.Join(dataDir, "console-output.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
		cmd := exec.Command(exe, "--run")
		cmd.Env = append(os.Environ(), "BABAI_HEALTH_FILE="+health)
		cmd.Stdout, cmd.Stderr = out, out
		started := time.Now()
		if err := cmd.Start(); err != nil {
			log.Printf("can't start %s: %v", exe, err)
			if out != nil {
				out.Close()
			}
			if trial {
				rollBack(ver, cur, "it could not start: "+err.Error())
				continue
			}
			if waitOrStop(stop, 5*time.Second) {
				return
			}
			continue
		}
		if job != 0 {
			if ph, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(cmd.Process.Pid)); err == nil {
				_ = windows.AssignProcessToJobObject(job, ph)
				windows.CloseHandle(ph)
			}
		}
		log.Printf("started Console %s (pid %d)%s", ver, cmd.Process.Pid, map[bool]string{true: " - trying the new version", false: ""}[trial])
		exited := make(chan int, 1)
		go func() {
			err := cmd.Wait()
			code := 0
			if err != nil {
				code = 1
				if ee, ok := err.(*exec.ExitError); ok {
					code = ee.ExitCode()
				}
			}
			exited <- code
		}()
		promoted := false
		tick := time.NewTicker(2 * time.Second)
	wait:
		for {
			select {
			case <-stop:
				tick.Stop()
				_ = cmd.Process.Kill()
				<-exited
				if out != nil {
					out.Close()
				}
				return
			case <-tick.C:
				if trial && !promoted && exists(health) {
					promote(ver, cur)
					promoted = true
				}
				if trial && !promoted && time.Since(started) > 3*time.Minute {
					_ = cmd.Process.Kill() // never became healthy: rolled back when it has stopped
				}
			case code := <-exited:
				tick.Stop()
				if out != nil {
					out.Close()
				}
				if trial && !promoted {
					if exists(health) {
						promote(ver, cur)
					} else {
						rollBack(ver, cur, fmt.Sprintf("it stopped after %s (exit %d)", time.Since(started).Round(time.Second), code))
						break wait
					}
				}
				if code == 3 {
					backoff = time.Second
					break wait // hand-over to a new version (next.txt)
				}
				log.Printf("Console %s stopped (exit %d) after %s; restarting", ver, code, time.Since(started).Round(time.Second))
				if time.Since(started) < 30*time.Second {
					backoff *= 2
					if backoff > 30*time.Second {
						backoff = 30 * time.Second
					}
				} else {
					backoff = time.Second
				}
				if waitOrStop(stop, backoff) {
					return
				}
				break wait
			}
		}
	}
}

func waitOrStop(stop chan struct{}, d time.Duration) bool {
	select {
	case <-stop:
		return true
	case <-time.After(d):
		return false
	}
}

func promote(ver, prev string) {
	dir := appVersionsDir()
	_ = os.WriteFile(filepath.Join(dir, "current.txt"), []byte(ver), 0o644)
	_ = os.WriteFile(filepath.Join(dir, "previous.txt"), []byte(prev), 0o644)
	_ = os.Remove(filepath.Join(dir, "next.txt"))
	_ = os.Remove(filepath.Join(dir, "rollback.txt"))
	log.Printf("version %s works: kept (previous %s stays for going back)", ver, prev)
	// keep only the current and previous versions
	es, _ := os.ReadDir(dir)
	for _, e := range es {
		if e.IsDir() && e.Name() != ver && e.Name() != prev {
			_ = os.RemoveAll(filepath.Join(dir, e.Name()))
		}
	}
}

func rollBack(ver, cur, why string) {
	dir := appVersionsDir()
	markBad(ver)
	_ = os.Remove(filepath.Join(dir, "next.txt"))
	msg := fmt.Sprintf("%s|Version %s was not kept: %s. Version %s is running again.", time.Now().Format(time.RFC3339), ver, why, cur)
	_ = os.WriteFile(filepath.Join(dir, "rollback.txt"), []byte(msg), 0o644)
	log.Print(msg)
}
