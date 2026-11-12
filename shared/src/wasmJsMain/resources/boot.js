// Starts the app, kept out of index.html so the CSP needs no inline script.
// WebAssembly GC is what the app needs (Safari 18.2+, current Chrome, Edge, Firefox).
const wasmGc = (() => {
  try { return WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 120, 0])); }
  catch (e) { return false; }
})();
if (!wasmGc) {
  document.getElementById('loading').style.display = 'none';
  document.getElementById('unsupported').style.display = 'block';
} else {
  const script = document.createElement('script');
  script.type = 'module';
  script.src = 'glosso.js';
  document.body.appendChild(script);
}
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
