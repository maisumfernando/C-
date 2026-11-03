// Small enhancements only: copy the APK checksum and scroll the screenshot strip.
// No tracking, no third-party code.
(function () {
  var button = document.getElementById("copy-sha");
  var code = document.getElementById("sha");
  if (button && code) {
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
  }

  var strip = document.getElementById("strip");
  var prev = document.getElementById("strip-prev");
  var next = document.getElementById("strip-next");
  if (strip && prev && next) {
    var step = function (dir) {
      var shot = strip.querySelector(".shot");
      var width = shot ? shot.getBoundingClientRect().width + 26 : 280;
      strip.scrollBy({ left: dir * width * 2, behavior: "smooth" });
    };
    prev.addEventListener("click", function () { step(-1); });
    next.addEventListener("click", function () { step(1); });
  }
})();
