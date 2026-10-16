// node --test babai-fix.test.mjs   (run next to receipt.mjs and petpooja-live.mjs)
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseReceiptText } from './receipt.mjs';
import { parseKeepingLongNumbers } from './petpooja-live.mjs';

const kot = (idLine, extra = '') => `Ownly 28\n14/10/26 16:38\nKOT - 1171\n${idLine}\nDelivery\nItem\nMysore Bajji (4 Pcs)\nKesari Bath\nPayment Status : Online Paid\nScan to Mark food ready\n${extra}`;

test('Ownly: clean OCR', () => {
  assert.deepEqual(parseReceiptText(kot('Ownly : 1791976083241811062')), { brand: 'Ownly', orderId: '1791976083241811062' });
});
test('Ownly: bold last digits read as a separate word', () => {
  assert.equal(parseReceiptText(kot('Ownly : 179197608324181 1062')).orderId, '1791976083241811062');
});
test('Ownly: O / l misread as 0 / 1', () => {
  assert.equal(parseReceiptText(kot('Ownly : 179197608324l8l1O62')).orderId, '1791976083241811062');
});
test('Ownly: bold digits on their own line, barcode digits below', () => {
  assert.equal(parseReceiptText(kot('Ownly : 179197608324181\n1062', '1791976083241811062')).orderId, '1791976083241811062');
});
test('Ownly: id line unreadable, barcode digits used', () => {
  assert.equal(parseReceiptText(kot('Ownly :', '1791976083241811062')).orderId, '1791976083241811062');
});
test('Swiggy and Zomato unchanged', () => {
  assert.deepEqual(parseReceiptText('Swiggy : 250951448167204\nKOT - 12\nDelivery'), { brand: 'Swiggy', orderId: '250951448167204' });
  assert.deepEqual(parseReceiptText('Zomato : 8722626388\nKOT - 13'), { brand: 'Zomato', orderId: '8722626388' });
});
test('phone numbers and KOT numbers are not order ids', () => {
  assert.equal(parseReceiptText('Self service\nKOT - 55\nPhone : 9876543210\nTotal : 235').orderId, null);
});
test('Petpooja feed keeps 19-digit ids exact', () => {
  const r = parseKeepingLongNumbers('{"data":[{"client_order_id":1791982601318835770,"status":5,"amount":12.5,"id":"x"}]}');
  assert.equal(r.data[0].client_order_id, '1791982601318835770');
  assert.equal(r.data[0].status, 5);
  assert.equal(r.data[0].amount, 12.5);
});
