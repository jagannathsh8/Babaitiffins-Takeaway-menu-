package com.babai.screens;

import java.util.List;

public class NetTest {
    static int n;
    static void eq(Object a, Object b) { n++; if (!String.valueOf(a).equals(String.valueOf(b))) throw new AssertionError(a + " != " + b); }

    public static void main(String[] x) {
        eq(Net.base("192.168.1.5"), "http://192.168.1.5:8790");
        eq(Net.base(" http://192.168.1.5:9000/ "), "http://192.168.1.5:9000");
        eq(Net.base(""), "");
        List<String> nb = Net.neighbours("192.168.1.10");
        eq(nb.size(), 253);
        eq(nb.get(0), "192.168.1.9");
        eq(nb.get(1), "192.168.1.11");
        eq(nb.contains("192.168.1.10"), false);
        eq(Net.neighbours("bad").size(), 0);
        eq(Net.jsonStr("{\"ok\":false,\"msg\":\"Wrong PIN.\"}", "msg"), "Wrong PIN.");
        eq(Net.jsonStr("{\"ok\": true, \"key\": \"ab\\\"c\"}", "key"), "ab\"c");
        eq(Net.jsonTrue("{\"ok\": true}", "ok"), true);
        eq(Net.jsonTrue("{\"ok\":false}", "ok"), false);
        eq(Net.jsonNum("{\"b\": 12,\"url\":\"app.apk\"}", "b"), 12);
        eq(Net.jsonNum("{}", "b"), -1);
        eq(Net.quote("a\"b\\c\n"), "\"a\\\"b\\\\c \"");
        System.out.println("NetTest: " + n + " checks OK");
    }
}
