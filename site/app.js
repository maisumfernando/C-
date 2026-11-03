// Copy the APK checksum. No tracking, no third-party code.
(function () {
  var button = document.getElementById("copy-sha");
  var code = document.getElementById("sha");
  if (!button || !code) return;
  button.addEventListener("click", function () {
    var text = code.textContent.trim();
    var done = function () {
      button.textContent = "Copiado";
      setTimeout(function () { button.textContent = "Copiar"; }, 1800);
    };
    var fallback = function () {
      var range = document.createRange();
      range.selectNodeContents(code);
      var selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
    };
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, fallback);
    } else {
      fallback();
    }
  });
})();
