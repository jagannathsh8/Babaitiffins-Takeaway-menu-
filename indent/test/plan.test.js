// node test/plan.test.js  – the planner's maths on rows copied from the real BOM sheet.
const P = require("../plan.js");
let fails = 0;
const ok = (name, cond, info) => { if (!cond) { fails++; console.log("FAIL", name, info === undefined ? "" : JSON.stringify(info)); } else console.log("ok  ", name); };
const near = (a, b, e = 1e-6) => Math.abs(a - b) < e;
const header = ["Product Code","Product Name","Ingredients","SKUs","Ingredients UOM","Cost/Unit","  RM Yield %  "," Recipe Qty "," BOM Qty ","Ingredient Cost","Yield","Item Cost","Product UOM","Format","Modified Date","Product SKU"];
const rows = [
 ["","OP - Dosa batter","CP - Dosa Batter",2000,"kg",29.5,1,0.882,0.882,26.03,1,26.2,"kg","","",3002],
 ["","OP - Dosa batter","RO water",1045,"ltr",0,1,0.132,0.132,0,1,26.2,"kg","","",3002],
 ["","OP - Dosa batter","Table salt",1208,"kg",26.6,1,0.007,0.007,0.2,1,26.2,"kg","","",3002],
 [309,"Onion Dosa","OP - Dosa batter",3002,"kg",26.23,1,0.11,0.11,2.88,1,24.2,"portion","","",309],
 [309,"Onion Dosa","Onion",1305,"kg",62.82,0.88,0.07,0.08,5,1,24.2,"portion","","",309],
 [309,"Onion Dosa","CP - Allam Chutney",2008,"kg",67.99,1,0.025,0.025,1.7,1,24.2,"portion","","",309],
 [309,"Onion Dosa","CP - Breakfast Sambar",2012,"kg",40.56,1,0.045,0.045,1.83,1,24.2,"portion","","",309],
 [309,"Onion Dosa","OP - Butter mix",3003,"kg",244.28,1,0.012,0.012,2.93,1,24.2,"portion","","",309],
 ["","CP - Dosa Batter","White Urad Dal",1301,"kg",132,1,0.2,0.2,0,1,29.5,"kg","","",2000],
 ["","CP - Dosa Batter","Sona Masoori Rice",1302,"kg",76.8,1,0.55,0.55,0,1,29.5,"kg","","",2000],
];
const m = P.buildModel({ header, rows });
ok("dish found", !!P.matchDish(m, "Onion Dosa") && !!P.matchDish(m, "ONION  dosa"));
ok("OP not a dish", !P.matchDish(m, "OP - Dosa batter"));
ok("kinds", m.items[P.norm("Onion")].kind === "RM" && m.items[P.norm("CP - Allam Chutney")].kind === "CP" && m.items[P.norm("OP - Dosa batter")].kind === "OP");
const out = { cp: {}, rm: {}, missing: {} };
P.explode(m, P.matchDish(m, "Onion Dosa"), 10, out);
ok("CP through OP: dosa batter 10 x 0.11 x 0.882", near(out.cp[P.norm("CP - Dosa Batter")], 10 * 0.11 * 0.882), out.cp);
ok("CP direct", near(out.cp[P.norm("CP - Allam Chutney")], 0.25) && near(out.cp[P.norm("CP - Breakfast Sambar")], 0.45));
ok("RM with yield (BOM qty)", near(out.rm[P.norm("Onion")], 0.8) && near(out.rm[P.norm("Table salt")], 10 * 0.11 * 0.007));
ok("OP without recipe reported", near(out.missing["OP - Butter mix"], 0.12), out.missing);
const ck = P.explodeCP(m, { [P.norm("CP - Dosa Batter")]: 100, [P.norm("CP - Allam Chutney")]: 5 });
ok("central kitchen RM", near(ck.rm[P.norm("White Urad Dal")], 20) && near(ck.rm[P.norm("Sona Masoori Rice")], 55), ck.rm);
ok("central kitchen missing recipe", near(ck.missing[P.norm("CP - Allam Chutney")], 5));
// sales: 5 weeks of Wednesdays + other days; target Wed 2026-10-14
const days = {};
const put = (date, h, q) => { (days[date] = days[date] || {})["Onion Dosa"] = (days[date]["Onion Dosa"] || new Array(24).fill(0)); days[date]["Onion Dosa"][h] += q; };
put("2026-10-07", 8, 40); put("2026-10-07", 19, 20);   // 1 week back
put("2026-09-30", 8, 36); put("2026-09-30", 19, 18);
put("2026-09-23", 8, 30); put("2026-09-23", 19, 16);
put("2026-09-16", 8, 28); put("2026-09-16", 19, 12);   // 4 weeks back
const t = P.typicalFor(days, "2026-10-14", false);
const exp8 = 0.6 * 28 + 0.2 * (40 + 36 + 30 + 28) / 4 + 0.2 * (40 + 36) / 2;
ok("typical 60/20/20", near(t.dishes["Onion Dosa"][8], exp8), t.dishes["Onion Dosa"][8]);
const plan = P.planOutlet(m, days, "2026-10-14", { buffer: 0.05 });
const sam = plan.cp[P.norm("CP - Breakfast Sambar")];
ok("drop split: morning 6:30, evening 5:30", sam[0] > 0 && sam[1] === 0 && sam[2] > 0, sam);
ok("unknown dish listed", Object.keys(P.planOutlet(m, { "2026-10-07": { "Mystery Dish": new Array(24).fill(1) } }, "2026-10-14", {}).unmatched).length === 1);
ok("rounding", P.roundQty(0.33, "kg") === 0.4 && P.roundQty(3.2, "kg") === 3.5 && P.roundQty(12.1, "kg") === 13 && P.roundQty(11, "pcs") === 15 && P.roundQty(0, "kg") === 0);
ok("hub payload", P.dayFromHub({ s: "hub", r: [["Onion Dosa", "8:2 9:1"]] })["Onion Dosa"][9] === 1);
ok("holiday uses Sundays", P.typicalFor({ "2026-10-11": { "Onion Dosa": new Array(24).fill(1) } }, "2026-10-14", true).sources[0] === "2026-10-11");
console.log(fails ? fails + " FAILED" : "ALL PASS");
process.exit(fails ? 1 : 0);
