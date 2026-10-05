package com.pp.kds.dosa;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal .xlsx writer (no libraries): several sheets of text/number cells, bold header row,
 * frozen first row/column, column widths. Opens in Excel, Google Sheets and LibreOffice.
 */
final class XlsxWriter {

    static final class Sheet {
        final String name;
        final List<Object[]> rows = new ArrayList<Object[]>();
        double[] widths;
        int boldRows = 1;
        boolean freeze = true;

        Sheet(String name) {
            this.name = name.length() > 31 ? name.substring(0, 31) : name;
        }

        Sheet row(Object... cells) {
            rows.add(cells);
            return this;
        }
    }

    private final List<Sheet> sheets = new ArrayList<Sheet>();

    Sheet sheet(String name) {
        Sheet s = new Sheet(name);
        sheets.add(s);
        return s;
    }

    void write(OutputStream out) throws IOException {
        ZipOutputStream z = new ZipOutputStream(out);
        StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 1; i <= sheets.size(); i++) {
            ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        ct.append("</Types>");
        put(z, "[Content_Types].xml", ct.toString());
        put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>");
        StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 1; i <= sheets.size(); i++) {
            wb.append("<sheet name=\"").append(esc(sheets.get(i - 1).name)).append("\" sheetId=\"").append(i)
                    .append("\" r:id=\"rId").append(i).append("\"/>");
            rels.append("<Relationship Id=\"rId").append(i)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i).append(".xml\"/>");
        }
        rels.append("<Relationship Id=\"rId").append(sheets.size() + 1)
                .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        wb.append("</sheets></workbook>");
        rels.append("</Relationships>");
        put(z, "xl/workbook.xml", wb.toString());
        put(z, "xl/_rels/workbook.xml.rels", rels.toString());
        // style 0 = normal, 1 = bold header, 2 = number with 2 decimals
        put(z, "xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"0.00\"/></numFmts>"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFE8B0\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>");
        for (int i = 1; i <= sheets.size(); i++) put(z, "xl/worksheets/sheet" + i + ".xml", sheetXml(sheets.get(i - 1)));
        z.finish();
        z.flush();
    }

    private static String sheetXml(Sheet s) {
        StringBuilder x = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + (s.freeze ? "<sheetViews><sheetView workbookViewId=\"0\"><pane xSplit=\"1\" ySplit=\"" + s.boldRows
                + "\" topLeftCell=\"B" + (s.boldRows + 1) + "\" activePane=\"bottomRight\" state=\"frozen\"/></sheetView></sheetViews>" : ""));
        if (s.widths != null) {
            x.append("<cols>");
            for (int c = 0; c < s.widths.length; c++) {
                x.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1).append("\" width=\"")
                        .append(s.widths[c]).append("\" customWidth=\"1\"/>");
            }
            x.append("</cols>");
        }
        x.append("<sheetData>");
        for (int r = 0; r < s.rows.size(); r++) {
            Object[] row = s.rows.get(r);
            x.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < row.length; c++) {
                Object v = row[c];
                if (v == null) continue;
                String ref = col(c) + (r + 1);
                boolean header = r < s.boldRows;
                if (v instanceof Number) {
                    double d = ((Number) v).doubleValue();
                    if (Double.isNaN(d) || Double.isInfinite(d)) continue;
                    x.append("<c r=\"").append(ref).append("\"").append(header ? " s=\"1\"" : d == Math.rint(d) ? "" : " s=\"2\"")
                            .append("><v>").append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d))
                            .append("</v></c>");
                } else {
                    x.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(header ? " s=\"1\"" : "")
                            .append("><is><t xml:space=\"preserve\">").append(esc(String.valueOf(v))).append("</t></is></c>");
                }
            }
            x.append("</row>");
        }
        x.append("</sheetData></worksheet>");
        return x.toString();
    }

    static String col(int c) {
        StringBuilder sb = new StringBuilder();
        c++;
        while (c > 0) {
            int m = (c - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return sb.toString();
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (ch >= 0x20 || ch == '\t' || ch == '\n' || ch == '\r') sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(Charset.forName("UTF-8")));
        z.closeEntry();
    }

    // ---- the Prep Live day report ---------------------------------------------------------

    /** Builds the workbook for one date: Summary, Prep items (hourly), Dishes (hourly). */
    static XlsxWriter dayReport(PrepStats.Model m, PrepStats.Day d, String generatedAt) {
        XlsxWriter x = new XlsxWriter();
        Sheet sum = x.sheet("Summary");
        sum.widths = new double[]{28, 40};
        sum.freeze = false;
        sum.row("Babai Tiffins - Prep Live report", "");
        sum.row("Date", d.date);
        sum.row("Data source", d.source);
        if (d.forecast) {
            sum.row("Type", "FORECAST - 60% same day last month + 20% avg last 4 + 20% avg last 2");
            sum.row("Based on dates", d.sources.isEmpty() ? "no history" : join(d.sources));
        }
        sum.row("Holiday / festival", d.holiday ? "Yes" : "No");
        if (d.kotsByType != null) {
            sum.row("KOTs - Dine In", d.kotsByType[0]);
            sum.row("KOTs - Pick Up", d.kotsByType[1]);
            sum.row("KOTs - Delivery", d.kotsByType[2]);
            sum.row("KOTs - Other", d.kotsByType[3]);
        }
        sum.row("Generated", generatedAt);
        sum.row("Note", "Prep quantities are BOM-standard (portions x recipe), all order types.");

        Object[] head = new Object[27];
        head[0] = "Item";
        head[1] = "Unit";
        head[2] = "Total";
        for (int h = 0; h < 24; h++) head[3 + h] = String.format(java.util.Locale.US, "%02d:00", h);

        Sheet prep = x.sheet("Prep items (OP-CP)");
        prep.widths = widths(36);
        prep.row(head);
        List<Integer> order = new ArrayList<Integer>();
        for (int t = 0; t < m.targets.length; t++) if (t < d.prep.length && total(d.prep[t]) > 0) order.add(t);
        final PrepStats.Day day = d;
        java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer a, Integer b) {
                return Double.compare(total(day.prep[b]), total(day.prep[a]));
            }
        });
        for (int t : order) prep.row(line(m.targets[t], m.unit(t), d.prep[t]));

        Sheet dishes = x.sheet("Dishes sold");
        dishes.widths = widths(36);
        Object[] dh = head.clone();
        dh[0] = "Dish";
        dh[1] = "";
        dh[2] = "Qty";
        dishes.row(dh);
        List<String> names = new ArrayList<String>(d.dishes.keySet());
        java.util.Collections.sort(names, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                return Double.compare(total(day.dishes.get(b)), total(day.dishes.get(a)));
            }
        });
        for (String n : names) dishes.row(line(n, "", d.dishes.get(n)));
        return x;
    }

    private static double[] widths(double first) {
        double[] w = new double[27];
        w[0] = first;
        w[1] = 6;
        w[2] = 10;
        for (int i = 3; i < 27; i++) w[i] = 7.5;
        return w;
    }

    private static Object[] line(String name, String unit, double[] hours) {
        Object[] row = new Object[27];
        row[0] = name;
        row[1] = unit;
        row[2] = round(total(hours));
        for (int h = 0; h < 24; h++) if (hours != null && hours[h] != 0) row[3 + h] = round(hours[h]);
        return row;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String x : l) sb.append(sb.length() == 0 ? "" : ", ").append(x);
        return sb.toString();
    }

    static double total(double[] h) {
        double s = 0;
        if (h != null) for (double v : h) s += v;
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
