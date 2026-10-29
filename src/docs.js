// Notia 1.2 — document helpers: unzip, xlsx/csv import, rich DOCX + PDF export (with images, charts, diagrams)
const inflate=async(u8)=>new Uint8Array(await new Response(new Blob([u8]).stream().pipeThrough(new DecompressionStream("deflate-raw"))).arrayBuffer());
// Minimal zip reader (central directory) → Map(name → {m,cs,us,off}) + get()
function unzip(u8){const v=new DataView(u8.buffer,u8.byteOffset,u8.byteLength);let e=u8.length-22;while(e>=0&&v.getUint32(e,true)!=0x06054b50)e--;if(e<0)throw Error("Not a zip file");
const n=v.getUint16(e+10,true);let p=v.getUint32(e+16,true);const M=new Map(),D=new TextDecoder();
for(let i=0;i<n;i++){const nl=v.getUint16(p+28,true),xl=v.getUint16(p+30,true),cl=v.getUint16(p+32,true);M.set(D.decode(u8.subarray(p+46,p+46+nl)),{m:v.getUint16(p+10,true),cs:v.getUint32(p+20,true),us:v.getUint32(p+24,true),off:v.getUint32(p+42,true)});p+=46+nl+xl+cl}
const get=async k=>{const f=M.get(k);if(!f)return null;const o=f.off,d=u8.subarray(o+30+v.getUint16(o+26,true)+v.getUint16(o+28,true));const c=d.subarray(0,f.cs);return f.m==0?c.slice():f.m==8?inflate(c):null};
return{M,get,text:async k=>{const b=await get(k);return b?new TextDecoder().decode(b):null}}}
// ---- table import (xlsx / csv / tsv) → 2D array of strings
async function readTable(file){const nm=file.name.toLowerCase();if(/\.xlsx$/.test(nm)){const z=unzip(new Uint8Array(await file.arrayBuffer())),X=s=>new DOMParser().parseFromString(s,"application/xml"),
ss=z.M.has("xl/sharedStrings.xml")?[...X(await z.text("xl/sharedStrings.xml")).getElementsByTagName("si")].map(s=>[...s.getElementsByTagName("t")].map(t=>t.textContent).join("")):[];
const sh=[...z.M.keys()].filter(k=>/^xl\/worksheets\/sheet\d+\.xml$/.test(k)).sort()[0];if(!sh)throw Error("Empty workbook");const rows=[];
for(const c of X(await z.text(sh)).getElementsByTagName("c")){const r=c.getAttribute("r"),[,L,N]=r.match(/([A-Z]+)(\d+)/),ci=[...L].reduce((a,ch)=>a*26+ch.charCodeAt(0)-64,0)-1,ty=c.getAttribute("t"),vv=c.getElementsByTagName("v")[0]?.textContent;
const val=ty=="s"?ss[+vv]:ty=="inlineStr"?c.textContent:vv??"";(rows[N-1]??=[])[ci]=val}
return rows.filter(Boolean).map(r=>Array.from(r,x=>x??""))}
const tx=await file.text(),d=tx.split("\n",1)[0].includes("\t")?"\t":tx.split("\n",1)[0].split(";").length>tx.split("\n",1)[0].split(",").length?";":",";return csvParse(tx,d)}
function csvParse(s,d){const R=[];let r=[],c="",q=false;for(let i=0;i<s.length;i++){const h=s[i];if(q){if(h=='"'){if(s[i+1]=='"'){c+='"';i++}else q=false}else c+=h}else if(h=='"')q=true;else if(h==d){r.push(c);c=""}else if(h=="\n"||h=="\r"){if(h=="\r"&&s[i+1]=="\n")i++;r.push(c);R.push(r);r=[];c=""}else c+=h}if(c||r.length){r.push(c);R.push(r)}return R.filter(r=>r.some(x=>x!==""))}
// ---- rasterise <img> / <svg> to JPEG bytes
const loadImg=src=>new Promise((ok,no)=>{const i=new Image();i.onload=()=>ok(i);i.onerror=no;i.src=src});
async function rast(el){let src;if(el.tagName=="IMG")src=el.src;else{const s=el.cloneNode(true);s.setAttribute("xmlns","http://www.w3.org/2000/svg");src="data:image/svg+xml;charset=utf-8,"+encodeURIComponent(new XMLSerializer().serializeToString(s))}
const i=await loadImg(src),w=i.naturalWidth||+el.getAttribute("width")||600,h=i.naturalHeight||+el.getAttribute("height")||400,c=document.createElement("canvas");c.width=w;c.height=h;const g=c.getContext("2d");g.fillStyle="#fff";g.fillRect(0,0,w,h);g.drawImage(i,0,0,w,h);
const b=atob(c.toDataURL("image/jpeg",.9).split(",")[1]);return{w,h,b:Uint8Array.from(b,ch=>ch.charCodeAt(0)),dw:el.getBoundingClientRect?.().width||w,al:el.closest?.("[data-al]")?.dataset.al||""}}
// ---- flatten the note DOM into blocks: {t,z,b,al,li} text lines, {i} images, {tb} tables
const BLK=new Set("DIV P H1 H2 H3 LI TR BLOCKQUOTE PRE UL OL TABLE HR".split(" "));
async function blocks(root,raster=true){const out=[];let line="",sty={};const nl=(force)=>{if(line.trim()||force)out.push({t:line.replace(/\u00a0/g," "),...sty});line="";sty={}};
async function w(n,ctx){if(n.nodeType==3){line+=n.data;return}if(n.nodeType!=1)return;const g=n.tagName;
if(g=="IMG"||g=="svg"||n.classList.contains("fig")){nl();const e=g=="IMG"||g=="svg"?n:n.querySelector("svg");if(raster&&e)try{out.push({i:await rast(e)})}catch{out.push({t:"[image]"})}else out.push({t:"[image]"});return}
if(n.classList.contains("mf")){line+="$"+n.dataset.tex+"$";return}
if(g=="BR"){nl(true);return}if(g=="STYLE"||g=="SCRIPT")return;
if(g=="TABLE"){nl();const rows=[];for(const r of n.rows)rows.push([...r.cells].map(c=>(c.innerText??c.textContent).replace(/\u00a0/g," ").trim()));out.push({tb:rows});return}
const blk=BLK.has(g);if(blk)nl();if(g=="LI"){const pa=n.parentElement;line=pa&&pa.tagName=="OL"?([...pa.children].indexOf(n)+1)+". ":"• ";}if(/^H[123]$/.test(g))sty={z:g=="H1"?18:g=="H2"?14:12,b:1};if(g=="BLOCKQUOTE")line="“";
const al=n.style?.textAlign;if(al&&blk)sty.al=al;for(const c of n.childNodes)await w(c);if(g=="BLOCKQUOTE")line+="”";if(blk)nl()}
for(const c of root.childNodes)await w(c);nl();return out}
// ---- DOCX
const X_=s=>String(s).replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;");
function docxBuild(T,bl){const media=[],R="http://schemas.openxmlformats.org/";
const jc=a=>a=="center"||a=="c"?'<w:jc w:val="center"/>':a=="right"||a=="r"?'<w:jc w:val="right"/>':a=="justify"?'<w:jc w:val="both"/>':"";
const par=(s,o={})=>`<w:p><w:pPr>${jc(o.al)}</w:pPr><w:r><w:rPr>${o.b?"<w:b/>":""}${o.z?`<w:sz w:val="${o.z*2}"/>`:""}</w:rPr><w:t xml:space="preserve">${X_(s)}</w:t></w:r></w:p>`;
let body=par(T,{b:1,z:20});
for(const b of bl){if(b.i){media.push(b.i);const n=media.length,cw=Math.min(b.i.dw||b.i.w,600),cx=Math.round(cw*9525),cy=Math.round(cw*b.i.h/b.i.w*9525);
body+=`<w:p><w:pPr>${jc(b.i.al)}</w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="${cx}" cy="${cy}"/><wp:docPr id="${n}" name="Image ${n}"/><a:graphic xmlns:a="${R}drawingml/2006/main"><a:graphicData uri="${R}drawingml/2006/picture"><pic:pic xmlns:pic="${R}drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="${n}" name="image${n}.jpg"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed="rIdm${n}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${cx}" cy="${cy}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>`}
else if(b.tb){const bd=["top","left","bottom","right","insideH","insideV"].map(s=>`<w:${s} w:val="single" w:sz="4" w:color="888888"/>`).join("");body+=`<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblBorders>${bd}</w:tblBorders></w:tblPr>${b.tb.map(r=>`<w:tr>${r.map(c=>`<w:tc><w:tcPr><w:tcW w:w="2000" w:type="dxa"/></w:tcPr>${par(c)}</w:tc>`).join("")}</w:tr>`).join("")}</w:tbl>${par("")}`}
else body+=par(b.t,b)}
const files=[["[Content_Types].xml",`<Types xmlns="${R}package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="jpg" ContentType="image/jpeg"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>`],
["_rels/.rels",`<Relationships xmlns="${R}package/2006/relationships"><Relationship Id="r1" Type="${R}officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>`],
["word/_rels/document.xml.rels",`<Relationships xmlns="${R}package/2006/relationships">${media.map((_,i)=>`<Relationship Id="rIdm${i+1}" Type="${R}officeDocument/2006/relationships/image" Target="media/image${i+1}.jpg"/>`).join("")}</Relationships>`],
["word/document.xml",`<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="${R}wordprocessingml/2006/main" xmlns:r="${R}officeDocument/2006/relationships" xmlns:wp="${R}drawingml/2006/wordprocessingDrawing"><w:body>${body}<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>`]];
media.forEach((m,i)=>files.push(["word/media/image"+(i+1)+".jpg",m.b]));return zipB(files)}
// zip writer that accepts strings or bytes (store only)
function zipB(files){const E=new TextEncoder(),P=[],D=[];let o=0;for(const[n,s]of files){const nb=E.encode(n),d=typeof s=="string"?E.encode(s):s,c=crc(d);
const h=new DataView(new ArrayBuffer(30));h.setUint32(0,0x04034b50,true);h.setUint16(4,20,true);h.setUint32(14,c,true);h.setUint32(18,d.length,true);h.setUint32(22,d.length,true);h.setUint16(26,nb.length,true);P.push(new Uint8Array(h.buffer),nb,d);
const g=new DataView(new ArrayBuffer(46));g.setUint32(0,0x02014b50,true);g.setUint16(4,20,true);g.setUint16(6,20,true);g.setUint32(16,c,true);g.setUint32(20,d.length,true);g.setUint32(24,d.length,true);g.setUint16(28,nb.length,true);g.setUint32(42,o,true);D.push(new Uint8Array(g.buffer),nb);o+=30+nb.length+d.length}
const cl=D.reduce((s,a)=>s+a.length,0),e=new DataView(new ArrayBuffer(22));e.setUint32(0,0x06054b50,true);e.setUint16(8,files.length,true);e.setUint16(10,files.length,true);e.setUint32(12,cl,true);e.setUint32(16,o,true);return new Blob([...P,...D,new Uint8Array(e.buffer)])}
// ---- PDF (A4, Helvetica, wrapped text, JPEG images, tables as rows)
const W1252={"‘":0x91,"’":0x92,"“":0x93,"”":0x94,"–":0x96,"—":0x97,"…":0x85,"•":0x95,"€":0x80};
const wa=s=>[...s].map(c=>{const k=c.charCodeAt(0);return W1252[c]?String.fromCharCode(W1252[c]):k>=32&&k<=255?c:"?"}).join("").replace(/[\\()]/g,"\\$&");
function pdfBuild(T,bl){const M=50,PW=595,PH=842,CW=PW-2*M,pages=[],imgs=[];let cur=[],y=PH-M;const np=()=>{pages.push(cur);cur=[];y=PH-M};
const wrap=(s,z)=>{const mx=Math.floor(CW/(z*.5)),o=[];for(let p of s.split("\n")){p=p.replace(/\s+$/,"");if(!p){o.push("");continue}while(p.length>mx){let k=p.lastIndexOf(" ",mx);if(k<mx/2)k=mx;o.push(p.slice(0,k));p=p.slice(k).trimStart()}o.push(p)}return o};
const txt=(s,z,b,al)=>{for(const l of wrap(s,z)){if(y-z<M)np();const wd=l.length*z*.5,x=al=="center"?M+(CW-wd)/2:al=="right"?M+CW-wd:M;cur.push(`BT /${b?"F2":"F1"} ${z} Tf ${x.toFixed(1)} ${(y-z).toFixed(1)} Td (${wa(l)}) Tj ET`);y-=z*1.35}};
txt(T,18,1);y-=6;
for(const b of bl){if(b.i){let w=Math.min(b.i.dw||b.i.w,CW),h=w*b.i.h/b.i.w;if(h>PH-2*M){h=PH-2*M;w=h*b.i.w/b.i.h}if(y-h<M)np();imgs.push(b.i);const x=b.i.al=="c"?M+(CW-w)/2:b.i.al=="r"?M+CW-w:M;cur.push(`q ${w.toFixed(1)} 0 0 ${h.toFixed(1)} ${x.toFixed(1)} ${(y-h).toFixed(1)} cm /Im${imgs.length} Do Q`);y-=h+8}
else if(b.tb)for(const r of b.tb)txt(r.join("  |  "),10,0);else txt(b.t,b.z||11,b.b,b.al)}
pages.push(cur);const E=new TextEncoder(),ch=[],X=[];let len=0;const put=a=>{const u=typeof a=="string"?Uint8Array.from(a,c=>c.charCodeAt(0)&255):a;ch.push(u);len+=u.length},obj=(n,head,stream)=>{X[n]=len;put(`${n} 0 obj\n${head}`);if(stream){put(`\nstream\n`);put(stream);put("\nendstream")}put("\nendobj\n")};
const k0=5,pg0=k0+imgs.length,xo=imgs.map((_,i)=>`/Im${i+1} ${k0+i} 0 R`).join(" ");put("%PDF-1.4\n%\xe2\xe3\xcf\xd3\n");
obj(1,"<</Type/Catalog/Pages 2 0 R>>");obj(2,`<</Type/Pages/Kids[${pages.map((_,i)=>pg0+2*i+" 0 R").join(" ")}]/Count ${pages.length}>>`);
obj(3,"<</Type/Font/Subtype/Type1/BaseFont/Helvetica/Encoding/WinAnsiEncoding>>");obj(4,"<</Type/Font/Subtype/Type1/BaseFont/Helvetica-Bold/Encoding/WinAnsiEncoding>>");
imgs.forEach((m,i)=>obj(k0+i,`<</Type/XObject/Subtype/Image/Width ${m.w}/Height ${m.h}/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode/Length ${m.b.length}>>`,m.b));
pages.forEach((p,i)=>{const c=p.join("\n"),n=pg0+2*i;obj(n,`<</Type/Page/Parent 2 0 R/MediaBox[0 0 ${PW} ${PH}]/Resources<</Font<</F1 3 0 R/F2 4 0 R>>/XObject<<${xo}>>>>/Contents ${n+1} 0 R>>`);obj(n+1,`<</Length ${c.length}>>`,c)});
const total=pg0+2*pages.length,xr=len;put(`xref\n0 ${total}\n0000000000 65535 f \n`+Array.from({length:total-1},(_,i)=>String(X[i+1]).padStart(10,"0")+" 00000 n \n").join("")+`trailer<</Size ${total}/Root 1 0 R>>\nstartxref\n${xr}\n%%EOF`);
const out=new Uint8Array(len);let o=0;for(const c of ch){out.set(c,o);o+=c.length}return out}
// ---- pack(k): used by export + share dialogs
async function pack(k){const n=cur_(),T=n.title||t("untitled"),nm=T.normalize("NFD").replace(/[\u0300-\u036f]/g,"").replace(/[^\w .-]/g,"_")+"."+k,E=new TextEncoder();
if(k=="md"){const c=ed.cloneNode(true);c.querySelectorAll(".mf").forEach(m=>m.replaceWith("$"+m.dataset.tex+"$"));c.querySelectorAll(".fig").forEach(f=>f.replaceWith("[figure]"));return[nm,E.encode("# "+T+"\n\n"+md(c.innerHTML))]}
const bl=await blocks(ed,k=="pdf"||k=="docx");
if(k=="txt")return[nm,E.encode(T+"\n\n"+bl.map(b=>b.tb?b.tb.map(r=>r.join("\t")).join("\n"):b.t||"").join("\n"))];
if(k=="pdf")return[nm,pdfBuild(T,bl)];return[nm,new Uint8Array(await docxBuild(T,bl).arrayBuffer())]}
