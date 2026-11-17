package com.babai.screens;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;

/**
 * The TV's own settings page on the shop Wi-Fi (http://<TV>:8080): change the facts and the board's
 * settings from a computer or phone, test the announcement, and see what the board reads from
 * Petpooja. Saving needs the PIN (1234). Nothing leaves the shop network.
 */
final class TvWeb {
    interface App {
        JSONObject state();                  // everything the page shows
        String save(JSONObject in);          // "" when saved, else why not
        void announce();                     // a test announcement on the TV
    }

    static final int PORT = 8080;
    static final String PIN = "1234";
    private final App app;
    private volatile ServerSocket server;
    volatile int port;

    TvWeb(App app) { this.app = app; }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int p = PORT; p < PORT + 5 && server == null; p++) {
                    try { server = new ServerSocket(p, 16); port = p; } catch (Throwable ignored) { }
                }
                if (server == null) return;
                while (true) {
                    try {
                        final Socket s = server.accept();
                        new Thread(new Runnable() { @Override public void run() { serve(s); } }).start();
                    } catch (Throwable e) {
                        if (server == null || server.isClosed()) return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        try { if (server != null) server.close(); } catch (Throwable ignored) { }
        server = null;
    }

    /** "http://192.168.1.40:8080" for each of the TV's addresses on the local network. */
    static String addresses(int port) {
        StringBuilder b = new StringBuilder();
        for (String a : Net.myAddresses()) b.append(b.length() > 0 ? "  or  " : "").append("http://").append(a).append(":").append(port);
        return b.toString();
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(8000);
            InputStream in = s.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) return;
            String method = rl[0], path = rl[1];
            int len = 0;
            for (String l : lines) {
                if (l.toLowerCase(Locale.US).startsWith("content-length:")) {
                    try { len = Integer.parseInt(l.substring(15).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            byte[] body = new byte[Math.max(0, Math.min(len, 256 * 1024))];
            int r = 0;
            while (r < body.length) { int x = in.read(body, r, body.length - r); if (x < 0) break; r += x; }
            OutputStream out = s.getOutputStream();
            if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
                send(out, 200, "text/html; charset=utf-8", PAGE);
            } else if (method.equals("GET") && path.startsWith("/api/state")) {
                JSONObject st = app.state();
                send(out, 200, "application/json", st.toString());
            } else if (method.equals("POST") && (path.equals("/api/save") || path.equals("/api/announce"))) {
                JSONObject j;
                try { j = new JSONObject(new String(body, "UTF-8")); } catch (Throwable e) { j = new JSONObject(); }
                if (!PIN.equals(j.optString("pin"))) {
                    send(out, 200, "application/json", new JSONObject().put("ok", false).put("msg", "Wrong PIN.").toString());
                } else if (path.equals("/api/announce")) {
                    app.announce();
                    send(out, 200, "application/json", new JSONObject().put("ok", true).put("msg", "Playing on the TV now.").toString());
                } else {
                    String why = app.save(j);
                    send(out, 200, "application/json", new JSONObject().put("ok", why.isEmpty()).put("msg", why.isEmpty() ? "Saved. The TV uses it now." : why).toString());
                }
            } else {
                send(out, 404, "text/plain", "Not found");
            }
        } catch (Throwable ignored) {
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, n = 0;
        while ((c = in.read()) >= 0) {
            b.write(c);
            n = (c == '\r' || c == '\n') ? n + 1 : 0;
            if (n == 4) return b.toString("UTF-8");
            if (b.size() > 16 * 1024) return null;
        }
        return null;
    }

    private static void send(OutputStream o, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String h = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        o.write(h.getBytes("UTF-8"));
        o.write(b);
        o.flush();
    }

    static String localName() {
        try { return InetAddress.getLocalHost().getHostName(); } catch (Throwable t) { return ""; }
    }

    // The page (one file, no internet needed).
    static final String PAGE = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Babai Dosa Board</title><style>"
            + ":root{--y:#f9bc21;--b:#171717;--c:#fff6df;--g:#0a8f4a;--r:#b91c1c;--line:#e3e5ea;--mute:#6b7280}"
            + "*{box-sizing:border-box}body{margin:0;font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,Arial,sans-serif;background:#f3f4f8;color:var(--b)}"
            + "header{background:var(--y);padding:14px 18px;display:flex;gap:12px;align-items:center;flex-wrap:wrap}header b{font-size:22px}header span{font-weight:600}"
            + "main{max-width:1000px;margin:0 auto;padding:16px}.card{background:#fff;border:1px solid var(--line);border-radius:14px;padding:14px 16px;margin-bottom:14px}"
            + "h2{margin:0 0 4px;font-size:18px}.mute{color:var(--mute)}.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:8px 0}"
            + "button{font:inherit;font-weight:700;border:2px solid var(--b);background:#fff;border-radius:10px;padding:7px 12px;cursor:pointer}button.on{background:var(--b);color:var(--y)}button.go{background:var(--g);border-color:var(--g);color:#fff}"
            + "input[type=text],input[type=password]{font:inherit;border:1px solid #ccc;border-radius:10px;padding:8px 10px}"
            + ".fact{display:grid;grid-template-columns:28px 1fr auto;gap:8px;align-items:center;margin:6px 0}.fact input[type=text]{width:100%}.fact .ic{display:flex;gap:4px}.fact .ic button{padding:4px 9px}"
            + ".fact.off input[type=text]{opacity:.5;text-decoration:line-through}"
            + ".bar{position:sticky;bottom:0;background:#fff;border-top:1px solid var(--line);padding:10px 16px;display:flex;gap:10px;align-items:center;flex-wrap:wrap;justify-content:center}"
            + ".msg{font-weight:700}.msg.ok{color:var(--g)}.msg.bad{color:var(--r)}"
            + "table{width:100%;border-collapse:collapse;font-size:14px}th,td{text-align:left;padding:6px 8px;border-top:1px solid var(--line)}th{color:var(--mute);font-size:12px;text-transform:uppercase}"
            + ".pill{border-radius:999px;padding:2px 9px;font-size:12px;font-weight:700;background:#eef2ff}.pill.r{background:#e8f5ec;color:#0a6b37}.pill.p{background:#fff3e0;color:#9a5b00}.pill.x{background:#f1f5f9;color:#475569}"
            + "</style></head><body><header><b>Babai Dosa Board</b><span id=\"tv\"></span></header><main>"
            + "<div class=\"card\"><h2>Did you know? facts</h2><p class=\"mute\">They scroll at the bottom of the board, in this order. Untick to hide one without deleting it.</p>"
            + "<div class=\"row\"><label><input type=\"checkbox\" id=\"nums\"> Today's numbers first (favourite dosa, dosas made today, rush hour, top 3, yesterday's favourite)</label></div>"
            + "<div id=\"nowNums\" class=\"mute\"></div><div id=\"facts\"></div><div class=\"row\"><button id=\"add\">+ Add a fact</button><span class=\"mute\" id=\"count\"></span></div></div>"
            + "<div class=\"card\"><h2>The board</h2>"
            + "<div class=\"row\"><b>Dosas on this board:</b><span id=\"kinds\"></span></div><div class=\"row\" id=\"chansRow\"></div>"
            + "<div class=\"row\"><b>Ready tokens stay:</b><span id=\"clear\"></span><span class=\"mute\">(they leave sooner when dispatched in Petpooja)</span></div>"
            + "<div class=\"row\"><b>Announcement:</b><span id=\"voice\"></span></div><div class=\"row\"><b>Say the token:</b><span id=\"digits\"></span></div>"
            + "<div class=\"row mute\" id=\"say\"></div><div class=\"row\"><button id=\"test\">🔊 Play a test announcement on the TV</button></div></div>"
            + "<div class=\"card\"><h2>What the board reads from Petpooja now</h2><p class=\"mute\" id=\"pp\"></p><div style=\"overflow-x:auto\"><table><thead><tr><th>Token</th><th>Type</th><th>Dosas (status)</th><th>KOT status</th><th>Order status</th><th>On the board</th></tr></thead><tbody id=\"rows\"></tbody></table></div>"
            + "<p class=\"mute\">Dish status: 1 = cooking, 9 = Food ready, 10 = dispatched. KOT status: 9 = ready, 10 = dispatched. A token leaves the board when Petpooja marks it dispatched, or after the time set above.</p></div>"
            + "</main><div class=\"bar\"><input type=\"password\" id=\"pin\" placeholder=\"PIN\" inputmode=\"numeric\" size=\"6\"><button class=\"go\" id=\"save\">Save</button><span class=\"msg\" id=\"msg\"></span></div>"
            + "<script>"
            + "var S=null,F=null,dirty=false;function $(i){return document.getElementById(i)}"
            + "function esc(s){return String(s==null?'':s).replace(/[&<>\"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c]})}"
            + "function mark(){dirty=true;$('msg').className='msg';$('msg').textContent='Unsaved changes'}"
            + "function chips(id,opts,cur,multi){$(id).innerHTML=opts.map(function(o){var on=multi?cur.indexOf(o[0])>=0:cur===o[0];return '<button data-g=\"'+id+'\" data-v=\"'+esc(o[0])+'\" class=\"'+(on?'on':'')+'\">'+(on?'✓ ':'')+esc(o[1])+'</button>'}).join(' ')}"
            + "function drawFacts(){$('facts').innerHTML=F.map(function(f,i){return '<div class=\"fact'+(f.on?'':' off')+'\"><input type=\"checkbox\" data-on=\"'+i+'\"'+(f.on?' checked':'')+' aria-label=\"Show\"><input type=\"text\" data-t=\"'+i+'\" value=\"'+esc(f.t)+'\"><span class=\"ic\"><button data-up=\"'+i+'\" aria-label=\"Up\">↑</button><button data-dn=\"'+i+'\" aria-label=\"Down\">↓</button><button data-del=\"'+i+'\" aria-label=\"Delete\">✕</button></span></div>'}).join('');$('count').textContent=F.filter(function(f){return f.on&&f.t.trim()}).length+' facts shown'}"
            + "function drawSettings(){chips('kinds',[['I','Dine in'],['P','Pick up'],['S','Self service'],['D','Online']],S.kinds,true);"
            + "if(S.kinds.indexOf('D')>=0){var all=S.seenChans.slice();S.chans.forEach(function(c){if(all.indexOf(c)<0)all.push(c)});$('chansRow').innerHTML='<b>Online channels:</b> <button data-ch=\"\" class=\"'+(S.chans.length?'':'on')+'\">'+(S.chans.length?'':'✓ ')+'All channels</button> '+all.map(function(c){var on=S.chans.indexOf(c)>=0;return '<button data-ch=\"'+esc(c)+'\" class=\"'+(on?'on':'')+'\">'+(on?'✓ ':'')+esc(c)+'</button>'}).join(' ')+(all.length?'':' <span class=\"mute\">channels show here once their first order is read</span>')}else $('chansRow').innerHTML='';"
            + "chips('clear',[[5,'5 min'],[10,'10 min'],[15,'15 min'],[30,'30 min']].map(function(x){return [String(x[0]),x[1]]}),String(S.clearMin),false);chips('voice',[['1','On'],['0','Off']],S.voice?'1':'0',false);chips('digits',[['0','Whole number (two thousand four hundred one)'],['1','Digit by digit (two four zero one)']],S.digits?'1':'0',false);"
            + "$('say').textContent='The TV plays a chime, then says: \\u201c'+S.sample+'\\u201d';$('nums').checked=S.numbersOn}"
            + "function drawBoard(st){$('tv').textContent=(st.ppHost?'Petpooja '+st.ppHost:'Petpooja not set')+' \\u00b7 '+st.version;$('pp').textContent=st.readAt?'Read '+Math.round((Date.now()-st.readAt)/1000)+' s ago \\u00b7 '+st.board.length+' KOTs with a dosa':'Nothing read from Petpooja yet.';"
            + "$('nowNums').textContent=st.numbers.length?'Now: '+st.numbers.join(' \\u00b7 '):'Today\\u2019s numbers appear once enough dosas are made.';"
            + "$('rows').innerHTML=st.board.map(function(k){return '<tr><td><b>'+esc(k.token)+'</b></td><td>'+esc(k.type)+'</td><td>'+k.items.map(function(i){return esc(i.n)+' <span class=\"pill '+(i.s==10?'x':i.s==9?'r':'p')+'\">'+i.s+'</span>'}).join('<br>')+(k.hid?'<br><span class=\"mute\">'+k.hid+' dish(es) dispatched or replaced</span>':'')+'</td><td>'+esc(k.st||'\\u2013')+'</td><td>'+esc(k.oh||'\\u2013')+'</td><td>'+esc(k.shown)+'</td></tr>'}).join('')||'<tr><td colspan=\"6\" class=\"mute\">No KOT with a dosa right now.</td></tr>'}"
            + "function load(first){fetch('/api/state').then(function(r){return r.json()}).then(function(st){if(first||!dirty){S=st.settings;F=st.facts;drawFacts();drawSettings()}drawBoard(st)}).catch(function(){$('pp').textContent='Can\\u2019t reach the TV.'})}"
            + "document.addEventListener('click',function(e){var b=e.target.closest('button');if(!b)return;var d=b.dataset;"
            + "if(d.g==='kinds'){var i=S.kinds.indexOf(d.v);if(i>=0)S.kinds=S.kinds.replace(d.v,'');else S.kinds+=d.v;if(!S.kinds)S.kinds='I';drawSettings();mark()}"
            + "else if(d.g==='clear'){S.clearMin=+d.v;drawSettings();mark()}else if(d.g==='voice'){S.voice=d.v==='1';drawSettings();mark()}else if(d.g==='digits'){S.digits=d.v==='1';drawSettings();mark()}"
            + "else if(d.ch!==undefined){if(!d.ch)S.chans=[];else{var j=S.chans.indexOf(d.ch);if(j>=0)S.chans.splice(j,1);else S.chans.push(d.ch)}drawSettings();mark()}"
            + "else if(d.up!==undefined){var u=+d.up;if(u>0){var t=F[u];F[u]=F[u-1];F[u-1]=t;drawFacts();mark()}}else if(d.dn!==undefined){var w=+d.dn;if(w<F.length-1){var t2=F[w];F[w]=F[w+1];F[w+1]=t2;drawFacts();mark()}}"
            + "else if(d.del!==undefined){F.splice(+d.del,1);drawFacts();mark()}else if(b.id==='add'){F.push({t:'',on:true});drawFacts();mark();var x=document.querySelectorAll('#facts input[type=text]');x[x.length-1].focus()}"
            + "else if(b.id==='save'||b.id==='test'){var body={pin:$('pin').value};if(b.id==='save'){body.facts=F.filter(function(f){return f.t.trim()});body.numbersOn=$('nums').checked;body.kinds=S.kinds;body.chans=S.chans;body.clearMin=S.clearMin;body.voice=S.voice;body.digits=S.digits}"
            + "fetch(b.id==='save'?'/api/save':'/api/announce',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}).then(function(r){return r.json()}).then(function(r){$('msg').className='msg '+(r.ok?'ok':'bad');$('msg').textContent=r.msg;if(r.ok&&b.id==='save'){dirty=false;load(true)}}).catch(function(){$('msg').className='msg bad';$('msg').textContent='Can\\u2019t reach the TV.'})}});"
            + "document.addEventListener('input',function(e){var t=e.target;if(t.dataset.t!==undefined){F[+t.dataset.t].t=t.value;mark()}});"
            + "document.addEventListener('change',function(e){var t=e.target;if(t.dataset.on!==undefined){F[+t.dataset.on].on=t.checked;drawFacts();mark()}else if(t.id==='nums')mark()});"
            + "load(true);setInterval(function(){load(false)},3000);"
            + "</script></body></html>";
}
