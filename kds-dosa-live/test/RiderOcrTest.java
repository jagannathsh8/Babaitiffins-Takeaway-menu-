package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.List;

/** Plain-JVM checks for RiderOcr with real text read from rider app screens. */
public final class RiderOcrTest {
    static int failures;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) failures++;
    }

    static RiderCalls.Order o(long id, String p, String oid, String slot, boolean ready) {
        return new RiderCalls.Order(id, p, RiderCalls.last4(oid), oid, slot, 0, ready, false);
    }

    public static void main(String[] a) {
        List<RiderCalls.Order> board = new ArrayList<RiderCalls.Order>();
        board.add(o(1, "Swiggy", "250671473118332", "10", false));
        board.add(o(2, "Ownly", "1791700811809339883", "11", false));
        board.add(o(3, "Ownly", "1791701046231479350", "12", false));
        board.add(o(4, "Ownly", "1791701346024622353", "13", false));
        board.add(o(5, "Ownly", "1791700782750606974", "14", false));
        board.add(o(6, "Swiggy", "250704258170997", "15", false));
        board.add(o(7, "Zomato", "8713250896", "16", false));
        // decoys: same last 4 digits as real orders, different IDs
        board.add(o(90, "Swiggy", "250671473110997", "90", false));
        board.add(o(91, "Zomato", "7311290896", "91", false));
        board.add(o(92, "Ownly", "1791700000000006974", "92", false));
        String[][] reads = {
            {"k_r1.jpg3", "Order ID : 250671473118332\n\n[i] This order requires an OTF\n\nAfter 4 minutes, you\n\nf\n\nget extra charge\n\nwaiting\n", "250671473118332"},
            {"k_r1.jpg11", "Order 1D\n\n)50671473118332\n\nTt\n\norder requires\n\nan OTF\n\no\n\nAfte\n\n1 minute\n\nVO\n\nf\n\nget extra cl\n\nirge\n\nyvaiting\n", "250671473118332"},
            {"k_r2.jpg3", "FIlvQ1j_179170081180933\n9883\n\nOrder ID:\n\n\u00ae You are now\n\nWait earning %0.50 for\nTimer Pal Re EE\n\n", "1791700811809339883"},
            {"k_r2.jpg11", "Order ID\n\nFIlvQ1j_179170081180933\n\n9883\n\n\u00ae You are now\n\nWait\n\nTimer\n\naach min nf\n\nearning 0.50 for\n", "1791700811809339883"},
            {"k_r3.jpg3", "4 Collect Food LJ\n\nNHBCHL_1791701046231479350\n\n\u00ae You are now earning 0.50 for\neach min of waiting. (upto 20\nmins)\n\nWait Timer\n\n07:07\n\n", "1791701046231479350"},
            {"k_r3.jpg11", "Collect Food\n\nNHLBI\n\n1791701046231479350\n\nWait Timer\n\n\u00ae You are now earning 0.50 for\n\nach min of waiting. (upto 20\n\n07:07\n\nmins)\n", "1791701046231479350"},
            {"k_r4.jpg3", "\u2014 a. he Ah J hee bb a bd _\u2014\n\na4 1D : ITBhKi_1791701346024622353\n\nA TE You are now earning\n) 20.50 for each min of\n04:48 waiting. (upto 20 mins)\n\n", "1791701346024622353"},
            {"k_r4.jpg11", "We ew\n\n_\u2014\n\na4 1D : ITBhKi_1791701346024622353\n\nCra rtd\n\nYou are now earning\n\n20.50 for each min of\n\n04:48\n\nwaiting. (upto 20 mins)\n", "1791701346024622353"},
            {"k_r5.jpg3", "4 WWWews VT ve\n\nOrder ID : higME4_1791700782750606974\n\nWait Timer \u00ae You are now earning 0.50 for\u2019\n. each min of waiting. (upto 20\n07:01 mins)\n\n", "1791700782750606974"},
            {"k_r5.jpg11", "WWWews VT ve\n\nv\n\n-r\n\nOrder ID\n\nhigME4_1791700782750606974\n\nxy\n\n\u00ae You are now earning 0.50 for\u2019\n\nWait Timer\n\n07:01\n\neach min of waiting. (upto 20\n\nmins)\n", "1791700782750606974"},
            {"k_s1.jpg3", "w Pickup 1of2\n\n#25070425817 0997\n\nBabai Tiffins\n\nItem Details\n", "250704258170997"},
            {"k_s1.jpg11", "w Pickup 1of2\n\n#25070425817 0997\n\nBabai Tiffins\n\nItem Details\n", "250704258170997"},
            {"k_z1.jpg3", "ORDER ID\n\n8713250896\n\nBabai Tiffins\n\n", "8713250896"},
            {"k_z1.jpg11", "ORDER ID\n\n8713250896\n\nBabai Tiffins\n", "8713250896"},
        };
        for (String[] r : reads) {
            RiderOcr.Match m = RiderOcr.match(RiderOcr.candidates(r[1]), board);
            check("read " + r[0], m != null && m.order.orderId.equals(r[2]) && m.sure,
                    m == null ? RiderOcr.candidates(r[1]) : m.order.orderId + " / " + m.digits);
        }
        // nothing on the board: no match, but an unknown labelled ID for "not on board"
        String zomatoOther = "ORDER ID\n\n8799912345\n\nBabai Tiffins\n";
        List<String> c = RiderOcr.candidates(zomatoOther);
        check("unknown order", RiderOcr.match(c, board) == null && "8799912345".equals(RiderOcr.unknownId(c))
                && RiderOcr.labelled(zomatoOther), c);
        // prices / times / addresses never match
        String noise = "Municipal New 347, 348 And 349, Survey 235/11\nWait Timer 07:07 earning 0.50 for\n";
        check("noise", RiderOcr.match(RiderOcr.candidates(noise), board) == null
                && RiderOcr.unknownId(RiderOcr.candidates(noise)) == null, RiderOcr.candidates(noise));
        // partial read (only last 6 digits) is a match, but not a sure one
        RiderOcr.Match m = RiderOcr.match(RiderOcr.candidates("Order ID : ..3118332"), board);
        check("partial needs twice", m != null && !m.sure && m.order.orderId.equals("250671473118332"), m == null ? "-" : m.digits);
        // O / I misread inside the number
        m = RiderOcr.match(RiderOcr.candidates("#2507O425817 O997"), board);
        check("O as 0", m != null && m.order.orderId.equals("250704258170997"), m == null ? "-" : m.digits);
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
