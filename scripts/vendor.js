// Copies the PDF engine into src/vendor so the app works fully offline.
const f=require("fs");f.mkdirSync("src/vendor",{recursive:true});
for(const n of ["pdf.min.mjs","pdf.worker.min.mjs"])f.copyFileSync("node_modules/pdfjs-dist/build/"+n,"src/vendor/"+n);
