import { inflateSync } from 'node:zlib';
import fs from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
const exec=promisify(execFile);

// Decode length-delimited graphic blocks; never interpret compressed pixels as text.
export function decodeReceipt(raw) {
  const images=[]; let text='';
  for(let i=0;i<raw.length;) {
    if(raw[i]===29&&raw[i+1]===118&&raw[i+2]===48&&i+8<=raw.length){
      const row=raw.readUInt16LE(i+4),height=raw.readUInt16LE(i+6),size=row*height;
      if(i+8+size>raw.length)throw Error('Incomplete raster image.');
      if(row>0&&row<=256&&height>0&&height<=10000)images.push({width:row*8,height,pixels:raw.subarray(i+8,i+8+size)});
      i+=8+size;text+='\n';continue;
    }
    if(raw[i]===29 && (raw[i+1]===56||raw[i+1]===40) && raw[i+2]===76) {
      const header=raw[i+1]===56?7:5;
      if(i+header>raw.length) break;
      const length=header===7?raw.readUInt32LE(i+3):raw.readUInt16LE(i+3);
      if(i+header+length>raw.length) break;
      const p=raw.subarray(i+header,i+header+length);
      if(p.length>=10 && p[0]===48 && p[1]===112) {
        const width=p.readUInt16LE(6),height=p.readUInt16LE(8),size=Math.ceil(width/8)*height;
        if(width>0 && width<=2048 && height>0 && height<=10000 && size<=4*1024*1024) {
          const pixels=p[2]===49?inflateSync(p.subarray(10),{maxOutputLength:size}):p[2]===48?p.subarray(10):null;
          if(pixels?.length===size) images.push({width,height,pixels});
        }
      }
      i+=header+length; text+='\n';continue;
    }
    if(raw[i]===27) {
      const cmd=raw[i+1];
      if(cmd===42&&i+5<=raw.length){const size=raw.readUInt16LE(i+3)*(raw[i+2]>=32?3:1);i+=5+size;text+='\n';continue;}
      const lengths={64:2,50:2,51:3,61:3,97:3,69:3,74:3,82:3,116:3,36:4,99:4,33:3,45:3,32:3};
      i+=lengths[cmd]||2;continue;
    }
    if(raw[i]===29) {
      if(raw[i+1]===40 && i+5<=raw.length){i+=5+raw.readUInt16LE(i+3);continue;}
      i+=3;continue;
    }
    const c=raw[i++];if(c===10||c===13)text+='\n';else if(c>=32&&c<=126)text+=String.fromCharCode(c);
  }
  return {images,text};
}
export function receiptBitmap(images) {
  if(!images.length) return null;
  const width=Math.max(...images.map(i=>i.width))+32;
  const height=images.reduce((n,i)=>n+i.height+12,16);
  if(height>10000) throw Error('Receipt too tall for text recognition.');
  const stride=Math.ceil(width*3/4)*4;
  const bmp=Buffer.alloc(54+stride*height,255);
  bmp.fill(0,0,54);bmp.write('BM');bmp.writeUInt32LE(bmp.length,2);bmp.writeUInt32LE(54,10);bmp.writeUInt32LE(40,14);bmp.writeInt32LE(width,18);bmp.writeInt32LE(-height,22);bmp.writeUInt16LE(1,26);bmp.writeUInt16LE(24,28);bmp.writeUInt32LE(stride*height,34);
  let top=8;
  for(const img of images) {
    const rowBytes=Math.ceil(img.width/8);
    for(let y=0;y<img.height;y++)for(let x=0;x<img.width;x++)if(img.pixels[y*rowBytes+(x>>3)]&(128>>(x&7))) {
      const at=54+(top+y)*stride+(x+16)*3;bmp.fill(0,at,at+3);
    }
    top+=img.height+12;
  }
  return bmp;
}
export function parseReceiptText(text) {
  const lines=text.replace(/\r/g,'').split('\n').map(s=>s.trim()).filter(Boolean);
  const toingHeading=/\bto(?:ing|wing)\s+by\s+swiggy\b/i;
  const isToing=lines.some(line=>toingHeading.test(line));
  const pickupBarcode=/\bpick\s*up\s+barcode\b/i;
  const excludedSpecialLine=/\b(?:kot|passcode|otp|date|time)\b/i;
  const specialLongId=line=>{
    if(!line||excludedSpecialLine.test(line))return null;
    const compact=line.replace(/[\s-]/g,'');
    if(!/^\d{10,20}$/.test(compact))return null;
    // Compact dates/timestamps can otherwise look like a long order number.
    if(/^(?:19|20)\d{6}(?:[01]\d[0-5]\d(?:[0-5]\d)?)?$/.test(compact))return null;
    return compact;
  };
  // Prefer explicit service labels and receipt headings over mentions in item descriptions.
  const candidates=lines.filter(s=>/order\s*(?:type|from|source)|(?:delivery|service)\s*type/i.test(s));
  const header=lines.slice(0,18);
  const sources=[[/\bzomato\b/i,'Zomato'],[/\bswiggy\b/i,'Swiggy'],[/\bownly\b/i,'Ownly'],[/\bmagicpin\b/i,'Magicpin'],[/\bondc\b/i,'ONDC'],[/\bself[ -]*service\b/i,'Self service'],[/\bpick[ -]*up\b|\btake[ -]*away\b|\bparcel\b/i,'Pick up'],[/\bdine[ -]*in\b/i,'Dine in']];
  let brand=null;
  for(const line of [...candidates,...header]) {if(/barcode|delivery partner/i.test(line))continue;const matches=sources.filter(([re])=>re.test(line));if(matches.length===1){brand=matches[0][1];break;}}
  const explicit=candidates.map(line=>line.match(/(?:order\s*(?:type|from|source)|(?:delivery|service)\s*type)\s*[:#-]\s*([A-Z][A-Z0-9 .&-]{1,30})/i)).find(Boolean);
  if(!brand&&explicit)brand=explicit[1].trim().replace(/\s+/g,' ');
  let orderId=null;
  if(isToing){
    const anchors=[
      ...lines.map((line,index)=>toingHeading.test(line)?{index,limit:2}:null).filter(Boolean),
      ...lines.map((line,index)=>pickupBarcode.test(line)?{index,limit:4}:null).filter(Boolean)
    ];
    for(const {index,limit} of anchors){
      for(let offset=1;offset<=limit&&index+offset<lines.length;offset++){
        const value=specialLongId(lines[index+offset]);
        if(value){orderId=value;break;}
      }
      if(orderId)break;
    }
  }
  const ignoredLabels=/^(?:kot|otp|order(?:\s*(?:id|no\.?|number))?|table(?:\s*(?:id|no\.?|number))?|bill(?:\s*(?:id|no\.?|number))?|token|customer|phone|mobile|payment|total|gst|invoice|cashier|date|time|(?:delivery\s+)?passcode)$/i;
  const sourceId=lines.slice(0,18).map(line=>line.match(/^\s*([A-Z][A-Z0-9 .&-]{1,23})\s*[:#-]\s*([A-Z0-9][A-Z0-9-]{1,39})\s*$/i)).find(match=>match&&/\d/.test(match[2])&&!ignoredLabels.test(match[1].trim()));
  if(!orderId&&sourceId){orderId=sourceId[2];if(!brand)brand=sourceId[1].trim().replace(/\s+/g,' ');}
  // Babai: OCR splits a long number when part of it is printed bold (Ownly KOTs print the last 4
  // digits bigger: "Ownly : 179197608324181 1062") and reads 0/1 as O/l. Accept "<Brand> : <digits with
  // spaces>" and clean the digits.
  const ocrDigits=value=>value.replace(/[Oo]/g,'0').replace(/[Il|]/g,'1').replace(/[\s-]/g,'');
  if(!orderId){
    for(const line of lines.slice(0,18)){
      const m=line.match(/^\s*([A-Z][A-Z0-9 .&-]{1,23}?)\s*[:#;-]\s*([0-9OoIl|][0-9OoIl| -]{4,48}[0-9OoIl|])\s*$/i);
      if(!m||ignoredLabels.test(m[1].trim()))continue;
      const digits=ocrDigits(m[2]);
      if(/^\d{6,24}$/.test(digits)){orderId=digits;if(!brand)brand=m[1].trim().replace(/\s+/g,' ');break;}
    }
  }
  // The number printed under the KOT barcode is one plain line of digits: when it continues the number
  // read above (bold part cut off), or nothing was read, use it. 13+ digits so phone numbers never count.
  // A long number read with a few O / l in it (e.g. 179197608324l8l1O62) is that number.
  if(orderId&&/^[0-9OoIl|]{10,}$/.test(orderId)&&(orderId.match(/\d/g)||[]).length>=orderId.length*0.7)orderId=ocrDigits(orderId);
  const longIds=[...new Set(lines.map(line=>/^[0-9OoIl| -]+$/.test(line)?specialLongId(ocrDigits(line)):null).filter(v=>v&&v.length>=13))];
  if(orderId&&/^\d+$/.test(orderId)){
    const longer=longIds.filter(v=>v.length>orderId.length&&v.startsWith(orderId)).sort((a,b)=>b.length-a.length)[0];
    if(longer)orderId=longer;
  } else if(!orderId&&longIds.length===1){
    orderId=longIds[0];
  }
  for(let i=0;i<lines.length;i++) {
    if(orderId) break;
    const match=lines[i].match(/\b(?:online\s+|external\s+|zomato\s+|swiggy\s+)?order\s*(?:id|no\.?|number|#)\s*[:#-]?\s*([A-Z0-9][A-Z0-9-]{1,39})?\s*$/i);
    if(match){const value=match[1]||lines[i+1];if(value&&/^[A-Z0-9][A-Z0-9-]{1,39}$/i.test(value)&&/\d/.test(value)){orderId=value;break;}}
  }
  return {brand,orderId,...(isToing?{displayBrand:'Toing / Swiggy'}:{})};
}
export async function recognizeReceipt(raw, bitmapPath) {
  const {images,text}=decodeReceipt(raw);
  if(!images.length)return {...parseReceiptText(text),method:'text'};
  const bmp=receiptBitmap(images);fs.writeFileSync(bitmapPath,bmp);
  const {stdout}=await exec('powershell.exe',['-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',fileURLToPath(new URL('./Read-Receipt.ps1',import.meta.url)),'-FilePath',bitmapPath],{windowsHide:true,timeout:30000,maxBuffer:1024*1024});
  const result=JSON.parse(stdout.trim());
  return {...parseReceiptText(text+'\n'+result.text),method:'ocr'};
}
