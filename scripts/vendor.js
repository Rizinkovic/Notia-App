// Copies the PDF engine and the KaTeX math engine into src/vendor so the app works fully offline.
const f=require("fs");f.mkdirSync("src/vendor/katex/fonts",{recursive:true});
for(const n of ["pdf.min.mjs","pdf.worker.min.mjs"])f.copyFileSync("node_modules/pdfjs-dist/build/"+n,"src/vendor/"+n);
for(const n of ["katex.min.js","katex.min.css"])f.copyFileSync("node_modules/katex/dist/"+n,"src/vendor/katex/"+n);
for(const n of f.readdirSync("node_modules/katex/dist/fonts"))if(n.endsWith(".woff2"))f.copyFileSync("node_modules/katex/dist/fonts/"+n,"src/vendor/katex/fonts/"+n);
