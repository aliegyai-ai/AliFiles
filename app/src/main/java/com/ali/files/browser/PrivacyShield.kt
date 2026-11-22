package com.ali.files.browser

/**
 * Anti-fingerprinting for private tabs. Sites can recognise a phone without cookies, from how it
 * draws pictures (canvas / WebGL), plays sound, and what hardware it reports. In private tabs these
 * get tiny random changes — new ones every private session — so visits can't be linked together.
 * Pages look and work the same.
 */
object PrivacyShield {
    /** a new random seed for every private session (changes when the private tabs are wiped) */
    @Volatile private var seed = newSeed()

    private fun newSeed() = (1..2_000_000_000).random()

    fun newSession() { seed = newSeed() }

    fun script(): String = """
(function(){
  if (window.__aliShield) return; window.__aliShield = 1;
  var s = $seed;
  function rnd(){ s = (s * 1103515245 + 12345) & 0x7fffffff; return s / 0x7fffffff; }
  function def(o, k, v){ try { Object.defineProperty(o, k, { get: function(){ return v; }, configurable: true }); } catch(e){} }

  // hardware details that identify the phone
  def(Navigator.prototype, 'hardwareConcurrency', 4);
  def(Navigator.prototype, 'deviceMemory', 4);
  def(Navigator.prototype, 'doNotTrack', '1');
  def(Navigator.prototype, 'globalPrivacyControl', true);
  try { delete Navigator.prototype.getBattery; } catch(e){}
  def(Navigator.prototype, 'getBattery', undefined);

  // canvas: a few invisible pixel changes before a page reads a drawing
  function noise(ctx, w, h){
    try {
      if (!w || !h) return;
      var img = ctx.getImageData(0, 0, Math.min(w, 64), Math.min(h, 64));
      for (var i = 0; i < 10; i++) {
        var p = Math.floor(rnd() * (img.data.length / 4)) * 4;
        img.data[p] = img.data[p] ^ 1;
      }
      ctx.putImageData(img, 0, 0);
    } catch(e){}
  }
  var toDataURL = HTMLCanvasElement.prototype.toDataURL;
  HTMLCanvasElement.prototype.toDataURL = function(){
    try { var c = this.getContext('2d'); if (c) noise(c, this.width, this.height); } catch(e){}
    return toDataURL.apply(this, arguments);
  };
  var toBlob = HTMLCanvasElement.prototype.toBlob;
  HTMLCanvasElement.prototype.toBlob = function(){
    try { var c = this.getContext('2d'); if (c) noise(c, this.width, this.height); } catch(e){}
    return toBlob.apply(this, arguments);
  };
  var getImageData = CanvasRenderingContext2D.prototype.getImageData;
  CanvasRenderingContext2D.prototype.getImageData = function(){
    var d = getImageData.apply(this, arguments);
    try { for (var i = 0; i < 6; i++) { var p = Math.floor(rnd() * (d.data.length / 4)) * 4; d.data[p] = d.data[p] ^ 1; } } catch(e){}
    return d;
  };

  // WebGL: hide the exact graphics chip
  function glPatch(proto){
    if (!proto) return;
    var gp = proto.getParameter;
    proto.getParameter = function(p){
      if (p === 37445) return 'Google Inc.';
      if (p === 37446) return 'ANGLE (Generic GPU)';
      return gp.apply(this, arguments);
    };
  }
  glPatch(window.WebGLRenderingContext && WebGLRenderingContext.prototype);
  glPatch(window.WebGL2RenderingContext && WebGL2RenderingContext.prototype);

  // audio: tiny changes in the sound a page analyses
  try {
    var gcd = AudioBuffer.prototype.getChannelData;
    AudioBuffer.prototype.getChannelData = function(){
      var d = gcd.apply(this, arguments);
      try { for (var i = 0; i < d.length; i += 100) d[i] = d[i] + (rnd() - 0.5) * 1e-7; } catch(e){}
      return d;
    };
    var gff = AnalyserNode.prototype.getFloatFrequencyData;
    AnalyserNode.prototype.getFloatFrequencyData = function(a){
      gff.apply(this, arguments);
      try { for (var i = 0; i < a.length; i += 10) a[i] = a[i] + (rnd() - 0.5) * 1e-4; } catch(e){}
    };
  } catch(e){}
})();
"""
}
